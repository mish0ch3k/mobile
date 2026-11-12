package ua.kpi.windfarm4.s14

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.kpi.windfarm4.data.CatalogRepository
import ua.kpi.windfarm4.data.ObjectsViewModel
import ua.kpi.windfarm4.domain.ObjectType
import ua.kpi.windfarm4.domain.SortKey
import ua.kpi.windfarm4.map.OsmMap
import ua.kpi.windfarm4.ui.common.Chips
import ua.kpi.windfarm4.ui.common.EmptyState
import ua.kpi.windfarm4.ui.common.InfoRow
import ua.kpi.windfarm4.ui.common.ObjectCard
import ua.kpi.windfarm4.ui.common.ObjectParams
import ua.kpi.windfarm4.ui.common.ScreenTitle
import ua.kpi.windfarm4.ui.common.SectionCard
import ua.kpi.windfarm4.ui.common.StatusBadge
import ua.kpi.windfarm4.ui.common.fmt
import ua.kpi.windfarm4.ui.common.toMarker
import ua.kpi.windfarm4.ui.common.typeColor
import ua.kpi.windfarm4.ui.common.typeIcon
import ua.kpi.windfarm4.ui.common.updatedText

// ============================ БАЗОВИЙ ============================
/** LazyColumn будує лише видимі рядки; key = id зберігає стан рядка при оновленні даних. */
@Composable
fun BasicListApp() {
    val objects by CatalogRepository.objects.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize()) {
        item { Column(Modifier.padding(16.dp)) { ScreenTitle("Енергетичні об'єкти", "${objects.size} об'єктів · LazyColumn") } }
        items(objects, key = { it.id }) { o ->
            ListItem(
                leadingContent = { Icon(typeIcon(o.type), null, tint = typeColor(o.type)) },
                headlineContent = { Text(o.name) },
                supportingContent = { Text(o.type.title) }
            )
            HorizontalDivider()
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
/** Картки з описом; натискання відкриває екран деталей (Navigation Compose, аргумент id). */
@Composable
fun MediumListApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "list") {
        composable("list") {
            val objects by CatalogRepository.objects.collectAsStateWithLifecycle()
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item { ScreenTitle("Об'єкти вітроенергетики", "Натисніть картку, щоб переглянути деталі") }
                items(objects, key = { it.id }) { o -> ObjectCard(o, onClick = { nav.navigate("details/${o.id}") }) }
            }
        }
        composable("details/{id}", arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            ObjectDetails(entry.arguments?.getInt("id") ?: -1) { nav.popBackStack() }
        }
    }
}

@Composable
private fun ObjectDetails(id: Int, onBack: () -> Unit) {
    val objects by CatalogRepository.objects.collectAsStateWithLifecycle()
    val o = objects.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (o == null) { EmptyState("Об'єкт не знайдено"); OutlinedButton(onClick = onBack) { Text("← Назад") }; return@Column }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(typeIcon(o.type), null, tint = typeColor(o.type), modifier = Modifier.size(36.dp))
            Column { Text(o.name, style = MaterialTheme.typography.headlineSmall); Text(o.type.title) }
        }
        StatusBadge(o.status)
        Text(o.description, style = MaterialTheme.typography.bodyLarge)
        SectionCard("Параметри") {
            InfoRow("Регіон", "${o.region} обл.")
            InfoRow("Координати", "${o.latitude.fmt(3)}, ${o.longitude.fmt(3)}")
            if (o.capacityMw > 0) InfoRow("Встановлена потужність", "${o.capacityMw.fmt(0)} МВт")
            if (o.generates) InfoRow("Поточна генерація", "${o.powerMw.fmt(1)} МВт (${o.loadPercent.fmt(0)} %)")
            if (o.windSpeed > 0) InfoRow("Вітер", "${o.windSpeed.fmt()} м/с")
            InfoRow("Оновлено", updatedText(o.updatedAt))
        }
        OutlinedButton(onClick = onBack) { Text("← До списку") }
    }
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Список і мапа синхронізовані в обидва боки: вибір картки → мапа центрується
 * й збільшує маркер; натискання маркера → список прокручується до картки й виділяє її.
 */
@Composable
fun AdvancedListApp(vm: ObjectsViewModel = viewModel(key = "s14adv")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // маркер → список: прокрутити до вибраної картки
    LaunchedEffect(selected) {
        val i = list.indexOfFirst { it.id == selected }
        if (i >= 0) listState.animateScrollToItem(i)
    }
    Column(Modifier.fillMaxSize()) {
        OsmMap(list.map { it.toMarker() }, selected, onMarkerClick = vm::selectExact, modifier = Modifier.fillMaxWidth().height(260.dp))
        Text("Вибрано: ${list.firstOrNull { it.id == selected }?.name ?: "—"}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState) {
            items(list, key = { it.id }) { o ->
                ObjectCard(o, onClick = { vm.select(o.id) }, selected = o.id == selected, showDescription = false) { ObjectParams(o) }
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
/** Картки станцій з потужністю, сортування та фільтр за типом, повідомлення для порожнього списку. */
@Composable
fun HomeworkListApp(vm: ObjectsViewModel = viewModel(key = "s14hw")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    var type by remember { mutableStateOf<ObjectType?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Вітрові станції та інфраструктура", "Показано ${list.size} об'єктів")
            Text("Тип", style = MaterialTheme.typography.labelLarge)
            Chips(listOf<ObjectType?>(null) + ObjectType.entries, type, { it?.short ?: "Усі" }) { t ->
                type = t
                vm.update { it.copy(types = setOfNotNull(t)) }
            }
            Text("Встановлена потужність", style = MaterialTheme.typography.labelLarge)
            Chips(listOf(0.0, 50.0, 100.0), q.minCapacity, { if (it == 0.0) "Будь-яка" else "≥ ${it.fmt(0)} МВт" }) { m ->
                vm.update { it.copy(minCapacity = m) }
            }
            Text("Сортування", style = MaterialTheme.typography.labelLarge)
            Chips(listOf(SortKey.NAME, SortKey.CAPACITY, SortKey.POWER), q.sort, { it.title }) { k ->
                vm.update { it.copy(sort = k, descending = k != SortKey.NAME) }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (list.isEmpty()) item { EmptyState() }
        items(list, key = { it.id }) { o ->
            ObjectCard(o, showDescription = false) {
                Text("${o.region} обл. · ${o.latitude.fmt(2)}, ${o.longitude.fmt(2)}", style = MaterialTheme.typography.bodySmall)
                ObjectParams(o)
            }
        }
    }
}
