package ua.kpi.windfarm3.s9

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windfarm3.AppGraph
import ua.kpi.windfarm3.data.BasicStationRepository
import ua.kpi.windfarm3.data.FleetFilter
import ua.kpi.windfarm3.data.FleetRepository
import ua.kpi.windfarm3.data.FleetSort
import ua.kpi.windfarm3.data.FleetSummary
import ua.kpi.windfarm3.data.StationRepository
import ua.kpi.windfarm3.data.StationState
import ua.kpi.windfarm3.data.WindResult
import ua.kpi.windfarm3.domain.DemoStations
import ua.kpi.windfarm3.domain.Station
import ua.kpi.windfarm3.domain.StationValidator
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorMapper

/** Стан полів форми станції (рядки — так, як їх ввів користувач). */
data class StationForm(
    val name: String = "",
    val region: String = "",
    val lat: String = "",
    val lon: String = "",
    val turbines: String = "",
    val editingId: Long? = null,
    val submitted: Boolean = false
) {
    val errors get() = StationValidator.errors(name, lat, lon, turbines)
    val isValid get() = errors.all { it == null }

    fun toStation() = Station(
        id = editingId ?: 0, name = name.trim(), region = region.trim().ifEmpty { "—" },
        latitude = StationValidator.parse(lat)!!, longitude = StationValidator.parse(lon)!!, turbines = turbines.trim().toInt()
    )

    companion object {
        fun of(s: Station) = StationForm(s.name, s.region, s.latitude.toString(), s.longitude.toString(), s.turbines.toString(), s.id)

        /** Заповнення прикладом — демо-станція за номером (зручно для перевірки форми). */
        fun demo(i: Int) = of(DemoStations.all[i % DemoStations.all.size]).copy(editingId = null)
    }
}

// ============================ БАЗОВИЙ ============================
/** ViewModel працює лише з Repository; Room прихований за StationLocalDataSource. */
class BasicRepoViewModel : ViewModel() {
    private val repo = BasicStationRepository(AppGraph.stationLocal)
    val stations: StateFlow<List<Station>> = repo.stations.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val form = MutableStateFlow(StationForm())
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)
    private var demoIndex = 0

    fun edit(f: StationForm) { form.value = f }
    fun fillDemo() { form.value = StationForm.demo(demoIndex++) }

    fun save() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) { message.value = "Перевірте поля форми" to true; return }
        viewModelScope.launch {
            message.value = try {
                repo.add(f.toStation())
                form.value = StationForm()
                "Станцію «${f.name.trim()}» збережено" to false
            } catch (e: Exception) {
                "Помилка збереження: ${e.message}" to true
            }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
/** Стан завантаження вітру для конкретної станції. */
sealed interface WindUi {
    data object Loading : WindUi
    data class Ok(val result: WindResult) : WindUi
    data class Error(val error: AppError) : WindUi
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediumRepoViewModel(private val repo: StationRepository = AppGraph.stationRepository) : ViewModel() {
    val query = MutableStateFlow("")

    /** «Реактивний» список: зміна запиту або таблиці в Room → новий список у UI. */
    val stations: StateFlow<List<Station>> = query.flatMapLatest { repo.stations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _wind = MutableStateFlow<Map<Long, WindUi>>(emptyMap())
    val wind: StateFlow<Map<Long, WindUi>> = _wind.asStateFlow()

    val form = MutableStateFlow(StationForm())
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)

    init { viewModelScope.launch { repo.seedIfEmpty() } }

    fun loadWind(s: Station, force: Boolean = false) {
        _wind.update { it + (s.id to WindUi.Loading) }
        viewModelScope.launch {
            val ui = try { WindUi.Ok(repo.wind(s, force)) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                WindUi.Error(ErrorMapper.map(e))
            }
            _wind.update { it + (s.id to ui) }
        }
    }

    fun loadAll(force: Boolean) = stations.value.forEach { loadWind(it, force) }

    fun startEdit(s: Station) { form.value = StationForm.of(s) }
    fun cancelEdit() { form.value = StationForm() }

    fun saveEdit() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid || f.editingId == null) { message.value = "Перевірте поля форми" to true; return }
        viewModelScope.launch {
            repo.update(f.toStation())
            _wind.update { it - f.editingId } // координати могли змінитися — старий вітер недійсний
            form.value = StationForm()
            message.value = "Зміни збережено" to false
        }
    }

    fun delete(s: Station) = viewModelScope.launch {
        repo.delete(s)
        _wind.update { it - s.id }
        message.value = "Станцію «${s.name}» видалено" to false
    }
}

// ============================ ПРОСУНУТИЙ ============================
class AdvancedRepoViewModel(private val repo: FleetRepository = AppGraph.fleetRepository) : ViewModel() {
    private val raw = MutableStateFlow<List<StationState>>(emptyList())
    val filter = MutableStateFlow(FleetFilter.ALL)
    val sort = MutableStateFlow(FleetSort.POWER)
    val loading = MutableStateFlow(false)
    val updatedAt = MutableStateFlow<Long?>(null)

    /** Фільтрація й сортування — похідні від трьох потоків; список перераховується автоматично. */
    val items: StateFlow<List<StationState>> = combine(raw, filter, sort) { list, f, s -> FleetRepository.filterAndSort(list, f, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val summary: StateFlow<FleetSummary> = raw.map { FleetRepository.summary(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, FleetRepository.summary(emptyList()))

    init { refresh(false) }

    fun refresh(force: Boolean) {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            try {
                raw.value = repo.refresh(force)
                updatedAt.value = System.currentTimeMillis()
            } finally {
                loading.value = false
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
/** CRUD станцій з перевіркою введених значень. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeworkRepoViewModel(private val repo: StationRepository = AppGraph.stationRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val stations: StateFlow<List<Station>> = query.flatMapLatest { repo.stations(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val form = MutableStateFlow(StationForm())
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)
    private var demoIndex = 0

    fun edit(f: StationForm) { form.value = f }
    fun fillDemo() { form.value = StationForm.demo(demoIndex++) }
    fun startEdit(s: Station) { form.value = StationForm.of(s); message.value = null }
    fun cancel() { form.value = StationForm() }

    /** Додавання або оновлення — залежно від того, чи редагується наявний запис. */
    fun save() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) { message.value = "Виправте помилки у формі" to true; return }
        viewModelScope.launch {
            message.value = try {
                if (f.editingId == null) repo.add(f.toStation()) else repo.update(f.toStation())
                form.value = StationForm()
                (if (f.editingId == null) "Додано «${f.name.trim()}»" else "Оновлено «${f.name.trim()}»") to false
            } catch (e: Exception) {
                "Помилка БД: ${e.message}" to true
            }
        }
    }

    fun delete(s: Station) = viewModelScope.launch {
        repo.delete(s)
        if (form.value.editingId == s.id) form.value = StationForm()
        message.value = "Видалено «${s.name}»" to false
    }
}
