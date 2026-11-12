package ua.kpi.windfarm2.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.kpi.windfarm2.domain.TurbineSpec
import ua.kpi.windfarm2.domain.WindPowerCalculator
import kotlin.random.Random

// ========================= БАЗОВИЙ =========================
/** Repository з одним локальним джерелом (Room): вставка та отримання. */
class BasicWindRepository(private val dao: WindDao) {
    val measurements: Flow<List<WindMeasurement>> = dao.observeAll()
    suspend fun add(m: WindMeasurement) = dao.insert(m)
}

// ========================= СЕРЕДНІЙ =========================
/** «Віддалене» джерело — імітація API метеостанції. */
class RemoteWindSource(var online: Boolean = true) {
    suspend fun fetchLatest(): List<WindMeasurement> {
        delay(800)
        if (!online) throw IllegalStateException("Метеосервер недоступний")
        return List(3) { i ->
            WindMeasurement(turbine = "ВТ-0${i + 1}", windSpeed = (Random.nextDouble(2.0, 20.0) * 10).toInt() / 10.0,
                temperature = (Random.nextDouble(-5.0, 25.0) * 10).toInt() / 10.0, source = "remote")
        }
    }
}

/**
 * Об'єднує локальне і віддалене джерела.
 * Стратегія: спершу пробуємо сервер і кешуємо результат у Room; якщо сервер недоступний —
 * працюємо з локальними даними (UI все одно читає лише з Room — single source of truth).
 */
class MediumWindRepository(private val dao: WindDao, private val remote: RemoteWindSource) {
    fun observe(query: String): Flow<List<WindMeasurement>> = if (query.isBlank()) dao.observeAll() else dao.search(query)
    suspend fun add(m: WindMeasurement) = dao.insert(m)
    suspend fun update(m: WindMeasurement) = dao.update(m)
    suspend fun delete(m: WindMeasurement) = dao.delete(m)

    /** Повертає, з якого джерела взято дані. */
    suspend fun sync(): String = try {
        dao.insertAll(remote.fetchLatest())
        "Дані отримано з сервера та збережено локально"
    } catch (e: IllegalStateException) {
        "${e.message}. Показано локальні дані (${dao.count()} записів)"
    }
}

// ========================= ПРОСУНУТИЙ =========================
enum class SortOrder(val title: String) { NEWEST("Новіші"), SPEED_DESC("Вітер ↓"), SPEED_ASC("Вітер ↑"), TURBINE("Турбіна") }

data class StationStats(val count: Int, val avgSpeed: Double, val maxSpeed: Double, val avgPowerKw: Double, val storms: Int)

/** Результат операції репозиторію з обробкою помилок. */
sealed interface RepoResult {
    data class Ok(val message: String) : RepoResult
    data class Fail(val message: String) : RepoResult
}

/**
 * Агрегує джерела, кешує віддалені дані з часом життя кешу (TTL),
 * обробляє помилки та виконує бізнес-логіку: статистика, потужність, фільтр, сортування.
 */
class AdvancedWindRepository(
    private val dao: WindDao,
    private val remote: RemoteWindSource,
    private val cacheTtlMs: Long = 30_000
) {
    private var lastSync = 0L

    fun observe(query: String, minSpeed: Double, order: SortOrder): Flow<List<WindMeasurement>> =
        (if (query.isBlank()) dao.observeAll() else dao.search(query)).map { list -> filterAndSort(list, minSpeed, order) }

    fun observeStats(): Flow<StationStats> = dao.observeAll().map(::stats)

    suspend fun refresh(force: Boolean): RepoResult {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < cacheTtlMs) return RepoResult.Ok("Кеш актуальний (${(now - lastSync) / 1000} с тому)")
        return try {
            dao.insertAll(remote.fetchLatest())
            lastSync = now
            RepoResult.Ok("Синхронізовано з сервером")
        } catch (e: IllegalStateException) {
            RepoResult.Fail("${e.message} — використано кеш")
        }
    }

    suspend fun add(m: WindMeasurement) = dao.insert(m)
    suspend fun delete(m: WindMeasurement) = dao.delete(m)
    suspend fun clear() = dao.clear()

    companion object {
        fun filterAndSort(list: List<WindMeasurement>, minSpeed: Double, order: SortOrder): List<WindMeasurement> {
            val f = list.filter { it.windSpeed >= minSpeed }
            return when (order) {
                SortOrder.NEWEST -> f.sortedByDescending { it.timestamp }
                SortOrder.SPEED_DESC -> f.sortedByDescending { it.windSpeed }
                SortOrder.SPEED_ASC -> f.sortedBy { it.windSpeed }
                SortOrder.TURBINE -> f.sortedBy { it.turbine }
            }
        }

        fun stats(list: List<WindMeasurement>): StationStats {
            if (list.isEmpty()) return StationStats(0, 0.0, 0.0, 0.0, 0)
            val spec = TurbineSpec()
            return StationStats(
                count = list.size,
                avgSpeed = list.map { it.windSpeed }.average(),
                maxSpeed = list.maxOf { it.windSpeed },
                avgPowerKw = list.map { WindPowerCalculator.turbinePowerKw(it.windSpeed, spec, it.temperature) }.average(),
                storms = list.count { it.windSpeed >= spec.cutOutSpeed }
            )
        }
    }
}
