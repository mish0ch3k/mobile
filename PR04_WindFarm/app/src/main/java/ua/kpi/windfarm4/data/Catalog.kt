package ua.kpi.windfarm4.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ua.kpi.windfarm4.domain.EnergyCatalog
import ua.kpi.windfarm4.domain.EnergyObject
import ua.kpi.windfarm4.domain.LiveCatalog
import ua.kpi.windfarm4.domain.ObjectFilter
import ua.kpi.windfarm4.domain.ObjectQuery
import ua.kpi.windfarm4.domain.UserLocation
import ua.kpi.windfarm4.map.LocationAccess
import ua.kpi.windfarm4.map.LocationTracker

/**
 * Єдине джерело даних про об'єкти для всіх екранів: «гарячий» StateFlow поверх
 * імітації телеметрії. Живе весь час роботи застосунку, тож при перемиканні екранів
 * дані не генеруються наново, а всі списки й мапи бачать однаковий стан.
 */
object CatalogRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val objects: StateFlow<List<EnergyObject>> = LiveCatalog().updates()
        .stateIn(scope, SharingStarted.Eagerly, EnergyCatalog.initial())

    fun byId(id: Int): EnergyObject? = objects.value.firstOrNull { it.id == id }
}

/**
 * Спільна ViewModel списків і мап (заняття 14–16). Кожен екран отримує власний
 * екземпляр (viewModel(key = …)), тож фільтри й вибір не змішуються між рівнями.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ObjectsViewModel(app: Application) : AndroidViewModel(app) {
    private val tracker = LocationTracker(app)

    val objects: StateFlow<List<EnergyObject>> = CatalogRepository.objects
    val query = MutableStateFlow(ObjectQuery())
    val selectedId = MutableStateFlow<Int?>(null)
    val checked = MutableStateFlow<Set<Int>>(emptySet())
    val access = MutableStateFlow(tracker.access())
    val locationError = MutableStateFlow<String?>(null)

    /** Позиція користувача: потік LocationManager запускається лише за наявності дозволу. */
    val location: StateFlow<UserLocation?> = access.flatMapLatest { a ->
        if (a == LocationAccess.GRANTED) tracker.updates().catch { e -> locationError.value = e.message } else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Видимий список = фільтр + сортування; перераховується при зміні даних, запиту чи позиції. */
    val visible: StateFlow<List<EnergyObject>> = combine(objects, query, location) { list, q, loc -> ObjectFilter.apply(list, q, loc) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ObjectFilter.apply(objects.value, ObjectQuery()))

    val selection: StateFlow<ObjectFilter.Selection> = combine(objects, checked) { list, ids -> ObjectFilter.summarize(list.filter { it.id in ids }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ObjectFilter.summarize(emptyList()))

    fun update(f: (ObjectQuery) -> ObjectQuery) = query.update(f)
    fun select(id: Int?) { selectedId.value = if (selectedId.value == id) null else id }
    fun selectExact(id: Int) { selectedId.value = id }
    fun toggleChecked(id: Int) = checked.update { if (id in it) it - id else it + id }
    fun checkAll(ids: Collection<Int>) { checked.value = ids.toSet() }
    fun clearChecked() { checked.value = emptySet() }
    fun refreshAccess() { access.value = tracker.access() }
}
