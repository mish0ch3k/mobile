package ua.kpi.windfarm.rgr.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.kpi.windfarm.rgr.data.AlertRepository
import ua.kpi.windfarm.rgr.data.ForecastRepository
import ua.kpi.windfarm.rgr.data.MeasurementRepository
import ua.kpi.windfarm.rgr.data.RefreshResult
import ua.kpi.windfarm.rgr.data.StationRepository
import ua.kpi.windfarm.rgr.data.local.AlertEntity
import ua.kpi.windfarm.rgr.data.local.MeasurementEntity
import ua.kpi.windfarm.rgr.data.local.StationDayStat
import ua.kpi.windfarm.rgr.data.local.StationEntity
import ua.kpi.windfarm.rgr.data.spec
import ua.kpi.windfarm.rgr.domain.AlertEvent
import ua.kpi.windfarm.rgr.domain.AlertRules
import ua.kpi.windfarm.rgr.domain.AlertThresholds
import ua.kpi.windfarm.rgr.domain.EnergyForecast
import ua.kpi.windfarm.rgr.domain.ForecastCalculator
import ua.kpi.windfarm.rgr.domain.ForecastHour
import ua.kpi.windfarm.rgr.domain.TurbineSpec
import ua.kpi.windfarm.rgr.domain.TurbineState
import ua.kpi.windfarm.rgr.domain.WindPhysics
import ua.kpi.windfarm.rgr.domain.WindStatistics

private const val STOP_TIMEOUT = 5_000L

/** Вибрана станція — спільна для всіх екранів (зберігається, поки живе процес). */
object Selection { val stationId = MutableStateFlow(1L) }

// ============================ ЗАВДАННЯ 1: моніторинг ============================

/** Стан дашборда станції. */
data class DashboardState(
    val station: StationEntity? = null,
    val latest: MeasurementEntity? = null,
    val history: List<Double> = emptyList(),      // вітер, найстаріші — першими
    val powerHistory: List<Double> = emptyList(),
    val state: TurbineState = TurbineState.IDLE,
    val capacityMw: Double = 0.0,
    val loadPercent: Double = 0.0,
    val beaufort: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    stations: StationRepository,
    private val measurements: MeasurementRepository,
    private val boost: MutableStateFlow<Double>? = null
) : ViewModel() {
    val stationList: StateFlow<List<StationEntity>> = stations.stations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val selected = Selection.stationId

    /** Зміна станції перемикає підписку на її виміри (flatMapLatest скасовує попередню). */
    val state: StateFlow<DashboardState> = combine(stationList, selected) { l, id -> l.firstOrNull { it.id == id } }
        .filterNotNull()
        .flatMapLatest { s -> measurements.latest(s.id, 60).map { rows -> build(s, rows) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), DashboardState())

    val weatherBoost: StateFlow<Double> = boost ?: MutableStateFlow(0.0)

    fun select(id: Long) { selected.value = id }
    fun setBoost(v: Double) { boost?.value = v }

    companion object {
        fun build(s: StationEntity, rowsDesc: List<MeasurementEntity>): DashboardState {
            val last = rowsDesc.firstOrNull()
            val spec = s.spec()
            val capacity = s.ratedKw * s.turbines / 1000
            return DashboardState(
                station = s, latest = last,
                history = rowsDesc.asReversed().map { it.windMs },
                powerHistory = rowsDesc.asReversed().map { it.powerMw },
                state = last?.let { WindPhysics.state(it.windMs, spec, WindPhysics.airDensity(it.temperatureC, it.pressureHpa)) } ?: TurbineState.IDLE,
                capacityMw = capacity,
                loadPercent = if (capacity > 0 && last != null) last.powerMw / capacity * 100 else 0.0,
                beaufort = last?.let { WindPhysics.beaufort(it.windMs) } ?: 0
            )
        }
    }
}

// ============================ ЗАВДАННЯ 2: калькулятор ============================

data class CalcInput(
    val windMs: Double = 9.0, val measuredAtM: Double = 100.0, val temperatureC: Double = 15.0, val pressureHpa: Double = 1013.25,
    val rotorRadiusM: Double = 45.0, val cp: Double = 0.42, val ratedKw: Double = 2000.0, val turbines: Int = 50
)

data class CalcResult(
    val hubWindMs: Double, val density: Double, val turbineKw: Double, val stationMw: Double, val state: TurbineState,
    val betzKw: Double, val dailyMwh: Double, val curve: List<Double>, val error: String? = null
)

class CalculatorViewModel : ViewModel() {
    val input = MutableStateFlow(CalcInput())
    val result: StateFlow<CalcResult> = input.map { calculate(it) }.stateIn(viewModelScope, SharingStarted.Eagerly, calculate(CalcInput()))

    fun update(f: (CalcInput) -> CalcInput) { input.value = f(input.value) }

    companion object {
        /** Розрахунок для введених параметрів; некоректна комбінація (напр. Cp > межі Бетца) — повідомлення, а не падіння. */
        fun calculate(i: CalcInput): CalcResult = try {
            val spec = TurbineSpec(ratedPowerKw = i.ratedKw, rotorRadiusM = i.rotorRadiusM, powerCoefficient = i.cp)
            val rho = WindPhysics.airDensity(i.temperatureC, i.pressureHpa)
            val v = WindPhysics.windAtHeight(i.windMs, i.measuredAtM, spec.hubHeightM)
            val kw = WindPhysics.powerKw(v, spec, rho)
            // теоретична межа: та сама турбіна з ідеальним Cp = 16/27 (без обмеження номіналом)
            val betz = 0.5 * rho * spec.sweptAreaM2 * v * v * v * WindPhysics.BETZ_LIMIT / 1000
            CalcResult(v, rho, kw, kw * i.turbines / 1000, WindPhysics.state(v, spec, rho), betz, kw * i.turbines / 1000 * 24,
                (0..30).map { WindPhysics.powerKw(it.toDouble(), spec, rho) })
        } catch (e: IllegalArgumentException) {
            CalcResult(0.0, 0.0, 0.0, 0.0, TurbineState.IDLE, 0.0, 0.0, emptyList(), e.message)
        }
    }
}

// ============================ ЗАВДАННЯ 3: прогноз ============================

data class ForecastUi(
    val station: StationEntity? = null,
    val energy: EnergyForecast? = null,
    val events: List<AlertEvent> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val fetchedAt: Long? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ForecastViewModel(
    private val stations: StationRepository,
    private val forecast: ForecastRepository,
    private val thresholds: StateFlow<AlertThresholds>
) : ViewModel() {
    private val status = MutableStateFlow(ForecastUi())
    val stationList = stations.stations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    private val data: Flow<Triple<StationEntity, List<ForecastHour>, EnergyForecast?>> =
        combine(stationList, Selection.stationId) { l, id -> l.firstOrNull { it.id == id } }
            .filterNotNull()
            .flatMapLatest { s ->
                forecast.observe(s.id).map { h -> Triple(s, h, if (h.isEmpty()) null else ForecastCalculator.energy(h, s.turbines, s.spec(), s.availability)) }
            }

    val ui: StateFlow<ForecastUi> = combine(data, status, thresholds) { (s, hours, e), st, t ->
        st.copy(station = s, energy = e, events = AlertRules.checkForecast(hours, s.spec(), t))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ForecastUi())

    init { refresh(force = false) }

    fun select(id: Long) { Selection.stationId.value = id; refresh(false) }

    /** Оновлення з мережі; без мережі показується кешований прогноз і пояснення. */
    fun refresh(force: Boolean = true) = viewModelScope.launch {
        val s = stations.get(Selection.stationId.value) ?: return@launch
        status.value = status.value.copy(loading = true, message = null)
        val r = forecast.refresh(s, force)
        val fetched = forecast.lastFetch(s.id)
        status.value = when (r) {
            is RefreshResult.Updated -> ForecastUi(message = "Прогноз оновлено: ${r.hours} год", fetchedAt = fetched)
            RefreshResult.Fresh -> ForecastUi(message = "Прогноз актуальний (оновлюється щогодини)", fetchedAt = fetched)
            is RefreshResult.Failed -> ForecastUi(message = r.error.message + if (r.hasCache) " — показано збережений прогноз" else "", isError = true, fetchedAt = fetched)
        }
    }
}

// ============================ ЗАВДАННЯ 4: журнал і аналітика ============================

enum class Period(val title: String, val days: Int) { DAY("Доба", 1), THREE("3 доби", 3), WEEK("Тиждень", 7) }

data class AnalyticsUi(
    val station: StationEntity? = null,
    val daily: List<StationDayStat> = emptyList(),
    val measurements: List<MeasurementEntity> = emptyList(),
    val summary: WindStatistics.Summary = WindStatistics.Summary(0, 0.0, 0.0, 0.0),
    val histogram: IntArray = IntArray(15),
    val weibull: Pair<Double, Double>? = null,
    val annualMwh: Double? = null,
    val energyMwh: Double = 0.0,
    val capacityFactor: Double = 0.0
)

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModel(stations: StationRepository, private val measurements: MeasurementRepository, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    val period = MutableStateFlow(Period.WEEK)
    val minWind = MutableStateFlow(0.0)
    val stationList = stations.stations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val ui: StateFlow<AnalyticsUi> = combine(stationList, Selection.stationId, period, minWind) { l, id, p, mw -> Triple(l.firstOrNull { it.id == id }, p, mw) }
        .flatMapLatest { (s, p, mw) ->
            if (s == null) kotlinx.coroutines.flow.flowOf(AnalyticsUi())
            else {
                val from = clock() - p.days * 24 * 3600_000L
                combine(measurements.daily(s.id, from), measurements.filtered(s.id, mw, from)) { daily, rows -> analyze(s, daily, rows, p.days) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AnalyticsUi())

    fun select(id: Long) { Selection.stationId.value = id }

    companion object {
        fun analyze(s: StationEntity, daily: List<StationDayStat>, rows: List<MeasurementEntity>, days: Int): AnalyticsUi {
            val winds = rows.map { it.windMs }
            val weibull = WindStatistics.weibull(winds)
            val energy = daily.sumOf { it.energyMwh }
            return AnalyticsUi(
                station = s, daily = daily, measurements = rows.take(200),
                summary = WindStatistics.summary(winds), histogram = WindStatistics.histogram(winds),
                weibull = weibull,
                annualMwh = weibull?.let { (k, c) -> WindStatistics.annualEnergyMwh(k, c, s.spec()) * s.turbines * s.availability },
                energyMwh = energy,
                capacityFactor = WindPhysics.capacityFactor(energy * 1000, s.ratedKw * s.turbines, days * 24.0)
            )
        }
    }
}

// ============================ ЗАВДАННЯ 5: тривоги ============================

class AlertsViewModel(private val repo: AlertRepository, val thresholds: MutableStateFlow<AlertThresholds>, stations: StationRepository) : ViewModel() {
    val alerts: StateFlow<List<AlertEntity>> = repo.alerts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val activeCount: StateFlow<Int> = repo.activeCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)
    val stationNames: StateFlow<Map<Long, String>> = stations.stations.map { l -> l.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

    fun acknowledge(id: Long) = viewModelScope.launch { repo.acknowledge(id) }
    fun acknowledgeAll() = viewModelScope.launch { repo.acknowledgeAll() }
    fun clear() = viewModelScope.launch { repo.clearAcknowledged() }
    fun setThresholds(f: (AlertThresholds) -> AlertThresholds) { thresholds.value = f(thresholds.value) }
}

// ============================ ДЕТАЛІ СТАНЦІЇ ============================

class StationViewModel(private val repo: StationRepository, val id: Long) : ViewModel() {
    val station: StateFlow<StationEntity?> = repo.stations.map { l -> l.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)

    /** Зміна параметрів станції з перевіркою меж. */
    fun save(turbines: Int, availability: Double) = viewModelScope.launch {
        val s = station.value ?: return@launch
        repo.update(s.copy(turbines = turbines.coerceIn(1, 500), availability = availability.coerceIn(0.0, 1.0)))
    }
}
