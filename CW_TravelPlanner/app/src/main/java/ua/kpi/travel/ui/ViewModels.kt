package ua.kpi.travel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.travel.TravelApp
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityCategory
import ua.kpi.travel.domain.ActivityValidator
import ua.kpi.travel.domain.BudgetCalculator
import ua.kpi.travel.domain.BudgetSummary
import ua.kpi.travel.domain.Conflict
import ua.kpi.travel.domain.DayWeather
import ua.kpi.travel.domain.Errors
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.ExpenseCategory
import ua.kpi.travel.domain.ExpenseValidator
import ua.kpi.travel.domain.ItineraryPlanner
import ua.kpi.travel.domain.PackingCategory
import ua.kpi.travel.domain.PackingItem
import ua.kpi.travel.domain.PackingSuggestion
import ua.kpi.travel.domain.PackingTemplates
import ua.kpi.travel.domain.Place
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripReport
import ua.kpi.travel.domain.TripStatus
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.domain.TripType
import ua.kpi.travel.domain.TripValidator
import ua.kpi.travel.domain.WeatherCodes
import ua.kpi.travel.notify.ReminderScheduler
import java.time.LocalDate

private val Application.container get() = (this as TravelApp).container

/* ---------------------------- Список подорожей ---------------------------- */

enum class TripFilter(val title: String) { ALL("Усі"), PLANNED("Майбутні"), ONGOING("Тривають"), FINISHED("Завершені") }

data class TripsState(val trips: List<Trip> = emptyList(), val filter: TripFilter = TripFilter.ALL, val query: String = "", val loaded: Boolean = false)

class TripsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.container.trips
    private val filter = MutableStateFlow(TripFilter.ALL)
    private val query = MutableStateFlow("")

    val state: StateFlow<TripsState> = combine(repo.observeTrips(), filter, query) { all, f, q ->
        val today = LocalDate.now()
        val shown = TripTimeline.sortForList(all, today).filter { t ->
            (f == TripFilter.ALL || TripTimeline.status(t, today).name == f.name) &&
                (q.isBlank() || t.title.contains(q, true) || t.destination.contains(q, true))
        }
        TripsState(shown, f, q, true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TripsState())

    fun setFilter(f: TripFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
}

/* --------------------------- Створення/редагування --------------------------- */

data class TripForm(
    val id: Long = 0,
    val title: String = "",
    val destination: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val startDay: Long? = LocalDate.now().plusDays(7).toEpochDay(),
    val endDay: Long? = LocalDate.now().plusDays(9).toEpochDay(),
    val budget: String = "",
    val currency: String = "UAH",
    val type: TripType = TripType.CITY,
    val travelers: String = "1",
    val notes: String = "",
    val errors: Errors = emptyMap(),
    val suggestions: List<Place> = emptyList(),
    val searchError: String? = null,
    val savedId: Long? = null
)

class TripEditViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.container.trips
    private val weather = app.container.weather
    private val _form = MutableStateFlow(TripForm())
    val form: StateFlow<TripForm> = _form.asStateFlow()
    private var searchJob: Job? = null

    fun load(id: Long) {
        if (id == 0L || _form.value.id == id) return
        viewModelScope.launch {
            repo.trip(id)?.let { t ->
                _form.value = TripForm(t.id, t.title, t.destination, t.latitude, t.longitude, t.startDay, t.endDay,
                    if (t.budget > 0) t.budget.toString() else "", t.currency, t.type, t.travelers.toString(), t.notes)
            }
        }
    }

    fun update(f: (TripForm) -> TripForm) = _form.update { f(it).copy(errors = emptyMap()) }

    /** Пошук міста з debounce 400 мс: координати потрібні для погоди й мапи. */
    fun onDestination(text: String) {
        _form.update { it.copy(destination = text, latitude = null, longitude = null, errors = it.errors - "destination") }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            weather.search(text)
                .onSuccess { r -> _form.update { it.copy(suggestions = r, searchError = null) } }
                .onFailure { e -> _form.update { it.copy(suggestions = emptyList(), searchError = e.message) } }
        }
    }

    fun choosePlace(p: Place) = _form.update { it.copy(destination = p.label, latitude = p.latitude, longitude = p.longitude, suggestions = emptyList()) }

    fun save() {
        val f = _form.value
        val errors = TripValidator.validate(f.title, f.destination, f.startDay, f.endDay, f.budget, f.travelers)
        if (errors.isNotEmpty()) { _form.update { it.copy(errors = errors) }; return }
        viewModelScope.launch {
            val id = repo.saveTrip(Trip(f.id, f.title.trim(), f.destination.trim(), f.latitude, f.longitude, f.startDay!!, f.endDay!!,
                f.budget.replace(',', '.').toDoubleOrNull() ?: 0.0, f.currency, f.type, f.travelers.toInt(), f.notes.trim()))
            _form.update { it.copy(savedId = id) }
        }
    }
}

/* ------------------------------ Деталі подорожі ------------------------------ */

sealed interface WeatherState {
    data object Idle : WeatherState
    data object Loading : WeatherState
    data object NoCoordinates : WeatherState
    data class Ready(val days: List<DayWeather>, val inTrip: List<DayWeather>) : WeatherState
    data class Error(val message: String) : WeatherState
}

data class TripDetailState(
    val trip: Trip? = null,
    val activities: List<Activity> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val packing: List<PackingItem> = emptyList(),
    val budget: BudgetSummary? = null,
    val conflicts: List<Conflict> = emptyList(),
    val status: TripStatus = TripStatus.PLANNED,
    val deleted: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val tripId = MutableStateFlow(0L)
    private val _weather = MutableStateFlow<WeatherState>(WeatherState.Idle)
    val weather: StateFlow<WeatherState> = _weather.asStateFlow()
    private val deleted = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    private fun <T> byTrip(source: (Long) -> Flow<T>): Flow<T> = tripId.flatMapLatest { source(it) }

    val state: StateFlow<TripDetailState> = combine(
        byTrip { c.trips.observeTrip(it) }, byTrip { c.trips.observeActivities(it) },
        byTrip { c.trips.observeExpenses(it) }, byTrip { c.trips.observePacking(it) }, deleted
    ) { trip, acts, exps, pack, del ->
        TripDetailState(
            trip, acts, exps, pack,
            trip?.let { BudgetCalculator.summarize(it, exps, acts, c.rates.converter) },
            ItineraryPlanner.conflicts(acts),
            trip?.let { TripTimeline.status(it, LocalDate.now()) } ?: TripStatus.PLANNED,
            del
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TripDetailState())

    fun load(id: Long) { if (tripId.value != id) { tripId.value = id; _weather.value = WeatherState.Idle } }

    fun loadWeather(force: Boolean = false) {
        if (!force && _weather.value is WeatherState.Ready) return
        viewModelScope.launch {
            val trip = c.trips.trip(tripId.value) ?: return@launch
            val lat = trip.latitude; val lon = trip.longitude
            if (lat == null || lon == null) { _weather.value = WeatherState.NoCoordinates; return@launch }
            _weather.value = WeatherState.Loading
            c.weather.forecast(lat, lon)
                .onSuccess { days -> _weather.value = WeatherState.Ready(days, days.filter { it.day.toEpochDay() in trip.startDay..trip.endDay }) }
                .onFailure { _weather.value = WeatherState.Error(it.message ?: "Помилка завантаження") }
        }
    }

    fun saveActivity(a: Activity) = viewModelScope.launch {
        val id = c.trips.saveActivity(a)
        ReminderScheduler.sync(getApplication(), a.copy(id = id))
    }

    fun toggleDone(a: Activity) = saveActivity(a.copy(done = !a.done))

    fun deleteActivity(a: Activity) = viewModelScope.launch {
        c.trips.deleteActivity(a); ReminderScheduler.cancel(getApplication(), a.id)
    }

    fun addExpense(e: Expense) = viewModelScope.launch { c.trips.addExpense(e) }
    fun deleteExpense(e: Expense) = viewModelScope.launch { c.trips.deleteExpense(e) }

    fun togglePacked(p: PackingItem) = viewModelScope.launch { c.trips.updatePacking(p.copy(packed = !p.packed)) }
    fun deletePacking(p: PackingItem) = viewModelScope.launch { c.trips.deletePacking(p) }
    fun addPackingItem(name: String, category: PackingCategory) = viewModelScope.launch {
        if (name.isNotBlank()) c.trips.addPacking(tripId.value, listOf(PackingSuggestion(name.trim(), category)))
    }

    /** Генерує список речей з урахуванням типу подорожі та прогнозу (якщо вже завантажений). */
    fun generatePacking() = viewModelScope.launch {
        val trip = c.trips.trip(tripId.value) ?: return@launch
        val w = (_weather.value as? WeatherState.Ready)?.inTrip.orEmpty()
        val minT = w.minOfOrNull { it.tMin }
        val rain = w.any { it.precipitationProbability >= 50 || WeatherCodes.isBad(it.code) }
        val abroad = !trip.destination.contains("Україна", true)
        val added = c.trips.addPacking(trip.id, PackingTemplates.suggest(trip.type, trip.days, minT, rain, abroad))
        message.value = if (added == 0) "Усі рекомендовані речі вже у списку" else "Додано речей: $added"
    }

    fun deleteTrip() = viewModelScope.launch {
        val s = state.value
        s.activities.forEach { ReminderScheduler.cancel(getApplication(), it.id) }
        s.trip?.let { c.trips.deleteTrip(it) }
        deleted.value = true
    }

    fun report(): String {
        val s = state.value
        return s.trip?.let { TripReport.build(it, s.activities, s.budget, s.packing) } ?: ""
    }

    fun validateActivity(trip: Trip, title: String, day: Long?, time: String, duration: String, cost: String) =
        ActivityValidator.validate(trip, title, day, time, duration, cost)

    fun validateExpense(title: String, amount: String, currency: String) = ExpenseValidator.validate(title, amount, currency)

    fun ratesInfo(): Long = c.rates.updatedAt

    suspend fun searchPlace(q: String) = c.weather.search(q)

    fun convert(amount: Double, from: String, to: String): Double? = c.rates.converter.convert(amount, from, to)

    companion object {
        val defaultCategory = ActivityCategory.SIGHTSEEING
        val defaultExpense = ExpenseCategory.FOOD
    }
}

