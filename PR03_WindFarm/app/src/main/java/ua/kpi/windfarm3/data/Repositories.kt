package ua.kpi.windfarm3.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import ua.kpi.windfarm3.data.remote.WindForecast
import ua.kpi.windfarm3.domain.DemoStations
import ua.kpi.windfarm3.domain.EnergyForecast
import ua.kpi.windfarm3.domain.ForecastAnalytics
import ua.kpi.windfarm3.domain.HourlyWind
import ua.kpi.windfarm3.domain.Station
import ua.kpi.windfarm3.domain.TurbineSpec
import ua.kpi.windfarm3.domain.TurbineStatus
import ua.kpi.windfarm3.domain.WindPowerCalculator
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorMapper
import ua.kpi.windfarm3.errors.RetryPolicy

// ============================ БАЗОВИЙ РІВЕНЬ ============================
/** Repository з одним локальним джерелом: вставка й отримання станцій. */
class BasicStationRepository(private val local: StationLocalDataSource) {
    val stations: Flow<List<Station>> = local.observe()
    suspend fun add(s: Station): Long = local.insert(s)
}

// ============================ СЕРЕДНІЙ РІВЕНЬ ============================
/** Звідки взято дані про вітер — показується користувачу. */
enum class DataOrigin(val title: String) { NETWORK("мережа"), CACHE("кеш"), STALE_CACHE("застарілий кеш") }

data class WindResult(val forecast: WindForecast, val origin: DataOrigin, val fetchedAt: Long)

/**
 * Об'єднує локальне (Room) і віддалене (REST) джерела.
 * Стратегія вибору джерела для вітру:
 *   1) свіжий кеш (молодший за [freshMs]) — без запиту до мережі;
 *   2) інакше — мережа з оновленням кешу;
 *   3) мережа недоступна — застарілий кеш, якщо він є; інакше помилка.
 */
class StationRepository(
    private val local: StationLocalDataSource,
    private val remote: WindRemoteDataSource,
    private val cache: ForecastCacheDataSource,
    private val freshMs: Long = 10 * 60_000L
) {
    fun stations(query: String): Flow<List<Station>> = local.observe(query)

    suspend fun add(s: Station) = local.insert(s)
    suspend fun update(s: Station) = local.update(s)
    suspend fun delete(s: Station) = local.delete(s)

    /** Початкове наповнення демо-станціями, якщо таблиця порожня. */
    suspend fun seedIfEmpty(): Boolean {
        if (local.count() > 0) return false
        local.insertAll(DemoStations.all)
        return true
    }

    /** [retry] — повторні спроби мережевого запиту до переходу на застарілий кеш. */
    suspend fun wind(s: Station, forceNetwork: Boolean = false, retry: RetryPolicy? = null): WindResult {
        val cached = cache.get(s.latitude, s.longitude)
        if (!forceNetwork && cached != null && cached.ageMs() < freshMs)
            return WindResult(cached.forecast, DataOrigin.CACHE, cached.fetchedAt)
        return try {
            val fresh = if (retry != null) retry.run { remote.forecast(s.latitude, s.longitude) }
                        else remote.forecast(s.latitude, s.longitude)
            val now = System.currentTimeMillis()
            cache.put(s.latitude, s.longitude, fresh, now)
            WindResult(fresh, DataOrigin.NETWORK, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            cached?.let { WindResult(it.forecast, DataOrigin.STALE_CACHE, it.fetchedAt) } ?: throw e
        }
    }
}

// ============================ ПРОСУНУТИЙ РІВЕНЬ ============================
/** Поточний стан станції, обчислений з прогнозу (бізнес-логіка репозиторію). */
data class StationLive(
    val station: Station,
    val hubSpeed: Double,              // вітер на висоті маточини (100 м) у поточну годину
    val surfaceSpeed: Double,          // вітер на 10 м з блоку current
    val powerMw: Double,
    val status: TurbineStatus,
    val capacityFactor: Double,        // %
    val next24h: EnergyForecast,
    val origin: DataOrigin,
    val fetchedAt: Long
)

/** Результат по станції: або дані, або помилка (одна станція не «валить» увесь список). */
sealed interface StationState {
    val station: Station
    data class Ok(val live: StationLive) : StationState { override val station get() = live.station }
    data class Failed(override val station: Station, val error: AppError) : StationState
}

enum class FleetFilter(val title: String) { ALL("Усі"), GENERATING("Генерують"), IDLE("Штиль"), STORM("Буря"), ERROR("Помилка") }
enum class FleetSort(val title: String) { POWER("Потужність"), WIND("Вітер"), ENERGY("Прогноз 24 год"), NAME("Назва") }

data class FleetSummary(
    val stations: Int,
    val online: Int,
    val installedMw: Double,
    val currentMw: Double,
    val energy24hMwh: Double,
    val avgCapacityFactor: Double,
    val storms: Int
)

/**
 * Агрегує кілька джерел для всього парку станцій:
 *  - паралельні запити до API (async/awaitAll) — час оновлення ≈ найповільнішій станції;
 *  - повторні спроби при тимчасових збоях (RetryPolicy);
 *  - кеш у Room для офлайн-доступу (через StationRepository);
 *  - бізнес-логіка: потужність, КВВП, прогноз виробітку на 24 год, підсумок парку.
 */
class FleetRepository(
    private val stations: StationRepository,
    private val local: StationLocalDataSource,
    private val retry: RetryPolicy = RetryPolicy()
) {
    private val spec = TurbineSpec()

    suspend fun refresh(force: Boolean): List<StationState> = coroutineScope {
        stations.seedIfEmpty()
        local.all().map { s ->
            async {
                try {
                    val r = stations.wind(s, forceNetwork = force, retry = retry)
                    StationState.Ok(toLive(s, r))
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    StationState.Failed(s, ErrorMapper.map(e))
                }
            }
        }.awaitAll()
    }

    fun toLive(s: Station, r: WindResult): StationLive {
        val hourly = r.forecast.hourly
        val nowHour = r.forecast.current.time.take(13) // "2026-11-11T14"
        val idx = hourly.indexOfFirst { it.time.startsWith(nowHour) }.let { if (it < 0) 0 else it }
        val hub = hourly.getOrNull(idx)?.speed100 ?: r.forecast.current.speed10
        val temp = r.forecast.current.temperature.coerceIn(-50.0, 50.0)
        val powerMw = WindPowerCalculator.turbinePowerKw(hub, spec, temp) * s.turbines / 1000.0
        val next = hourly.drop(idx).take(24)
        return StationLive(
            station = s,
            hubSpeed = hub,
            surfaceSpeed = r.forecast.current.speed10,
            powerMw = powerMw,
            status = WindPowerCalculator.status(hub, spec),
            capacityFactor = powerMw / s.installedMw * 100.0,
            next24h = ForecastAnalytics.forecast(next, s.turbines, spec),
            origin = r.origin,
            fetchedAt = r.fetchedAt
        )
    }

    companion object {
        fun filterAndSort(items: List<StationState>, filter: FleetFilter, sort: FleetSort): List<StationState> {
            val f = items.filter { st ->
                when (filter) {
                    FleetFilter.ALL -> true
                    FleetFilter.ERROR -> st is StationState.Failed
                    FleetFilter.GENERATING -> st is StationState.Ok && st.live.powerMw > 0
                    FleetFilter.IDLE -> st is StationState.Ok && st.live.status == TurbineStatus.IDLE
                    FleetFilter.STORM -> st is StationState.Ok && st.live.status == TurbineStatus.STORM_STOP
                }
            }
            // Станції з помилкою — завжди в кінці списку
            val key: (StationState) -> Double = { st ->
                if (st !is StationState.Ok) Double.NEGATIVE_INFINITY else when (sort) {
                    FleetSort.POWER -> st.live.powerMw
                    FleetSort.WIND -> st.live.hubSpeed
                    FleetSort.ENERGY -> st.live.next24h.energyMwh
                    FleetSort.NAME -> 0.0
                }
            }
            return if (sort == FleetSort.NAME) f.sortedBy { it.station.name } else f.sortedByDescending(key)
        }

        fun summary(items: List<StationState>): FleetSummary {
            val ok = items.filterIsInstance<StationState.Ok>().map { it.live }
            return FleetSummary(
                stations = items.size,
                online = ok.size,
                installedMw = items.sumOf { it.station.installedMw },
                currentMw = ok.sumOf { it.powerMw },
                energy24hMwh = ok.sumOf { it.next24h.energyMwh },
                avgCapacityFactor = if (ok.isEmpty()) 0.0 else ok.map { it.capacityFactor }.average(),
                storms = ok.count { it.status == TurbineStatus.STORM_STOP }
            )
        }
    }
}

/** Години прогнозу, що починаються з поточної (для таблиць і графіків). */
fun WindForecast.upcoming(hours: Int): List<HourlyWind> {
    val nowHour = current.time.take(13)
    val idx = hourly.indexOfFirst { it.time.startsWith(nowHour) }.let { if (it < 0) 0 else it }
    return hourly.drop(idx).take(hours)
}
