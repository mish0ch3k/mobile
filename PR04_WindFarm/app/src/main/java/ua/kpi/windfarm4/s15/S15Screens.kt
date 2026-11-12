package ua.kpi.windfarm4.s15

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm4.data.ObjectsViewModel
import ua.kpi.windfarm4.domain.EnergyCatalog
import ua.kpi.windfarm4.domain.ObjectQuery
import ua.kpi.windfarm4.domain.ObjectStatus
import ua.kpi.windfarm4.domain.ObjectType
import ua.kpi.windfarm4.domain.SortKey
import ua.kpi.windfarm4.ui.common.Chips
import ua.kpi.windfarm4.ui.common.EmptyState
import ua.kpi.windfarm4.ui.common.InfoRow
import ua.kpi.windfarm4.ui.common.MultiChips
import ua.kpi.windfarm4.ui.common.ObjectCard
import ua.kpi.windfarm4.ui.common.ObjectParams
import ua.kpi.windfarm4.ui.common.ScreenTitle
import ua.kpi.windfarm4.ui.common.SectionCard
import ua.kpi.windfarm4.ui.common.fmt
import ua.kpi.windfarm4.ui.common.typeIcon
import ua.kpi.windfarm4.ui.common.updatedText

/** Поле пошуку з підказками; список оновлюється на кожну введену літеру. */
@Composable
private fun SearchBox(q: ObjectQuery, vm: ObjectsViewModel, hints: List<String> = listOf("Приазов", "Тилігул", "Одеська", "метео")) {
    OutlinedTextField(
        value = q.text, onValueChange = { t -> vm.update { it.copy(text = t) } },
        label = { Text("Пошук за назвою, регіоном, описом") }, singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = { if (q.text.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(text = "") } }) { Text("✕") } },
        modifier = Modifier.fillMaxWidth()
    )
    Chips(hints, q.text, { it }) { h -> vm.update { it.copy(text = if (it.text == h) "" else h) } }
}

/** Сортування: ключ + напрям. */
@Composable
private fun SortRow(q: ObjectQuery, vm: ObjectsViewModel, keys: List<SortKey>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Сортувати:", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { vm.update { it.copy(descending = !it.descending) } }) { Text(if (q.descending) "↓ спадання" else "↑ зростання") }
    }
    Chips(keys, q.sort, { it.title }) { k -> vm.update { it.copy(sort = k) } }
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicSearchApp(vm: ObjectsViewModel = viewModel(key = "s15basic")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Пошук і сортування", "Знайдено: ${list.size}")
            SearchBox(q, vm)
            SortRow(q, vm, listOf(SortKey.NAME, SortKey.TYPE))
        }
        if (list.isEmpty()) item { EmptyState() }
        items(list, key = { it.id }) { o ->
            ListItem(leadingContent = { Icon(typeIcon(o.type), null) }, headlineContent = { Text(o.name) },
                supportingContent = { Text("${o.type.title} · ${o.region} обл.") })
            HorizontalDivider()
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumSearchApp(vm: ObjectsViewModel = viewModel(key = "s15medium")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Комбіновані фільтри", "Тип + потужність + пошук · знайдено ${list.size}")
            SearchBox(q, vm)
            Text("Типи об'єктів", style = MaterialTheme.typography.labelLarge)
            MultiChips(ObjectType.entries, q.types, { it.short }) { t -> vm.update { it.copy(types = it.types.toggle(t)) } }
            Text("Потужність від ${q.minCapacity.fmt(0)} МВт", style = MaterialTheme.typography.labelLarge)
            Slider(q.minCapacity.toFloat(), { v -> vm.update { it.copy(minCapacity = v.toDouble()) } }, valueRange = 0f..250f, steps = 9)
            SortRow(q, vm, listOf(SortKey.NAME, SortKey.CAPACITY, SortKey.POWER, SortKey.TYPE))
            if (q.activeFilters > 0) TextButton(onClick = { vm.update { ObjectQuery() } }) { Text("Скинути фільтри (${q.activeFilters})") }
        }
        if (list.isEmpty()) item { EmptyState() }
        items(list, key = { it.id }) { o -> ObjectCard(o) { ObjectParams(o) } }
    }
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Багаторівнева фільтрація (тип, статус, регіон, діапазон потужності), сортування за
 * основним і додатковим ключем, вибір кількох об'єктів з підсумком по вибраних.
 */
@Composable
fun AdvancedSearchApp(vm: ObjectsViewModel = viewModel(key = "s15adv")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val checked by vm.checked.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Інтерактивний реєстр об'єктів", "Знайдено ${list.size} · активних фільтрів ${q.activeFilters}")
            SearchBox(q, vm)
            Text("Тип", style = MaterialTheme.typography.labelLarge)
            MultiChips(ObjectType.entries, q.types, { it.short }) { t -> vm.update { it.copy(types = it.types.toggle(t)) } }
            Text("Статус", style = MaterialTheme.typography.labelLarge)
            MultiChips(ObjectStatus.entries, q.statuses, { it.title }) { s -> vm.update { it.copy(statuses = it.statuses.toggle(s)) } }
            Text("Регіон", style = MaterialTheme.typography.labelLarge)
            MultiChips(EnergyCatalog.regions, q.regions, { it }) { r -> vm.update { it.copy(regions = it.regions.toggle(r)) } }
            val max = if (q.maxCapacity == Double.MAX_VALUE) 250.0 else q.maxCapacity
            Text("Потужність: ${q.minCapacity.fmt(0)}–${max.fmt(0)} МВт", style = MaterialTheme.typography.labelLarge)
            RangeSlider(q.minCapacity.toFloat()..max.toFloat(), { r ->
                vm.update { it.copy(minCapacity = r.start.toDouble(), maxCapacity = if (r.endInclusive >= 250f) Double.MAX_VALUE else r.endInclusive.toDouble()) }
            }, valueRange = 0f..250f)
            SortRow(q, vm, listOf(SortKey.POWER, SortKey.CAPACITY, SortKey.WIND, SortKey.UPDATED, SortKey.NAME, SortKey.TYPE))
            Text("Потім за:", style = MaterialTheme.typography.labelLarge)
            Chips(listOf<SortKey?>(null, SortKey.TYPE, SortKey.CAPACITY, SortKey.WIND), q.thenBy, { it?.title ?: "—" }) { k -> vm.update { it.copy(thenBy = k) } }
            if (q.activeFilters > 0) TextButton(onClick = { vm.update { ObjectQuery() } }) { Text("Скинути все") }
            SectionCard("Вибрано: ${sel.count}") {
                InfoRow("Встановлена потужність", "${sel.capacityMw.fmt(0)} МВт")
                InfoRow("Генерація зараз", "${sel.powerMw.fmt(1)} МВт")
                InfoRow("Середній вітер", "${sel.avgWind.fmt()} м/с")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { vm.checkAll(list.map { it.id }) }, label = { Text("Вибрати всі видимі") })
                    AssistChip(onClick = vm::clearChecked, label = { Text("Зняти вибір") })
                }
            }
        }
        if (list.isEmpty()) item { EmptyState() }
        items(list, key = { it.id }) { o ->
            ObjectCard(o, onClick = { vm.toggleChecked(o.id) }, selected = o.id in checked, showDescription = false,
                leading = { Checkbox(o.id in checked, { vm.toggleChecked(o.id) }) }) {
                ObjectParams(o)
                Text("Оновлено ${updatedText(o.updatedAt)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
/** Пошук за назвою, сортування за потужністю або датою, фільтр за типом (ViewModel + StateFlow). */
@Composable
fun HomeworkSearchApp(vm: ObjectsViewModel = viewModel(key = "s15hw")) {
    val list by vm.visible.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Каталог об'єктів ВЕС", "Знайдено ${list.size} з ${vm.objects.value.size}")
            SearchBox(q, vm, listOf("ВЕС", "Турбіна", "ПС", "Карпат"))
            Text("Тип", style = MaterialTheme.typography.labelLarge)
            Chips(listOf<ObjectType?>(null) + ObjectType.entries, q.types.firstOrNull(), { it?.short ?: "Усі" }) { t ->
                vm.update { it.copy(types = setOfNotNull(t)) }
            }
            Text("Сортування (за спаданням)", style = MaterialTheme.typography.labelLarge)
            Chips(listOf(SortKey.POWER, SortKey.CAPACITY, SortKey.UPDATED), q.sort,
                { mapOf(SortKey.POWER to "Генерація", SortKey.CAPACITY to "Встановлена потужність", SortKey.UPDATED to "Дата оновлення")[it]!! }) { k ->
                vm.update { it.copy(sort = k, descending = true) }
            }
        }
        if (list.isEmpty()) item { EmptyState("Об'єктів за запитом «${q.text}» не знайдено") }
        items(list, key = { it.id }) { o ->
            ObjectCard(o, showDescription = false) {
                Text(o.type.title, style = MaterialTheme.typography.bodySmall)
                InfoRow("Потужність", if (o.capacityMw > 0) "${o.capacityMw.fmt(0)} МВт (зараз ${o.powerMw.fmt(1)})" else "—")
                InfoRow("Останнє оновлення", updatedText(o.updatedAt))
            }
        }
    }
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v
