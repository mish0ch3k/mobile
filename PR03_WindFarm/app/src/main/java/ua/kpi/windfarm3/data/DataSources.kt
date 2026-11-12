package ua.kpi.windfarm3.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ua.kpi.windfarm3.data.local.ForecastCacheDao
import ua.kpi.windfarm3.data.local.ForecastCacheEntity
import ua.kpi.windfarm3.data.local.StationDao
import ua.kpi.windfarm3.data.local.StationEntity
import ua.kpi.windfarm3.data.remote.ApiClient
import ua.kpi.windfarm3.data.remote.GeoPlace
import ua.kpi.windfarm3.data.remote.GeocodingApi
import ua.kpi.windfarm3.data.remote.OpenMeteoApi
import ua.kpi.windfarm3.data.remote.OpenMeteoMapper
import ua.kpi.windfarm3.data.remote.WindForecast
import ua.kpi.windfarm3.data.remote.bodyOrThrow
import ua.kpi.windfarm3.domain.Station
import java.util.Locale

/*
 * Data Source — найнижчий рівень доступу до даних. Кожне джерело знає лише «своє» сховище
 * (Room або HTTP) і повертає доменні моделі. Repository поєднує джерела й вирішує,
 * звідки брати дані; ViewModel працює тільки з Repository.
 */

/** Локальне джерело: станції у Room. */
class StationLocalDataSource(private val dao: StationDao) {
    fun observe(query: String = ""): Flow<List<Station>> =
        (if (query.isBlank()) dao.observeAll() else dao.search(query.trim())).map { list -> list.map { it.toDomain() } }

    suspend fun all(): List<Station> = dao.getAll().map { it.toDomain() }
    suspend fun count(): Int = dao.count()
    suspend fun insert(s: Station): Long = dao.insert(StationEntity.from(s))
    suspend fun insertAll(items: List<Station>) = dao.insertAll(items.map(StationEntity::from))
    suspend fun update(s: Station) = dao.update(StationEntity.from(s))
    suspend fun delete(s: Station) = dao.delete(StationEntity.from(s))
}

/** Віддалене джерело: прогноз вітру з REST API Open-Meteo. */
class WindRemoteDataSource(private val api: OpenMeteoApi) {
    suspend fun forecast(lat: Double, lon: Double): WindForecast =
        OpenMeteoMapper.toForecast(api.forecast(lat, lon).bodyOrThrow())
}

/** Віддалене джерело: геокодування (пошук населеного пункту). */
class GeoRemoteDataSource(private val api: GeocodingApi) {
    suspend fun search(name: String): List<GeoPlace> = OpenMeteoMapper.toPlaces(api.search(name).bodyOrThrow())
}

/** Кеш прогнозів у Room: зберігається JSON доменної моделі разом із часом отримання. */
class ForecastCacheDataSource(private val dao: ForecastCacheDao) {
    data class Cached(val forecast: WindForecast, val fetchedAt: Long) {
        fun ageMs(now: Long = System.currentTimeMillis()) = now - fetchedAt
    }

    suspend fun get(lat: Double, lon: Double): Cached? = dao.get(key(lat, lon))?.let { e ->
        runCatching { Cached(ApiClient.gson.fromJson(e.json, WindForecast::class.java), e.fetchedAt) }.getOrNull()
    }

    suspend fun put(lat: Double, lon: Double, f: WindForecast, now: Long = System.currentTimeMillis()) =
        dao.put(ForecastCacheEntity(key(lat, lon), ApiClient.gson.toJson(f), now))

    suspend fun clear() = dao.clear()
    suspend fun count() = dao.count()

    /** Координати округлюються до 0,01° (~1 км) — сусідні запити потрапляють в один запис. */
    private fun key(lat: Double, lon: Double) = String.format(Locale.US, "%.2f,%.2f", lat, lon)
}
