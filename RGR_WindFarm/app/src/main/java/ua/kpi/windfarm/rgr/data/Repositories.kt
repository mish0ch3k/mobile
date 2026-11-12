package ua.kpi.windfarm.rgr.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.kpi.windfarm.rgr.data.local.AlertDao
import ua.kpi.windfarm.rgr.data.local.AlertEntity
import ua.kpi.windfarm.rgr.data.local.ForecastDao
import ua.kpi.windfarm.rgr.data.local.ForecastEntity
import ua.kpi.windfarm.rgr.data.local.MeasurementDao
import ua.kpi.windfarm.rgr.data.local.MeasurementEntity
import ua.kpi.windfarm.rgr.data.local.StationDao
import ua.kpi.windfarm.rgr.data.local.StationDayStat
import ua.kpi.windfarm.rgr.data.local.StationEntity
import ua.kpi.windfarm.rgr.data.remote.ForecastResponse
import ua.kpi.windfarm.rgr.data.remote.NetworkError
import ua.kpi.windfarm.rgr.data.remote.OpenMeteoApi
import ua.kpi.windfarm.rgr.data.remote.toNetworkError
import ua.kpi.windfarm.rgr.domain.AlertEvent
import ua.kpi.windfarm.rgr.domain.ForecastHour
import ua.kpi.windfarm.rgr.domain.TurbineSpec

fun StationEntity.spec() = TurbineSpec(ratedPowerKw = ratedKw, rotorRadiusM = rotorRadiusM, powerCoefficient = cp)

// ============================ СТАНЦІЇ ============================
class StationRepository(private val dao: StationDao) {
    val stations: Flow<List<StationEntity>> = dao.observeAll()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun update(s: StationEntity) = dao.update(s)
}

// ============================ ВИМІРИ ============================
class MeasurementRepository(private val dao: MeasurementDao) {
    fun latest(stationId: Long, limit: Int = 60) = dao.observeLatest(stationId, limit)
    fun since(stationId: Long, from: Long) = dao.observeSince(stationId, from)
    fun filtered(stationId: Long, minWind: Double, from: Long) = dao.observeFiltered(stationId, minWind, from)
    fun daily(stationId: Long, from: Long): Flow<List<StationDayStat>> = dao.observeDaily(stationId, from)
    suspend fun add(m: MeasurementEntity) = dao.insert(m)
    suspend fun addAll(m: List<MeasurementEntity>) = dao.insertAll(m)
    suspend fun count() = dao.count()
    /**
     * Обмеження розміру БД: детальна телеметрія зберігається 2 доби, уся історія — 30 діб.
     * Повертає кількість видалених записів.
     */
    suspend fun cleanup(now: Long = System.currentTimeMillis()): Int =
        dao.deleteTelemetryOlderThan(now - 2L * 24 * 3600 * 1000) + dao.deleteOlderThan(now - 30L * 24 * 3600 * 1000)
}

// ============================ ПРОГНОЗ ============================

/** Результат оновлення прогнозу. */
sealed interface RefreshResult {
    data class Updated(val hours: Int) : RefreshResult
    data object Fresh : RefreshResult                       // кеш ще дійсний — запит не потрібен
    data class Failed(val error: NetworkError, val hasCache: Boolean) : RefreshResult
}

/**
 * Прогноз: Room — єдине джерело для UI (offline-first), мережа лише оновлює кеш.
 * Кеш вважається свіжим [ttlMs] (1 год); без мережі UI показує збережений прогноз.
 */
class ForecastRepository(
    private val api: OpenMeteoApi,
    private val dao: ForecastDao,
    private val ttlMs: Long = 60 * 60 * 1000L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun observe(stationId: Long): Flow<List<ForecastHour>> =
        dao.observe(stationId, clock() - 3600_000).map { rows -> rows.map { ForecastHour(it.timeMs, it.windMs, it.temperatureC, it.pressureHpa, it.gustMs) } }

    suspend fun lastFetch(stationId: Long): Long? = dao.lastFetch(stationId)

    suspend fun refresh(station: StationEntity, force: Boolean = false): RefreshResult {
        val last = dao.lastFetch(station.id)
        if (!force && last != null && clock() - last < ttlMs) return RefreshResult.Fresh
        return try {
            val rows = parse(station.id, api.forecast(station.latitude, station.longitude), clock())
            dao.replace(station.id, rows)
            RefreshResult.Updated(rows.size)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            RefreshResult.Failed(e.toNetworkError(), hasCache = last != null)
        }
    }

    companion object {
        /**
         * Перетворення відповіді API на рядки кешу з перевіркою:
         * масиви однакової довжини, швидкість 0…75 м/с; пропуски (null) — пропускаються.
         */
        fun parse(stationId: Long, r: ForecastResponse, now: Long): List<ForecastEntity> {
            val h = r.hourly ?: throw NetworkError.BadData("немає блоку hourly")
            if (h.time.isEmpty()) throw NetworkError.BadData("порожній прогноз")
            if (h.wind100m.size != h.time.size || h.temperature.size != h.time.size) throw NetworkError.BadData("різна довжина масивів")
            return h.time.indices.mapNotNull { i ->
                val w = h.wind100m[i] ?: return@mapNotNull null
                if (w < 0 || w > 75) return@mapNotNull null
                ForecastEntity(stationId, h.time[i] * 1000, w, h.gusts?.getOrNull(i) ?: w * 1.4,
                    h.temperature[i] ?: 15.0, h.pressure?.getOrNull(i) ?: 1013.25, now)
            }.also { if (it.isEmpty()) throw NetworkError.BadData("усі значення порожні") }
        }
    }
}

// ============================ ТРИВОГИ ============================
class AlertRepository(private val dao: AlertDao) {
    val alerts: Flow<List<AlertEntity>> = dao.observeAll()
    val activeCount: Flow<Int> = dao.observeActiveCount()

    /**
     * Збереження події без дублікатів: та сама тривога для станції не частіше ніж раз на [dedupMs].
     * Повертає збережену подію або null, якщо це повтор.
     */
    suspend fun raise(stationId: Long, e: AlertEvent, dedupMs: Long = 30 * 60 * 1000L, now: Long = System.currentTimeMillis()): AlertEntity? {
        if (dao.findRecent(stationId, e.type.name, now - dedupMs) != null) return null
        val a = AlertEntity(stationId = stationId, type = e.type.name, message = e.message, value = e.value, timestamp = now)
        return a.copy(id = dao.insert(a))
    }

    suspend fun acknowledge(id: Long) = dao.acknowledge(id)
    suspend fun acknowledgeAll() = dao.acknowledgeAll()
    suspend fun clearAcknowledged() = dao.deleteAcknowledged()
}
