package ua.kpi.windfarm6.s25

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import ua.kpi.windfarm6.domain.TurbineSpec
import ua.kpi.windfarm6.domain.TurbineStatus
import ua.kpi.windfarm6.domain.WindPowerCalculator

// ============================ ДОМАШНЄ: логіка калькулятора ============================

/** Результат розрахунку станції або помилки введення по полях. */
sealed interface CalcResult {
    data class Ok(val turbineKw: Double, val stationMw: Double, val status: TurbineStatus, val dailyMwh: Double) : CalcResult
    data class Invalid(val windError: String?, val countError: String?) : CalcResult
}

/**
 * Калькулятор станції: розбір введених рядків, перевірка меж, розрахунок.
 * Вся логіка — тут, щоб покрити її юніт-тестами; екран лише показує CalcResult.
 */
object StationCalculator {
    const val MAX_TURBINES = 500
    private val spec = TurbineSpec()

    fun parseWind(s: String): Pair<Double?, String?> {
        val v = s.trim().replace(',', '.').toDoubleOrNull() ?: return null to (if (s.isBlank()) "Введіть швидкість вітру" else "Потрібне число")
        return when {
            !v.isFinite() -> null to "Потрібне число"
            v < 0 -> null to "Швидкість не може бути від'ємною"
            v > 60 -> null to "Понад 60 м/с — помилка датчика"
            else -> v to null
        }
    }

    fun parseCount(s: String): Pair<Int?, String?> {
        val n = s.trim().toIntOrNull() ?: return null to (if (s.isBlank()) "Введіть кількість" else "Ціле число")
        return if (n !in 1..MAX_TURBINES) null to "Від 1 до $MAX_TURBINES" else n to null
    }

    fun calculate(wind: String, count: String): CalcResult {
        val (v, we) = parseWind(wind)
        val (n, ce) = parseCount(count)
        if (v == null || n == null) return CalcResult.Invalid(we, ce)
        val kw = WindPowerCalculator.turbinePowerKw(v, spec)
        return CalcResult.Ok(kw, kw * n / 1000, WindPowerCalculator.status(v, spec), kw * n / 1000 * 24)
    }
}

// ============================ СЕРЕДНІЙ: дані через інтерфейс (підміняються в тестах) ============================

data class Station(val id: Int, val name: String, val capacityMw: Double, val windMs: Double)

/** Джерело даних. У застосунку — DemoStationRepository, у UI-тестах — FakeStationRepository. */
interface StationRepository {
    suspend fun login(user: String, password: String): Boolean
    suspend fun stations(): List<Station>
}

class DemoStationRepository : StationRepository {
    override suspend fun login(user: String, password: String): Boolean { delay(400); return user == "operator" && password == "wind2026" }
    override suspend fun stations(): List<Station> { delay(400); return listOf(
        Station(1, "Приазовська", 200.0, 9.4), Station(2, "Тилігульська", 76.0, 8.1),
        Station(3, "Одеса-Офшор", 150.0, 11.2), Station(4, "Ботієвська", 200.0, 7.6)) }
}

/** Підробка для тестів: передбачувані дані без затримок. */
class FakeStationRepository(private val list: List<Station> = listOf(Station(1, "Тестова ВЕС", 10.0, 12.0)), private val ok: Boolean = true) : StationRepository {
    var loginCalls = 0
        private set
    override suspend fun login(user: String, password: String): Boolean { loginCalls++; return ok }
    override suspend fun stations(): List<Station> = list
}

object ServiceLocator { var stations: StationRepository = DemoStationRepository() }

// ============================ ПРОСУНУТИЙ: асинхронне завантаження ============================

sealed interface LoadState {
    data object Idle : LoadState
    data object Loading : LoadState
    data class Success(val stations: List<Station>, val attempts: Int, val ms: Long) : LoadState
    data class Error(val message: String, val attempts: Int) : LoadState
}

/** Мережеве джерело з можливими збоями (для перевірки повторів і тайм-аутів). */
typealias Source = suspend () -> List<Station>

/**
 * Завантаження з повторами (експоненційна пауза) і загальним тайм-аутом.
 * Диспетчер передається ззовні, тож у тестах його замінює тестовий (віртуальний час).
 */
class StationLoader(private val source: Source, private val io: CoroutineDispatcher = Dispatchers.IO,
                    private val retries: Int = 3, private val baseDelayMs: Long = 300, private val timeoutMs: Long = 5_000) {
    suspend fun load(clock: () -> Long = System::currentTimeMillis): LoadState = withContext(io) {
        val t0 = clock()
        var attempts = 0
        try {
            withTimeout<LoadState>(timeoutMs) {
                var lastError: Throwable? = null
                repeat(retries) { i ->
                    attempts = i + 1
                    val result = try { source() } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e; null }
                    if (result != null) {
                        // дані прийшли, але некоректні — повтор не допоможе
                        if (result.any { it.capacityMw <= 0 }) return@withTimeout LoadState.Error("Некоректні дані: потужність ≤ 0", attempts)
                        return@withTimeout LoadState.Success(result, attempts, clock() - t0)
                    }
                    if (i < retries - 1) delay(baseDelayMs shl i) // 300, 600, 1200 мс
                }
                LoadState.Error("Сервер недоступний після $attempts спроб: ${lastError?.message}", attempts)
            }
        } catch (e: TimeoutCancellationException) {
            LoadState.Error("Перевищено час очікування ${timeoutMs / 1000} с", attempts)
        }
    }
}
