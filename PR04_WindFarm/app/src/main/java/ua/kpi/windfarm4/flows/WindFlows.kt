package ua.kpi.windfarm4.flows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm4.domain.TurbineSpec
import ua.kpi.windfarm4.domain.TurbineStatus
import ua.kpi.windfarm4.domain.WindPowerCalculator
import kotlin.random.Random

/** Один вимір анемометра. */
data class SensorReading(val sensorId: String, val seq: Int, val time: Long, val speed: Double, val directionDeg: Double)

/** Вимір після трансформації: потужність і стан турбіни. */
data class PowerSample(val reading: SensorReading, val powerKw: Double, val beaufort: Int, val status: TurbineStatus)

/** Збій датчика (обрив зв'язку). */
class SensorException(message: String) : Exception(message)

/**
 * Джерела даних у вигляді Flow.
 * «Холодний» потік (flow { }) починає генерацію лише під час collect і для кожного
 * збирача окремо — як запит до датчика, який виконується «на вимогу».
 */
object WindFlows {

    /**
     * Анемометр: випадкове блукання швидкості навколо [base] з кроком [periodMs].
     * [failEvery] — кожен n-й вимір кидає SensorException; [glitchProbability] — частка
     * хибних значень (−1 або 99 м/с), які мають відсіятися фільтром.
     */
    fun anemometer(
        id: String,
        base: Double = 8.0,
        periodMs: Long = 1000,
        seed: Int = id.hashCode(),
        failEvery: Int? = null,
        glitchProbability: Double = 0.0,
        clock: () -> Long = System::currentTimeMillis
    ): Flow<SensorReading> = flow {
        val rnd = Random(seed)
        var v = base
        var dir = rnd.nextDouble(0.0, 360.0)
        var n = 0
        while (true) {
            n++
            if (failEvery != null && n % failEvery == 0) throw SensorException("Датчик $id не відповідає (вимір №$n)")
            v = (v + rnd.nextDouble(-1.3, 1.3) + (base - v) * 0.1).coerceIn(0.0, 32.0)
            dir = (dir + rnd.nextDouble(-8.0, 8.0) + 360) % 360
            val speed = if (rnd.nextDouble() < glitchProbability) (if (rnd.nextBoolean()) -1.0 else 99.0) else round1(v)
            emit(SensorReading(id, n, clock(), speed, round1(dir)))
            delay(periodMs)
        }
    }

    /** Прогнозна модель: оновлюється рідше, ніж датчик, і дає «очікувану» швидкість вітру. */
    fun forecastModel(base: Double = 9.0, periodMs: Long = 3000, seed: Int = 7): Flow<Double> = flow {
        val rnd = Random(seed)
        var hour = 0
        while (true) {
            // добовий хід вітру: вдень сильніше, вночі слабше
            emit(round1(base + 2.5 * kotlin.math.sin(hour / 24.0 * 2 * Math.PI) + rnd.nextDouble(-0.5, 0.5)))
            hour++
            delay(periodMs)
        }
    }

    /** Фільтр фізично можливих значень (відкидає збої датчика). */
    fun Flow<SensorReading>.validOnly(max: Double = 60.0): Flow<SensorReading> = filter { it.speed in 0.0..max }

    /** Перетворення швидкості вітру на потужність і стан турбіни. */
    fun Flow<SensorReading>.toPower(spec: TurbineSpec = TurbineSpec()): Flow<PowerSample> = map {
        PowerSample(it, WindPowerCalculator.turbinePowerKw(it.speed, spec), WindPowerCalculator.beaufort(it.speed), WindPowerCalculator.status(it.speed, spec))
    }

    /**
     * Автоматичний повторний збір після збою датчика з наростаючою затримкою.
     * Після [maxRetries] невдач помилка передається далі — її обробить catch.
     */
    fun <T> Flow<T>.retrySensor(maxRetries: Long = 3, baseDelayMs: Long = 500, onRetry: suspend (attempt: Long, cause: Throwable) -> Unit = { _, _ -> }): Flow<T> =
        retryWhen { cause, attempt ->
            if (cause is SensorException && attempt < maxRetries) {
                onRetry(attempt + 1, cause)
                delay(baseDelayMs * (attempt + 1))
                true
            } else false
        }
}

/**
 * «Гарячий» потік: генерація йде в окремій корутині незалежно від того, чи є підписники.
 * Нові підписники отримують останні [replay] значень і далі — ті самі виміри, що й інші
 * (на відміну від холодного потоку, де кожен збирач запускає власний датчик).
 */
class HotSensorHub(
    private val scope: CoroutineScope,
    private val source: Flow<SensorReading>,
    replay: Int = 1
) {
    private val _readings = MutableSharedFlow<SensorReading>(replay = replay, extraBufferCapacity = 16)
    val readings: SharedFlow<SensorReading> = _readings.asSharedFlow()
    val subscribers: StateFlow<Int> = _readings.subscriptionCount
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun start() {
        if (running) return
        job = scope.launch { source.collect { _readings.emit(it) } }
    }

    fun stop() { job?.cancel(); job = null }
}

/** Знімок станції: останній вимір кожної турбіни та час його отримання. */
data class StationSnapshot(val latest: Map<String, SensorReading> = emptyMap()) {
    fun withReading(r: SensorReading) = copy(latest = latest + (r.sensorId to r))

    /** Турбіни, від яких немає даних довше [staleMs] — «відсутність даних». */
    fun stale(now: Long, staleMs: Long, all: List<String>): List<String> =
        all.filter { id -> latest[id]?.let { now - it.time > staleMs } ?: true }

    fun totalPowerKw(spec: TurbineSpec = TurbineSpec(), exclude: Collection<String> = emptyList()): Double =
        latest.filterKeys { it !in exclude }.values.sumOf { WindPowerCalculator.turbinePowerKw(it.speed, spec) }
}

internal fun round1(v: Double) = Math.round(v * 10) / 10.0

/** Нескінченний «тікер» — для перевірки відсутності даних раз на [periodMs]. */
fun ticker(periodMs: Long, clock: () -> Long = System::currentTimeMillis): Flow<Long> = flow {
    while (currentCoroutineContextActive()) { emit(clock()); delay(periodMs) }
}

private suspend fun currentCoroutineContextActive(): Boolean = kotlin.coroutines.coroutineContext.isActive
