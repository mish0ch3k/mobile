package ua.kpi.windfarm4.s13

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import ua.kpi.windfarm4.flows.HotSensorHub
import ua.kpi.windfarm4.flows.PowerSample
import ua.kpi.windfarm4.flows.SensorReading
import ua.kpi.windfarm4.flows.StationSnapshot
import ua.kpi.windfarm4.flows.WindFlows
import ua.kpi.windfarm4.flows.WindFlows.retrySensor
import ua.kpi.windfarm4.flows.WindFlows.toPower
import ua.kpi.windfarm4.flows.WindFlows.validOnly
import ua.kpi.windfarm4.flows.ticker

// ============================ БАЗОВИЙ ============================
/**
 * Холодний потік датчика збирається в корутині viewModelScope; останні значення
 * зберігаються у StateFlow, на який підписаний UI.
 */
class BasicFlowViewModel : ViewModel() {
    val latest = MutableStateFlow<SensorReading?>(null)
    val history = MutableStateFlow<List<SensorReading>>(emptyList())
    val running = MutableStateFlow(false)
    private var job: Job? = null

    fun toggle() = if (running.value) stop() else start()

    private fun start() {
        running.value = true
        job = viewModelScope.launch {
            // collect — термінальний оператор: саме він запускає генерацію холодного потоку
            WindFlows.anemometer("A-10", base = 8.0, periodMs = 1000).collect { r ->
                latest.value = r
                history.update { (listOf(r) + it).take(12) }
            }
        }
    }

    private fun stop() { job?.cancel(); running.value = false }
}

// ============================ СЕРЕДНІЙ ============================
/** Подія журналу: вимір, відкинуте значення або помилка. */
data class FlowEvent(val text: String, val isError: Boolean = false)

/**
 * Трансформації потоку (onEach → filter → map), обробка помилок (catch, onCompletion)
 * і debounce для порогу тривоги, який користувач змінює слайдером.
 */
@OptIn(FlowPreview::class)
class MediumFlowViewModel : ViewModel() {
    val sample = MutableStateFlow<PowerSample?>(null)
    val events = MutableStateFlow<List<FlowEvent>>(emptyList())
    val rejected = MutableStateFlow(0)
    val running = MutableStateFlow(false)
    val threshold = MutableStateFlow(12f)          // змінюється слайдером миттєво
    private var job: Job? = null

    /** Поріг застосовується лише через 400 мс після останньої зміни слайдера. */
    val appliedThreshold: StateFlow<Float> = threshold.debounce(400)
        .stateIn(viewModelScope, SharingStarted.Eagerly, threshold.value)

    /** Тривога — похідна від вимірів і застосованого порогу. */
    val alarm: StateFlow<Boolean> = combine(sample, appliedThreshold) { s, t -> s != null && s.reading.speed >= t }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun log(e: FlowEvent) = events.update { (listOf(e) + it).take(14) }

    fun toggle() {
        if (running.value) { job?.cancel(); running.value = false; return }
        running.value = true
        job = WindFlows.anemometer("A-100", base = 11.0, periodMs = 800, failEvery = 25, glitchProbability = 0.15)
            .onStart { log(FlowEvent("Підключення до датчика A-100…")) }
            .onEach { if (it.speed !in 0.0..60.0) { rejected.update { n -> n + 1 }; log(FlowEvent("Відкинуто хибне значення ${it.speed} м/с", true)) } }
            .validOnly()                                   // filter
            .toPower()                                     // map
            .onEach { s -> sample.value = s; log(FlowEvent("№${s.reading.seq}: ${s.reading.speed} м/с → ${"%.0f".format(s.powerKw)} кВт")) }
            .catch { e -> log(FlowEvent("Помилка потоку: ${e.message}", true)) } // збій датчика не «валить» застосунок
            .onCompletion { running.value = false }
            .launchIn(viewModelScope)
    }
}

// ============================ ПРОСУНУТИЙ ============================
/** Порівняння двох анемометрів, отримане через zip (вимір до виміру). */
data class PairedReading(val seq: Int, val a: Double, val b: Double) { val diff get() = kotlin.math.abs(a - b) }

/** Порівняння датчика з прогнозною моделлю (combine — найсвіжіші значення обох). */
data class ModelCheck(val sensor: Double, val forecast: Double) {
    val errorPercent get() = if (forecast > 0) (sensor - forecast) / forecast * 100 else 0.0
}

/**
 * Кілька джерел: два анемометри (zip), датчик + прогноз (combine), автоматичний повтор
 * після збою (retryWhen) і «гарячий» потік (SharedFlow), до якого можна під'єднати
 * кілька підписників — усі бачать ті самі виміри.
 */
class AdvancedFlowViewModel : ViewModel() {
    private val sensorA = WindFlows.anemometer("A", base = 9.0, periodMs = 1000, seed = 1, failEvery = 15)
    private val hub = HotSensorHub(viewModelScope, sensorA.retrySensor(maxRetries = 3) { attempt, cause ->
        retries.update { it + 1 }
        log("Збій: ${cause.message}. Повторний збір, спроба $attempt")
    }.catch { log("Датчик A відключено після 3 невдалих спроб") }, replay = 5)

    val subscribers: StateFlow<Int> = hub.subscribers
    val running = MutableStateFlow(false)
    val retries = MutableStateFlow(0)
    val log = MutableStateFlow<List<String>>(emptyList())
    val paired = MutableStateFlow<List<PairedReading>>(emptyList())
    val check = MutableStateFlow<ModelCheck?>(null)
    val panelA = MutableStateFlow<SensorReading?>(null)
    val panelB = MutableStateFlow<SensorReading?>(null)
    private val jobs = mutableListOf<Job>()
    private var panelBJob: Job? = null

    private fun log(s: String) = log.update { (listOf(s) + it).take(10) }

    fun toggle() {
        if (running.value) { hub.stop(); jobs.forEach { it.cancel() }; jobs.clear(); panelBJob?.cancel(); panelBJob = null; running.value = false; return }
        running.value = true
        hub.start()
        // Підписник 1: панель A
        jobs += hub.readings.onEach { panelA.value = it }.launchIn(viewModelScope)
        // zip: кожен вимір гарячого датчика A в парі з виміром холодного датчика B
        val sensorB = WindFlows.anemometer("B", base = 9.0, periodMs = 1000, seed = 2)
        jobs += hub.readings.zip(sensorB) { a, b -> PairedReading(a.seq, a.speed, b.speed) }
            .onEach { p -> paired.update { (listOf(p) + it).take(6) } }
            .launchIn(viewModelScope)
        // combine: останній вимір датчика та останній прогноз моделі (модель оновлюється рідше)
        jobs += combine(hub.readings, WindFlows.forecastModel(periodMs = 3000)) { r, f -> ModelCheck(r.speed, f) }
            .onEach { check.value = it }
            .launchIn(viewModelScope)
    }

    /** Ще один підписник того самого гарячого потоку — бачить ті самі значення, що й панель A. */
    fun toggleSecondSubscriber() {
        panelBJob?.let { it.cancel(); panelBJob = null; panelB.value = null; return }
        panelBJob = hub.readings.onEach { panelB.value = it }.launchIn(viewModelScope)
    }

    val secondSubscriberActive: Boolean get() = panelBJob != null
}

// ============================ ДОМАШНЄ ============================
/** Стан турбіни на моніторі станції. */
data class TurbineView(val id: String, val reading: SensorReading?, val stale: Boolean)

/**
 * Моніторинг станції з трьох турбін: merge трьох потоків → накопичення знімка (scan-логіка
 * через StateFlow) → тікер перевіряє відсутність даних. Турбіна T3 періодично «зникає»,
 * щоб продемонструвати обробку відсутності даних, T2 — дає збій і відновлюється (retryWhen).
 */
class HomeworkFlowViewModel : ViewModel() {
    private val ids = listOf("T1", "T2", "T3")
    private val staleMs = 3500L
    val snapshot = MutableStateFlow(StationSnapshot())
    val now = MutableStateFlow(System.currentTimeMillis())
    val errors = MutableStateFlow<List<String>>(emptyList())
    val running = MutableStateFlow(false)
    private var job: Job? = null
    private var tickJob: Job? = null

    /** Похідний стан: турбіни з ознакою «немає даних» та сумарна потужність лише за свіжими даними. */
    val turbines: StateFlow<List<TurbineView>> = combine(snapshot, now) { s, t ->
        val stale = s.stale(t, staleMs, ids)
        ids.map { TurbineView(it, s.latest[it], it in stale) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ids.map { TurbineView(it, null, true) })

    val stationPowerKw: StateFlow<Double> = combine(snapshot, now) { s, t -> s.totalPowerKw(exclude = s.stale(t, staleMs, ids)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    private fun err(s: String) = errors.update { (listOf(s) + it).take(6) }

    fun toggle() {
        if (running.value) { job?.cancel(); tickJob?.cancel(); running.value = false; return }
        running.value = true
        snapshot.value = StationSnapshot()
        val t1 = WindFlows.anemometer("T1", base = 10.0, periodMs = 900, seed = 31)
        val t2 = WindFlows.anemometer("T2", base = 12.0, periodMs = 1100, seed = 32, failEvery = 12)
            .retrySensor(maxRetries = 5) { a, c -> err("${c.message} → повтор $a") }
        // T3 «замовкає» на 6 с кожні ~12 вимірів — імітація втрати зв'язку
        val t3 = WindFlows.anemometer("T3", base = 7.0, periodMs = 1000, seed = 33)
            .onEach { if (it.seq % 12 == 0) kotlinx.coroutines.delay(6000) }
        job = merge(t1, t2, t3)
            .validOnly()
            .onEach { r -> snapshot.update { it.withReading(r) } }
            .catch { e -> err("Потік зупинено: ${e.message}") }
            .launchIn(viewModelScope)
        tickJob = ticker(500).onEach { now.value = it }.launchIn(viewModelScope)
    }
}

/** Допоміжне для фільтрації порожніх значень у UI. */
internal fun List<SensorReading>.speeds() = map { it.speed }.filter { it >= 0 }
