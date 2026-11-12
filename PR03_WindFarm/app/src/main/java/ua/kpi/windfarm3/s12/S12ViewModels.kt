package ua.kpi.windfarm3.s12

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windfarm3.AppGraph
import ua.kpi.windfarm3.data.remote.ApiClient
import ua.kpi.windfarm3.data.remote.ConnectivityInterceptor
import ua.kpi.windfarm3.data.remote.FaultInjectionInterceptor
import ua.kpi.windfarm3.data.remote.FaultScenario
import ua.kpi.windfarm3.data.remote.OpenMeteoApi
import ua.kpi.windfarm3.data.remote.OpenMeteoMapper
import ua.kpi.windfarm3.data.remote.WindForecast
import ua.kpi.windfarm3.data.remote.bodyOrThrow
import ua.kpi.windfarm3.domain.DemoStations
import ua.kpi.windfarm3.domain.Station
import ua.kpi.windfarm3.domain.StationValidator
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorHandler
import ua.kpi.windfarm3.errors.LogEntry
import ua.kpi.windfarm3.errors.MemoryErrorLog
import ua.kpi.windfarm3.errors.RetryPolicy

/** Стан запиту для екранів заняття 12. */
sealed interface NetState {
    data object Idle : NetState
    data class Loading(val text: String) : NetState
    data class Success(val forecast: WindForecast, val note: String? = null) : NetState
    data class Failed(val error: AppError) : NetState
}

/** Потік «є мережа / немає мережі» для індикатора у верхній частині екрана. */
private fun ViewModel.onlineFlow(): StateFlow<Boolean> =
    AppGraph.network.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppGraph.network.isOnline())

// ============================ БАЗОВИЙ ============================
/** Перевірка введення і наявності мережі ДО запиту; зрозуміле повідомлення замість збою. */
class BasicErrorsViewModel : ViewModel() {
    data class Preset(val title: String, val lat: String, val lon: String)
    val presets = listOf(
        Preset("Приазовська", "46.70", "35.25"),
        Preset("Тилігул", "46.93", "31.27"),
        Preset("Некоректна широта", "123.4", "35.25"),
        Preset("Порожньо", "", "")
    )
    val online = onlineFlow()
    val lat = MutableStateFlow(presets[0].lat)
    val lon = MutableStateFlow(presets[0].lon)
    val state = MutableStateFlow<NetState>(NetState.Idle)
    val inputError = MutableStateFlow<String?>(null)

    fun apply(p: Preset) { lat.value = p.lat; lon.value = p.lon; inputError.value = null }

    fun load() {
        // 1) Некоректне введення — запит не надсилається
        val err = StationValidator.latitude(lat.value) ?: StationValidator.longitude(lon.value)
        inputError.value = err
        if (err != null) { state.value = NetState.Idle; return }
        // 2) Немає мережі — повідомлення без спроби з'єднання
        if (!AppGraph.network.isOnline()) { state.value = NetState.Failed(AppError.NoInternet); return }
        state.value = NetState.Loading("Запит прогнозу…")
        viewModelScope.launch {
            state.value = try {
                NetState.Success(AppGraph.windRemote.forecast(StationValidator.parse(lat.value)!!, StationValidator.parse(lon.value)!!))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                NetState.Failed(ua.kpi.windfarm3.errors.ErrorMapper.map(e))
            }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
/** Сценарії запитів, що провокують різні помилки (частина — справжні відповіді сервера). */
enum class Scenario(val title: String) {
    OK("Коректний запит"),
    BAD_REQUEST("400: широта 999"),
    NOT_FOUND("404: неіснуючий шлях"),
    TIMEOUT("Тайм-аут"),
    NO_DNS("Сервер не знайдено")
}

/**
 * Усі помилки проходять через ErrorHandler: журнал (у пам'яті) + Logcat + подія для UI.
 * Критичні (немає мережі) — діалог, інші — Snackbar.
 */
class MediumErrorsViewModel : ViewModel() {
    private val faults = FaultInjectionInterceptor()
    private val api: OpenMeteoApi = ApiClient.create(OpenMeteoApi.BASE_URL, ConnectivityInterceptor(AppGraph.network), faults)
    val handler = ErrorHandler(MemoryErrorLog())
    val online = onlineFlow()
    val state = MutableStateFlow<NetState>(NetState.Idle)
    val log = MutableStateFlow<List<LogEntry>>(emptyList())
    val dialog = MutableStateFlow<AppError?>(null)

    fun run(s: Scenario) {
        faults.scenario = when (s) {
            Scenario.TIMEOUT -> FaultScenario.TIMEOUT
            Scenario.NO_DNS -> FaultScenario.UNKNOWN_HOST
            else -> FaultScenario.NONE
        }
        state.value = NetState.Loading("Виконується: ${s.title}")
        viewModelScope.launch {
            try {
                val dto = when (s) {
                    Scenario.BAD_REQUEST -> api.forecast(999.0, 35.25)          // сервер відповідає 400
                    Scenario.NOT_FOUND -> api.missing(46.70, 35.25)            // сервер відповідає 404
                    else -> api.forecast(46.70, 35.25)
                }.bodyOrThrow()
                state.value = NetState.Success(OpenMeteoMapper.toForecast(dto), "Запит виконано успішно")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val err = handler.handle(s.title, e)
                state.value = NetState.Failed(err)
                if (err == AppError.NoInternet) dialog.value = err
            } finally {
                log.value = handler.entries()
            }
        }
    }

    fun clearLog() { handler.clear(); log.value = emptyList() }
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Повторні спроби для тимчасових помилок (408, 429, 5xx, тайм-аут, мережа) з експоненційною
 * затримкою, окремі повідомлення для кожного HTTP-коду та журнал у файлі.
 */
class AdvancedErrorsViewModel : ViewModel() {
    private val faults = FaultInjectionInterceptor()
    private val api: OpenMeteoApi = ApiClient.create(OpenMeteoApi.BASE_URL, ConnectivityInterceptor(AppGraph.network), faults, timeoutSec = 5)
    val handler = ErrorHandler(AppGraph.errorLog)
    val online = onlineFlow()
    val scenario = MutableStateFlow(FaultScenario.FLAKY_503)
    val state = MutableStateFlow<NetState>(NetState.Idle)
    val attempts = MutableStateFlow<List<String>>(emptyList())
    val log = MutableStateFlow(handler.entries())
    val logPath = AppGraph.errorLog.path

    fun run() {
        faults.scenario = scenario.value
        attempts.value = emptyList()
        state.value = NetState.Loading("Спроба 1…")
        val policy = RetryPolicy(maxAttempts = 4)
        viewModelScope.launch {
            try {
                val f = policy.run(onRetry = { attempt, err, wait ->
                    handler.retrying(scenario.value.title, attempt, err, wait)
                    attempts.update { it + "Спроба $attempt: ${err.title} — ${err.userMessage} Повтор через ${wait} мс" }
                    state.value = NetState.Loading("Спроба ${attempt + 1} з ${policy.maxAttempts}…")
                }) { OpenMeteoMapper.toForecast(api.forecast(46.70, 35.25).bodyOrThrow()) }
                val n = attempts.value.size + 1
                attempts.update { it + "Спроба $n: успіх (HTTP 200)" }
                state.value = NetState.Success(f, if (n > 1) "Успіх після $n спроб" else "Успіх з першої спроби")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val err = handler.handle(scenario.value.title, e)
                attempts.update { it + "Остаточна помилка: ${err.title}" + if (err.retryable) " (вичерпано ${policy.maxAttempts} спроби)" else " (неповторювана)" }
                state.value = NetState.Failed(err)
            } finally {
                log.value = handler.entries()
            }
        }
    }

    fun clearLog() { handler.clear(); log.value = emptyList() }
}

// ============================ ДОМАШНЄ ============================
/** Повний сценарій для станції: мережа → запит із повторами → Snackbar / діалог → журнал у файлі. */
class HomeworkErrorsViewModel : ViewModel() {
    private val faults = FaultInjectionInterceptor()
    private val api: OpenMeteoApi = ApiClient.create(OpenMeteoApi.BASE_URL, ConnectivityInterceptor(AppGraph.network), faults, timeoutSec = 5)
    val handler = ErrorHandler(AppGraph.errorLog)
    val online = onlineFlow()
    val stations = DemoStations.all
    val selected = MutableStateFlow<Station>(stations.first())
    val fault = MutableStateFlow(FaultScenario.NONE)
    val state = MutableStateFlow<NetState>(NetState.Idle)
    val dialog = MutableStateFlow<AppError?>(null)
    val logSize = MutableStateFlow(handler.entries().size)
    val faultOptions = listOf(FaultScenario.NONE, FaultScenario.FLAKY_503, FaultScenario.HTTP_500, FaultScenario.HTTP_403, FaultScenario.TIMEOUT)

    fun load() {
        val s = selected.value
        if (!AppGraph.network.isOnline()) {
            val err = handler.handle(s.name, ua.kpi.windfarm3.errors.NoConnectionException())
            state.value = NetState.Failed(err); dialog.value = err; logSize.value = handler.entries().size
            return
        }
        faults.scenario = fault.value
        state.value = NetState.Loading("Завантаження прогнозу для ${s.name}…")
        viewModelScope.launch {
            var tries = 1
            try {
                val f = RetryPolicy(maxAttempts = 3).run(onRetry = { a, e, w ->
                    tries = a + 1
                    handler.retrying(s.name, a, e, w)
                    state.value = NetState.Loading("${e.title}. Повтор $tries з 3…")
                }) { OpenMeteoMapper.toForecast(api.forecast(s.latitude, s.longitude).bodyOrThrow()) }
                state.value = NetState.Success(f, if (tries > 1) "Дані отримано з $tries-ї спроби" else null)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state.value = NetState.Failed(handler.handle(s.name, e))
            } finally { logSize.value = handler.entries().size }
        }
    }
}
