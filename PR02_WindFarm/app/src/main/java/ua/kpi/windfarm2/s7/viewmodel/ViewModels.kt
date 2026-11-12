package ua.kpi.windfarm2.s7.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm2.domain.TurbineSpec
import ua.kpi.windfarm2.domain.TurbineStatus
import ua.kpi.windfarm2.domain.WindPowerCalculator
import kotlin.random.Random

/*
 * MVVM: View (composable) лише відображає StateFlow і передає події у ViewModel;
 * ViewModel зберігає стан (переживає поворот екрана) і викликає бізнес-логіку (domain).
 */

// ========================= БАЗОВИЙ =========================
class BasicWindViewModel : ViewModel() {
    private val _windSpeed = MutableStateFlow(0.0)
    val windSpeed: StateFlow<Double> = _windSpeed.asStateFlow()

    private val _updates = MutableStateFlow(0)
    val updates: StateFlow<Int> = _updates.asStateFlow()

    /** Подія з UI: отримати новий вимір датчика. */
    fun refresh() {
        _windSpeed.value = (Random.nextDouble(0.0, 28.0) * 10).toInt() / 10.0
        _updates.update { it + 1 }
    }
}

// ========================= СЕРЕДНІЙ =========================
/** Кілька StateFlow, валідація і похідні величини (combine). */
class MediumWindViewModel : ViewModel() {
    val speedText = MutableStateFlow("8")
    val turbines = MutableStateFlow(10)

    /** Помилка введення — похідна від speedText. */
    val speedError: StateFlow<String?> = speedText.map { validateSpeed(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Результат розрахунку — похідний від двох потоків. */
    val result: StateFlow<FarmResult?> = combine(speedText, turbines) { text, n ->
        val v = text.replace(',', '.').toDoubleOrNull()
        if (v == null || validateSpeed(text) != null) null else calculate(v, n)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onSpeedChange(text: String) { speedText.value = text }
    fun onTurbinesChange(delta: Int) { turbines.update { (it + delta).coerceIn(1, 100) } }

    companion object {
        fun validateSpeed(text: String): String? {
            val v = text.replace(',', '.').toDoubleOrNull() ?: return "Введіть число"
            return if (v < 0 || v > 60) "Допустимо 0…60 м/с" else null
        }

        fun calculate(v: Double, n: Int): FarmResult {
            val spec = TurbineSpec()
            val p = WindPowerCalculator.turbinePowerKw(v, spec)
            return FarmResult(p, p * n / 1000.0, WindPowerCalculator.status(v, spec), WindPowerCalculator.beaufort(v))
        }
    }
}

data class FarmResult(val turbineKw: Double, val farmMw: Double, val status: TurbineStatus, val beaufort: Int)

// ========================= ПРОСУНУТИЙ =========================
/** Стан екрана: завантаження / успіх / помилка. */
sealed interface UiState {
    data object Loading : UiState
    data class Success(val station: StationSnapshot) : UiState
    data class Error(val message: String) : UiState
}

data class StationSnapshot(val speeds: List<Double>, val totalMw: Double, val avgSpeed: Double, val stopped: Int)

/** Джерело даних (імітація телеметрії): затримка мережі та можливі збої. */
class TelemetrySource(private val failureRate: Double = 0.25) {
    suspend fun fetchSpeeds(count: Int): List<Double> {
        delay(1200)
        if (Random.nextDouble() < failureRate) throw IllegalStateException("Немає зв'язку з телеметрією станції")
        return List(count) { (Random.nextDouble(2.0, 27.0) * 10).toInt() / 10.0 }
    }
}

class AdvancedWindViewModel(private val source: TelemetrySource = TelemetrySource()) : ViewModel() {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    /** Асинхронне завантаження у viewModelScope — скасовується разом із ViewModel. */
    fun load() {
        job?.cancel()
        _state.value = UiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                UiState.Success(summarize(source.fetchSpeeds(8)))
            } catch (e: IllegalStateException) {
                UiState.Error(e.message ?: "Невідома помилка")
            }
        }
    }

    companion object {
        /** Бізнес-логіка агрегації даних станції. */
        fun summarize(speeds: List<Double>): StationSnapshot {
            val spec = TurbineSpec()
            return StationSnapshot(
                speeds = speeds,
                totalMw = speeds.sumOf { WindPowerCalculator.turbinePowerKw(it, spec) } / 1000.0,
                avgSpeed = speeds.average(),
                stopped = speeds.count { WindPowerCalculator.turbinePowerKw(it, spec) == 0.0 }
            )
        }
    }
}

// ========================= ДОМАШНЄ =========================
/** Безперервний моніторинг: корутина оновлює вимір кожні 2 с, зберігається історія. */
class MonitoringViewModel : ViewModel() {
    private val _history = MutableStateFlow<List<Double>>(emptyList())
    val history: StateFlow<List<Double>> = _history.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private var job: Job? = null

    fun toggle() = if (_running.value) stop() else start()

    private fun start() {
        _running.value = true
        job = viewModelScope.launch {
            var v = 8.0
            while (isActive) {
                // випадкове блукання швидкості вітру (поривчастість)
                v = (v + Random.nextDouble(-2.5, 2.5)).coerceIn(0.0, 30.0)
                _history.update { (it + ((v * 10).toInt() / 10.0)).takeLast(20) }
                delay(2000)
            }
        }
    }

    private fun stop() { job?.cancel(); _running.value = false }
}
