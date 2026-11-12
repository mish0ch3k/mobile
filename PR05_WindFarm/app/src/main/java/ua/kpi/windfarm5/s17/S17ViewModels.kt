package ua.kpi.windfarm5.s17

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.WindPowerCalculator
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorMath
import ua.kpi.windfarm5.sensors.SensorSample
import ua.kpi.windfarm5.sensors.Sensors

/**
 * Базовий збирач кількох сенсорів: для кожного — останній показ, історія модулів
 * (для графіка) та помилка, якщо сенсора немає. Підписка діє, поки жива ViewModel.
 */
open class MultiSensorViewModel(kinds: List<SensorKind>, private val historySize: Int = 60) : ViewModel() {
    val latest = MutableStateFlow<Map<SensorKind, SensorSample>>(emptyMap())
    val history = MutableStateFlow<Map<SensorKind, List<Float>>>(emptyMap())
    val errors = MutableStateFlow<Map<SensorKind, String>>(emptyMap())
    private val jobs = HashMap<SensorKind, Job>()

    init { kinds.forEach(::start) }

    fun start(kind: SensorKind) {
        if (jobs[kind]?.isActive == true) return
        jobs[kind] = Sensors.repo.stream(kind)
            .onEach { s ->
                latest.update { it + (kind to s) }
                history.update { h -> h + (kind to ((h[kind] ?: emptyList()) + s.scalar).takeLast(historySize)) }
                onSample(s)
            }
            .catch { e -> errors.update { it + (kind to (e.message ?: "помилка сенсора")) } }
            .launchIn(viewModelScope)
    }

    fun stop(kind: SensorKind) { jobs.remove(kind)?.cancel() }

    protected open fun onSample(s: SensorSample) {}
}

class BasicSensorViewModel : MultiSensorViewModel(listOf(SensorKind.ACCELEROMETER), historySize = 80) {
    /** Останні 10 показів для таблиці (новіші — зверху). */
    val table = MutableStateFlow<List<SensorSample>>(emptyList())
    override fun onSample(s: SensorSample) = table.update { (listOf(s) + it).take(10) }
}

class MediumSensorViewModel : MultiSensorViewModel(listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE, SensorKind.MAGNETOMETER, SensorKind.PRESSURE))

// ============================ ПРОСУНУТИЙ ============================
/** Запис журналу показів для таблиці з фільтрацією та сортуванням. */
data class LogRow(val kind: SensorKind, val value: Float, val time: Long, val exceeded: Boolean)

enum class RowSort(val title: String) { TIME("Час"), VALUE("Значення"), SENSOR("Сенсор") }

/**
 * Панель моніторингу: користувач обирає сенсори та порогові значення; журнал показів
 * фільтрується за типом і діапазоном значень, сортується; перевищення фіксуються як тривоги.
 */
class AdvancedSensorViewModel : MultiSensorViewModel(emptyList(), historySize = 40) {
    val availableKinds: List<SensorKind> = Sensors.repo.available()
    val shown = MutableStateFlow(availableKinds.filter { it in setOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE, SensorKind.LIGHT) }.toSet())
    val thresholds = MutableStateFlow(mapOf(
        SensorKind.ACCELEROMETER to 15f, SensorKind.GYROSCOPE to 2f, SensorKind.MAGNETOMETER to 80f,
        SensorKind.PRESSURE to 1030f, SensorKind.LIGHT to 2000f, SensorKind.TEMPERATURE to 35f,
        SensorKind.HUMIDITY to 90f, SensorKind.PROXIMITY to 3f))
    val rows = MutableStateFlow<List<LogRow>>(emptyList())
    val alarms = MutableStateFlow<List<String>>(emptyList())

    val filterKinds = MutableStateFlow<Set<SensorKind>>(emptySet())
    val minValue = MutableStateFlow(0f)
    val onlyExceeded = MutableStateFlow(false)
    val sort = MutableStateFlow(RowSort.TIME)

    /** Таблиця = журнал після фільтрів і сортування. */
    val table: StateFlow<List<LogRow>> = combine(rows, filterKinds, minValue, onlyExceeded, sort) { r, k, min, ex, s ->
        r.filter { (k.isEmpty() || it.kind in k) && kotlin.math.abs(it.value) >= min && (!ex || it.exceeded) }
            .let { l -> when (s) { RowSort.TIME -> l.sortedByDescending { it.time }; RowSort.VALUE -> l.sortedByDescending { it.value }; RowSort.SENSOR -> l.sortedBy { it.kind.ordinal } } }
            .take(40)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lastLogged = HashMap<SensorKind, Long>()

    init { shown.value.forEach(::start) }

    fun toggleShown(k: SensorKind) {
        val now = shown.value
        if (k in now) { shown.value = now - k; stop(k) } else { shown.value = now + k; start(k) }
    }

    fun setThreshold(k: SensorKind, v: Float) = thresholds.update { it + (k to v) }

    override fun onSample(s: SensorSample) {
        val now = s.timestampMs
        if (now - (lastLogged[s.kind] ?: 0) < 500) return // до журналу — не частіше двох разів на секунду
        lastLogged[s.kind] = now
        val limit = thresholds.value[s.kind]
        val exceeded = limit != null && s.scalar > limit
        rows.update { (listOf(LogRow(s.kind, s.scalar, now, exceeded)) + it).take(300) }
        if (exceeded) alarms.update { (listOf("${s.kind.title}: ${"%.1f".format(s.scalar)} ${s.kind.unit} > ${"%.1f".format(limit)}") + it).distinct().take(5) }
    }
}

// ============================ ДОМАШНЄ ============================
/** Метеодані з сенсорів телефону та їх вплив на потужність турбіни. */
data class AirState(
    val pressureHpa: Float?,       // null — сенсора немає, використовується стандарт 1013,25
    val temperatureC: Float?,
    val humidity: Float?,
    val density: Double,
    val powerKw: Double,
    val standardPowerKw: Double,
    val icing: Boolean
) {
    val deltaPercent get() = if (standardPowerKw > 0) (powerKw / standardPowerKw - 1) * 100 else 0.0
}

/**
 * Барометр + термометр + гігрометр → густина повітря ρ = p/(R·T) → потужність турбіни
 * P = ½·ρ·A·v³·Cp для заданої швидкості вітру; порівняння зі стандартною атмосферою.
 */
class HomeworkSensorViewModel : MultiSensorViewModel(listOf(SensorKind.PRESSURE, SensorKind.TEMPERATURE, SensorKind.HUMIDITY), historySize = 60) {
    val wind = MutableStateFlow(9f)
    private val spec = TurbineSpec()

    val air: StateFlow<AirState> = combine(latest, wind) { l, v ->
        val p = l[SensorKind.PRESSURE]?.scalar
        val t = l[SensorKind.TEMPERATURE]?.scalar
        val h = l[SensorKind.HUMIDITY]?.scalar
        val rho = SensorMath.airDensity(p ?: 1013.25f, t ?: 15f)
        // потужність з фактичною густиною: P ∝ ρ, тож перераховуємо від стандартної 1,225 кг/м³
        val stdPower = WindPowerCalculator.turbinePowerKw(v.toDouble(), spec)
        val theoretical = 0.5 * rho * WindPowerCalculator.sweptArea(spec.rotorRadius) * Math.pow(v.toDouble(), 3.0) * spec.powerCoefficient / 1000
        val power = if (stdPower == 0.0) 0.0 else minOf(theoretical, spec.ratedPowerKw)
        AirState(p, t, h, rho, power, stdPower, SensorMath.icingRisk(t ?: 15f, h))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AirState(null, null, null, 1.225, 0.0, 0.0, false))
}
