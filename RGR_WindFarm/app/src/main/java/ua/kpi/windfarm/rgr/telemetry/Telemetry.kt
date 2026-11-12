package ua.kpi.windfarm.rgr.telemetry

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm.rgr.data.AlertRepository
import ua.kpi.windfarm.rgr.data.ForecastRepository
import ua.kpi.windfarm.rgr.data.MeasurementRepository
import ua.kpi.windfarm.rgr.data.StationRepository
import ua.kpi.windfarm.rgr.data.local.MeasurementEntity
import ua.kpi.windfarm.rgr.data.local.StationEntity
import ua.kpi.windfarm.rgr.data.spec
import ua.kpi.windfarm.rgr.domain.AlertRules
import ua.kpi.windfarm.rgr.domain.AlertThresholds
import ua.kpi.windfarm.rgr.domain.WindPhysics
import ua.kpi.windfarm.rgr.notify.AlertNotifier
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Модель вітру для станції: середнє (з прогнозу, якщо є) + добовий цикл + інерційний шум (AR(1)).
 * Використовується замість реальної SCADA-телеметрії, якої немає на навчальному стенді.
 */
class WindModel(seed: Long) {
    private val rnd = Random(seed)
    private var noise = 0.0
    private var dir = 220.0 + rnd.nextDouble(-30.0, 30.0)

    fun next(timeMs: Long, mean: Double): Pair<Double, Double> {
        noise = noise * 0.85 + rnd.nextDouble(-1.2, 1.2)
        val hour = (timeMs / 3_600_000.0 + 2) % 24                        // київський час ≈ UTC+2
        val daily = 1.5 * sin((hour - 9) / 24 * 2 * PI)                      // удень вітер сильніший
        dir = (dir + rnd.nextDouble(-4.0, 4.0) + 360) % 360
        return (mean + daily + noise).coerceIn(0.0, 32.0) to dir
    }
}

/**
 * Збирач телеметрії: кожні [periodMs] мс генерує вимір для кожної станції, записує його в Room,
 * перевіряє правила тривог і показує сповіщення. Працює в області застосунку (не прив'язаний до екрана).
 */
class TelemetryCollector(
    private val scope: CoroutineScope,
    private val stations: StationRepository,
    private val measurements: MeasurementRepository,
    private val forecast: ForecastRepository,
    private val alerts: AlertRepository,
    private val notifier: AlertNotifier?,
    private val periodMs: Long = 10_000
) {
    private val models = HashMap<Long, WindModel>()
    private val recentWinds = HashMap<Long, ArrayDeque<Double>>()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val thresholds = MutableStateFlow(AlertThresholds())
    /** Ручний «вплив погоди» для демонстрації (наприклад, +15 м/с — шторм). */
    val weatherBoost = MutableStateFlow(0.0)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _running.value = true
        job = scope.launch {
            seedHistoryIfEmpty()
            measurements.cleanup()
            while (isActive) {
                val now = System.currentTimeMillis()
                stations.stations.first().forEach { s -> runCatching { sample(s, now) } }
                delay(periodMs)
            }
        }
    }

    fun stop() { job?.cancel(); _running.value = false }

    private suspend fun meanWind(s: StationEntity, now: Long): Double {
        val f = forecast.observe(s.id).first().minByOrNull { kotlin.math.abs(it.timeMs - now) }
        return (f?.takeIf { kotlin.math.abs(it.timeMs - now) < 2 * 3600_000 }?.windMs ?: (7.5 + s.id)) + weatherBoost.value
    }

    private suspend fun sample(s: StationEntity, now: Long) {
        val model = models.getOrPut(s.id) { WindModel(s.id * 31) }
        val (wind, dir) = model.next(now, meanWind(s, now))
        val temp = 8.0 + 4 * sin(((now / 3_600_000.0 + 2) % 24 - 14) / 24 * 2 * PI)
        val pressure = 1012.0 + (s.id % 3)
        val humidity = 70.0 + (s.id * 5 % 20)
        val rho = WindPhysics.airDensity(temp, pressure)
        val spec = s.spec()
        val expectedKw = WindPhysics.powerKw(wind, spec, rho) * s.turbines * s.availability
        // фактична потужність трохи відрізняється від розрахункової (втрати, турбулентність)
        val actualKw = expectedKw * (0.93 + Random.nextDouble(0.0, 0.07))
        measurements.add(MeasurementEntity(stationId = s.id, timestamp = now, windMs = wind, windDirDeg = dir, temperatureC = temp,
            pressureHpa = pressure, humidity = humidity, powerMw = actualKw / 1000, intervalH = periodMs / 3_600_000.0))
        val history = recentWinds.getOrPut(s.id) { ArrayDeque() }
        AlertRules.checkMeasurement(wind, temp, humidity, actualKw, expectedKw, history.toList(), thresholds.value, now).forEach { e ->
            alerts.raise(s.id, e)?.let { notifier?.show(it, s.name) }
        }
        history.addLast(wind); while (history.size > 20) history.removeFirst()
    }

    /** Під час першого запуску — 7 діб погодинної історії, щоб аналітика мала дані одразу. */
    private suspend fun seedHistoryIfEmpty() {
        if (measurements.count() > 0) return
        val now = System.currentTimeMillis()
        val hourMs = 3_600_000L
        stations.stations.first().forEach { s ->
            val model = WindModel(s.id * 97)
            val spec = s.spec()
            val rows = (7 * 24 downTo 1).map { k ->
                val t = now - k * hourMs
                val synoptic = 3.5 * sin(t / (60.0 * hourMs) * 2 * PI + s.id)     // проходження фронтів ~2,5 доби
                val (w, d) = model.next(t, 8.0 + s.id * 0.4 + synoptic)
                val temp = 8.0 + 4 * sin(((t / 3_600_000.0 + 2) % 24 - 14) / 24 * 2 * PI)
                val p = WindPhysics.powerKw(w, spec, WindPhysics.airDensity(temp, 1012.0)) * s.turbines * s.availability / 1000
                MeasurementEntity(stationId = s.id, timestamp = t, windMs = w, windDirDeg = d, temperatureC = temp, pressureHpa = 1012.0,
                    humidity = 72.0, powerMw = p * 0.96, intervalH = 1.0, source = "history")
            }
            measurements.addAll(rows)
        }
    }
}
