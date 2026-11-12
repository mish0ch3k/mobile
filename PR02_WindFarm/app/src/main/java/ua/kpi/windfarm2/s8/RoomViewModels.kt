package ua.kpi.windfarm2.s8

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.kpi.windfarm2.data.AdvancedWindRepository
import ua.kpi.windfarm2.data.BasicWindRepository
import ua.kpi.windfarm2.data.MediumWindRepository
import ua.kpi.windfarm2.data.RemoteWindSource
import ua.kpi.windfarm2.data.RepoResult
import ua.kpi.windfarm2.data.SortOrder
import ua.kpi.windfarm2.data.StationStats
import ua.kpi.windfarm2.data.WindDatabase
import ua.kpi.windfarm2.data.WindMeasurement

/*
 * AndroidViewModel отримує Application → доступ до WindDatabase без ручної фабрики.
 * UI підписаний на StateFlow; будь-яка зміна таблиці автоматично оновлює список.
 */

class BasicRoomViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = BasicWindRepository(WindDatabase.get(app).dao())
    val items: StateFlow<List<WindMeasurement>> = repo.measurements.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun add(m: WindMeasurement) = viewModelScope.launch { repo.add(m) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MediumRoomViewModel(app: Application) : AndroidViewModel(app) {
    val remote = RemoteWindSource()
    private val repo = MediumWindRepository(WindDatabase.get(app).dao(), remote)
    val query = MutableStateFlow("")
    val message = MutableStateFlow<String?>(null)
    val online = MutableStateFlow(true)

    /** flatMapLatest — при зміні пошукового запиту перепідписуємось на новий запит Room. */
    val items: StateFlow<List<WindMeasurement>> = query.flatMapLatest { repo.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setOnline(v: Boolean) { online.value = v; remote.online = v }
    fun add(m: WindMeasurement) = viewModelScope.launch { repo.add(m) }
    fun update(m: WindMeasurement) = viewModelScope.launch { repo.update(m) }
    fun delete(m: WindMeasurement) = viewModelScope.launch { repo.delete(m) }
    fun sync() = viewModelScope.launch { message.value = "Синхронізація…"; message.value = repo.sync() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdvancedRoomViewModel(app: Application) : AndroidViewModel(app) {
    val remote = RemoteWindSource()
    private val repo = AdvancedWindRepository(WindDatabase.get(app).dao(), remote)
    val query = MutableStateFlow("")
    val minSpeed = MutableStateFlow(0f)
    val order = MutableStateFlow(SortOrder.NEWEST)
    val status = MutableStateFlow<RepoResult?>(null)
    val loading = MutableStateFlow(false)

    val items: StateFlow<List<WindMeasurement>> = combine(query, minSpeed, order) { q, m, o -> Triple(q, m, o) }
        .flatMapLatest { (q, m, o) -> repo.observe(q, m.toDouble(), o) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stats: StateFlow<StationStats> = repo.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StationStats(0, 0.0, 0.0, 0.0, 0))

    init { refresh(false) }

    fun refresh(force: Boolean) = viewModelScope.launch {
        loading.value = true
        status.value = repo.refresh(force)
        loading.value = false
    }

    fun setOnline(v: Boolean) { remote.online = v }
    fun add(m: WindMeasurement) = viewModelScope.launch { repo.add(m) }
    fun delete(m: WindMeasurement) = viewModelScope.launch { repo.delete(m) }
    fun clear() = viewModelScope.launch { repo.clear() }
}
