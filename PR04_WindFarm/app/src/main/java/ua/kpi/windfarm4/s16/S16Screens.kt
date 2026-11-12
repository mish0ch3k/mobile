package ua.kpi.windfarm4.s16

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm4.data.ObjectsViewModel
import ua.kpi.windfarm4.domain.ObjectFilter
import ua.kpi.windfarm4.domain.ObjectStatus
import ua.kpi.windfarm4.domain.ObjectType
import ua.kpi.windfarm4.domain.SortKey
import ua.kpi.windfarm4.domain.UserLocation
import ua.kpi.windfarm4.map.LocationPermissionCard
import ua.kpi.windfarm4.map.OsmMap
import ua.kpi.windfarm4.ui.common.Chips
import ua.kpi.windfarm4.ui.common.EmptyState
import ua.kpi.windfarm4.ui.common.InfoRow
import ua.kpi.windfarm4.ui.common.Message
import ua.kpi.windfarm4.ui.common.MultiChips
import ua.kpi.windfarm4.ui.common.ObjectCard
import ua.kpi.windfarm4.ui.common.ObjectParams
import ua.kpi.windfarm4.ui.common.SectionCard
import ua.kpi.windfarm4.ui.common.fmt
import ua.kpi.windfarm4.ui.common.toMarker

/** Рядок із поточною позицією користувача. */
@Composable
private fun LocationLine(loc: UserLocation?) {
    if (loc == null) return
    AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Filled.MyLocation, null) },
        label = { Text("${loc.latitude.fmt(4)}, ${loc.longitude.fmt(4)} · ±${loc.accuracyM.toInt()} м · ${loc.provider}") })
}

// ============================ БАЗОВИЙ ============================
/** Маркери всіх об'єктів і поточна позиція користувача (LocationManager). */
@Composable
fun BasicMapApp(vm: ObjectsViewModel = viewModel(key = "s16basic")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            LocationPermissionCard(access) { vm.refreshAccess() }
            LocationLine(loc)
        }
        OsmMap(list.map { it.toMarker() }, null, onMarkerClick = {}, user = loc, modifier = Modifier.fillMaxSize())
    }
}

// ============================ СЕРЕДНІЙ ============================
/** InfoWindow на маркері та двобічна синхронізація мапи зі списком карток. */
@Composable
fun MediumMapApp(vm: ObjectsViewModel = viewModel(key = "s16medium")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { list.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it) } }
    Column(Modifier.fillMaxSize()) {
        LocationPermissionCard(access) { vm.refreshAccess() }
        OsmMap(list.map { it.toMarker() }, selected, onMarkerClick = vm::selectExact, user = loc, showInfoWindow = true,
            modifier = Modifier.fillMaxWidth().height(300.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            items(list, key = { it.id }) { o ->
                ObjectCard(o, onClick = { vm.select(o.id) }, selected = o.id == selected, showDescription = o.id == selected)
            }
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Моніторинг на мапі: багаторівневі фільтри (тип, статус) і сортування (зокрема за відстанню
 * до користувача), картки з потужністю і статусом, фокус мапи на вибраному об'єкті.
 */
@Composable
fun AdvancedMapApp(vm: ObjectsViewModel = viewModel(key = "s16adv")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { list.indexOfFirst { it.id == selected }.takeIf { it >= 0 }?.let { listState.animateScrollToItem(it + 1) } }
    Column(Modifier.fillMaxSize()) {
        OsmMap(list.map { it.toMarker() }, selected, onMarkerClick = vm::selectExact, user = loc, showInfoWindow = true,
            modifier = Modifier.fillMaxWidth().height(250.dp))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            item {
                LocationPermissionCard(access) { vm.refreshAccess() }
                MultiChips(ObjectType.entries, q.types, { it.short }) { t -> vm.update { it.copy(types = if (t in it.types) it.types - t else it.types + t) } }
                MultiChips(ObjectStatus.entries, q.statuses, { it.title }) { s -> vm.update { it.copy(statuses = if (s in it.statuses) it.statuses - s else it.statuses + s) } }
                Chips(listOf(SortKey.DISTANCE, SortKey.POWER, SortKey.CAPACITY, SortKey.WIND), q.sort, { it.title }) { k ->
                    vm.update { it.copy(sort = k, descending = k != SortKey.DISTANCE) }
                }
                if (q.sort == SortKey.DISTANCE && loc == null) Message("Для сортування за відстанню потрібна геолокація", true)
                // Найближча вітрова станція до користувача (бізнес-логіка з геообчисленнями)
                loc?.let { u ->
                    list.filter { it.type == ObjectType.WIND_FARM }.minByOrNull { ObjectFilter.distanceKm(it, u) }?.let { n ->
                        SectionCard { InfoRow("Найближча ВЕС", "${n.name}, ${ObjectFilter.distanceKm(n, u).fmt(0)} км") }
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState() }
            items(list, key = { it.id }) { o ->
                ObjectCard(o, onClick = { vm.select(o.id) }, selected = o.id == selected, showDescription = false) {
                    ObjectParams(o, loc?.let { ObjectFilter.distanceKm(o, it) })
                }
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
/**
 * Мапа з маркерами та InfoWindow, позиція користувача оновлюється в реальному часі,
 * обробка відсутності дозволу та вимкненої геолокації.
 */
@Composable
fun HomeworkMapApp(vm: ObjectsViewModel = viewModel(key = "s16hw")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val loc by vm.location.collectAsStateWithLifecycle()
    val err by vm.locationError.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Мапа вітроенергетичних об'єктів", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            LocationPermissionCard(access) { vm.refreshAccess() }
            Message(err, true)
            LocationLine(loc)
            val sel = list.firstOrNull { it.id == selected }
            if (sel != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${sel.name}: ${sel.status.title}" + (loc?.let { ", ${ObjectFilter.distanceKm(sel, it).fmt(0)} км від вас" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium)
            } else Text("Натисніть маркер, щоб побачити інформацію про об'єкт", style = MaterialTheme.typography.bodySmall)
        }
        OsmMap(list.map { it.toMarker() }, selected, onMarkerClick = vm::selectExact, user = loc, showInfoWindow = true,
            modifier = Modifier.fillMaxSize())
    }
}
