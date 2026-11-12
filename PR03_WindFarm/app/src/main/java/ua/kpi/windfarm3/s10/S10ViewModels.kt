package ua.kpi.windfarm3.s10

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windfarm3.AppGraph
import ua.kpi.windfarm3.data.DataOrigin
import ua.kpi.windfarm3.data.StationRepository
import ua.kpi.windfarm3.data.WindResult
import ua.kpi.windfarm3.data.remote.GeoPlace
import ua.kpi.windfarm3.data.remote.WindForecast
import ua.kpi.windfarm3.data.upcoming
import ua.kpi.windfarm3.domain.DemoStations
import ua.kpi.windfarm3.domain.EnergyForecast
import ua.kpi.windfarm3.domain.ForecastAnalytics
import ua.kpi.windfarm3.domain.HourlyWind
import ua.kpi.windfarm3.domain.Station
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorMapper
import ua.kpi.windfarm3.errors.RetryPolicy

/** Загальний стан екрана із запитом до REST API. */
sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Success<T>(val data: T) : Load<T>
    data class Error(val error: AppError) : Load<Nothing>
}

/** Виконує блок і загортає результат у Load (помилки — через централізований ErrorMapper). */
suspend fun <T> loadOf(block: suspend () -> T): Load<T> = try {
    Load.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Error(ErrorMapper.map(e))
}

// ============================ БАЗОВИЙ ============================
/** Один REST-ресурс: поточний вітер у точці розташування станції. */
class BasicRestViewModel : ViewModel() {
    val station: Station = DemoStations.all.first()
    val state = MutableStateFlow<Load<WindForecast>>(Load.Idle)

    fun load() {
        state.value = Load.Loading
        viewModelScope.launch { state.value = loadOf { AppGraph.windRemote.forecast(station.latitude, station.longitude) } }
    }
}

// ============================ СЕРЕДНІЙ ============================
/**
 * Два REST-ресурси: геокодування (масив results) і прогноз (вкладені current / hourly).
 * Відповіді кешуються в пам'яті на [ttlMs], тож повторний вибір того самого міста не йде в мережу.
 */
class MediumRestViewModel(private val ttlMs: Long = 10 * 60_000L) : ViewModel() {
    private data class Entry<T>(val value: T, val time: Long)
    private val placeCache = HashMap<String, Entry<List<GeoPlace>>>()
    private val forecastCache = HashMap<String, Entry<WindForecast>>()

    val query = MutableStateFlow("")
    val places = MutableStateFlow<Load<List<GeoPlace>>>(Load.Idle)
    val selected = MutableStateFlow<GeoPlace?>(null)
    val forecast = MutableStateFlow<Load<WindForecast>>(Load.Idle)
    val fromCache = MutableStateFlow(false)
    val productiveOnly = MutableStateFlow(false)
    val sort = MutableStateFlow(ForecastAnalytics.Sort.TIME)
    private var searchJob: Job? = null

    /** Години для таблиці — похідні від прогнозу, фільтра та сортування. */
    val hours: StateFlow<List<HourlyWind>> = combine(forecast, productiveOnly, sort) { f, only, s ->
        val list = (f as? Load.Success)?.data?.upcoming(48).orEmpty()
        ForecastAnalytics.sort(if (only) ForecastAnalytics.productive(list) else list, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun search(name: String = query.value) {
        val q = name.trim()
        query.value = q
        if (q.length < 2) { places.value = Load.Error(AppError.InvalidData("введіть щонайменше 2 літери")); return }
        val key = q.lowercase()
        placeCache[key]?.takeIf { System.currentTimeMillis() - it.time < ttlMs }?.let {
            places.value = Load.Success(it.value); fromCache.value = true; return
        }
        searchJob?.cancel()
        places.value = Load.Loading
        searchJob = viewModelScope.launch {
            val r = loadOf { AppGraph.geoRemote.search(q) }
            if (r is Load.Success) placeCache[key] = Entry(r.data, System.currentTimeMillis())
            fromCache.value = false
            places.value = r
        }
    }

    fun select(p: GeoPlace) {
        selected.value = p
        val key = "%.2f,%.2f".format(java.util.Locale.US, p.latitude, p.longitude)
        forecastCache[key]?.takeIf { System.currentTimeMillis() - it.time < ttlMs }?.let {
            forecast.value = Load.Success(it.value); fromCache.value = true; return
        }
        forecast.value = Load.Loading
        viewModelScope.launch {
            val r = loadOf { AppGraph.windRemote.forecast(p.latitude, p.longitude) }
            if (r is Load.Success) forecastCache[key] = Entry(r.data, System.currentTimeMillis())
            fromCache.value = false
            forecast.value = r
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
/** Картка станції з прогнозом і показниками. */
data class StationForecastCard(
    val station: Station,
    val state: Load<WindResult>,
    val attempt: Int = 1,
    val next24: List<HourlyWind> = emptyList(),
    val energy24: EnergyForecast? = null,
    val energy48Mwh: Double? = null,
    val maintenance: List<HourlyWind>? = null
)

/**
 * Асинхронно завантажує прогноз для всіх станцій одночасно. Для кожної станції:
 * повторні спроби з експоненційною затримкою → за невдачі — кеш Room (офлайн-режим).
 * Бізнес-логіка: виробіток на 24 і 48 год, найкраще вікно для техобслуговування.
 */
class AdvancedRestViewModel(private val repo: StationRepository = AppGraph.stationRepository) : ViewModel() {
    val cards = MutableStateFlow<List<StationForecastCard>>(emptyList())
    val loading = MutableStateFlow(false)

    fun refresh(force: Boolean) {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            try {
                repo.seedIfEmpty()
                val stations = AppGraph.stationLocal.all()
                cards.value = stations.map { StationForecastCard(it, Load.Loading) }
                stations.map { s -> async { loadStation(s, force) } }.awaitAll()
            } finally {
                loading.value = false
            }
        }
    }

    private suspend fun loadStation(s: Station, force: Boolean) {
        // Номер поточної спроби показується на картці станції
        val retry = RetryPolicy(maxAttempts = 3, listener = { attempt, _, _ -> updateCard(s.id) { it.copy(attempt = attempt + 1) } })
        val r = loadOf { repo.wind(s, forceNetwork = force, retry = retry) }
        updateCard(s.id) { c ->
            if (r is Load.Success) {
                val next = r.data.forecast.upcoming(48)
                c.copy(
                    state = r,
                    next24 = next.take(24),
                    energy24 = ForecastAnalytics.forecast(next.take(24), s.turbines),
                    energy48Mwh = ForecastAnalytics.forecast(next, s.turbines).energyMwh,
                    maintenance = ForecastAnalytics.maintenanceWindow(next.take(24))
                )
            } else c.copy(state = r)
        }
    }

    private fun updateCard(id: Long, f: (StationForecastCard) -> StationForecastCard) =
        cards.update { list -> list.map { if (it.station.id == id) f(it) else it } }

    val totalEnergy24: StateFlow<Double> = combine(cards, loading) { c, _ -> c.sumOf { it.energy24?.energyMwh ?: 0.0 } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)
    val offlineCount: StateFlow<Int> = combine(cards, loading) { c, _ ->
        c.count { (it.state as? Load.Success)?.data?.origin == DataOrigin.STALE_CACHE }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // init — після оголошення всіх властивостей, які використовує refresh()
    init { refresh(force = false) }
}

// ============================ ДОМАШНЄ ============================
/** Погодинний прогноз для обраної станції: таблиця з фільтром і сортуванням. */
class HomeworkRestViewModel(private val repo: StationRepository = AppGraph.stationRepository) : ViewModel() {
    val stations = DemoStations.all
    val selected = MutableStateFlow(stations.first())
    val state = MutableStateFlow<Load<WindResult>>(Load.Idle)
    val productiveOnly = MutableStateFlow(false)
    val sort = MutableStateFlow(ForecastAnalytics.Sort.TIME)

    val rows: StateFlow<List<HourlyWind>> = combine(state, productiveOnly, sort) { st, only, s ->
        val list = (st as? Load.Success)?.data?.forecast?.upcoming(24).orEmpty()
        ForecastAnalytics.sort(if (only) ForecastAnalytics.productive(list) else list, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val energy: StateFlow<EnergyForecast?> = combine(state, selected) { st, s ->
        (st as? Load.Success)?.data?.forecast?.upcoming(24)?.let { ForecastAnalytics.forecast(it, s.turbines) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init { load() }

    fun select(s: Station) { selected.value = s; load() }

    fun load(force: Boolean = false) {
        state.value = Load.Loading
        viewModelScope.launch { state.value = loadOf { repo.wind(selected.value, forceNetwork = force) } }
    }
}
