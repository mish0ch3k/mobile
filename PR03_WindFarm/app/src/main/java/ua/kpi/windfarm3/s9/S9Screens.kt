package ua.kpi.windfarm3.s9

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm3.data.DataOrigin
import ua.kpi.windfarm3.data.FleetFilter
import ua.kpi.windfarm3.data.FleetSort
import ua.kpi.windfarm3.data.StationState
import ua.kpi.windfarm3.domain.Station
import ua.kpi.windfarm3.domain.TurbineStatus
import ua.kpi.windfarm3.ui.common.Chips
import ua.kpi.windfarm3.ui.common.FormField
import ua.kpi.windfarm3.ui.common.InfoRow
import ua.kpi.windfarm3.ui.common.Loading
import ua.kpi.windfarm3.ui.common.Message
import ua.kpi.windfarm3.ui.common.ScreenTitle
import ua.kpi.windfarm3.ui.common.SectionCard
import ua.kpi.windfarm3.ui.common.ageText
import ua.kpi.windfarm3.ui.common.fmt

/** Поля форми станції; [full] — з регіоном і координатами. */
@Composable
private fun StationFields(f: StationForm, onChange: (StationForm) -> Unit, full: Boolean) {
    val e = f.errors
    FormField("Назва станції", f.name, { onChange(f.copy(name = it)) }, e[0], showError = f.submitted)
    if (full) FormField("Регіон", f.region, { onChange(f.copy(region = it)) }, helper = "Наприклад, Одеська обл.")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FormField("Широта", f.lat, { onChange(f.copy(lat = it)) }, e[1], showError = f.submitted, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        FormField("Довгота", f.lon, { onChange(f.copy(lon = it)) }, e[2], showError = f.submitted, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        FormField("Турбін", f.turbines, { onChange(f.copy(turbines = it)) }, e[3], showError = f.submitted, keyboardType = KeyboardType.Number, modifier = Modifier.weight(0.8f))
    }
}

@Composable
private fun StationRow(s: Station, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, trailingText: String? = null) {
    ListItem(
        headlineContent = { Text(s.name) },
        supportingContent = { Text("${s.region} · ${s.turbines} турбін · ${s.installedMw.fmt(0)} МВт · ${s.latitude.fmt(2)}, ${s.longitude.fmt(2)}") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailingText != null) Text(trailingText, style = MaterialTheme.typography.titleSmall)
                if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Редагувати") }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Видалити") }
            }
        }
    )
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicRepoApp(vm: BasicRepoViewModel = viewModel()) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Станції (Room → Repository)", "Записів у локальній БД: ${stations.size}")
            StationFields(form, vm::edit, full = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save) { Text("Зберегти") }
                OutlinedButton(onClick = vm::fillDemo) { Text("Заповнити прикладом") }
            }
            Message(msg?.first, msg?.second ?: false)
            HorizontalDivider()
        }
        items(stations, key = { it.id }) { StationRow(it) }
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumRepoApp(vm: MediumRepoViewModel = viewModel()) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val wind by vm.wind.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Станції: Room + REST API", "Вітер — з кешу, якщо він свіжий, інакше з мережі")
            FormField("Пошук за назвою або регіоном", query, { vm.query.value = it }, helper = "Знайдено: ${stations.size}")
            Chips(listOf("", "Одеська", "Запорізька", "Миколаївська"), query, { it.ifEmpty { "Усі" } }) { vm.query.value = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loadAll(false) }) { Text("Вітер для всіх") }
                OutlinedButton(onClick = { vm.loadAll(true) }) { Text("Примусово з мережі") }
            }
            if (form.editingId != null) SectionCard("Редагування") {
                StationFields(form, { vm.form.value = it }, full = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::saveEdit) { Text("Зберегти") }
                    OutlinedButton(onClick = vm::cancelEdit) { Text("Скасувати") }
                }
            }
            Message(msg?.first, msg?.second ?: false)
        }
        items(stations, key = { it.id }) { s ->
            StationRow(s, onEdit = { vm.startEdit(s) }, onDelete = { vm.delete(s) })
            when (val w = wind[s.id]) {
                null -> {}
                WindUi.Loading -> Loading("Запит прогнозу…")
                is WindUi.Error -> Message("${w.error.title}: ${w.error.userMessage}", true)
                is WindUi.Ok -> {
                    val c = w.result.forecast.current
                    Text(
                        "Вітер ${c.speed10.fmt()} м/с (пориви ${c.gusts10.fmt()}), ${c.directionName}, ${c.temperature.fmt()} °C · " +
                            "джерело: ${w.result.origin.title}, ${ageText(w.result.fetchedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (w.result.origin == DataOrigin.NETWORK) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedRepoApp(vm: AdvancedRepoViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val sum by vm.summary.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val updated by vm.updatedAt.collectAsStateWithLifecycle()

    // Адаптивна сітка: на телефоні — одна колонка карток, на планшеті — дві-три
    LazyVerticalGrid(GridCells.Adaptive(minSize = 300.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenTitle("Парк вітрових станцій", updated?.let { "Оновлено ${ageText(it)}" })
                SectionCard {
                    InfoRow("Станцій онлайн", "${sum.online} з ${sum.stations}")
                    InfoRow("Потужність зараз", "${sum.currentMw.fmt(1)} з ${sum.installedMw.fmt(0)} МВт")
                    InfoRow("Прогноз виробітку на 24 год", "${sum.energy24hMwh.fmt(0)} МВт·год")
                    InfoRow("Середній КВВП", "${sum.avgCapacityFactor.fmt(0)} %")
                    if (sum.storms > 0) Message("Аварійна зупинка через бурю: ${sum.storms} ст.", true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { vm.refresh(true) }, enabled = !loading) { Text("Оновити з мережі") }
                        if (loading) Loading("Паралельні запити…")
                    }
                }
                Text("Фільтр", style = MaterialTheme.typography.labelLarge)
                Chips(FleetFilter.entries, filter, { it.title }) { vm.filter.value = it }
                Text("Сортування", style = MaterialTheme.typography.labelLarge)
                Chips(FleetSort.entries, sort, { it.title }) { vm.sort.value = it }
            }
        }
        items(items, key = { it.station.id }) { StationCard(it) }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun StationCard(st: StationState) {
    val colors = when {
        st is StationState.Failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        st is StationState.Ok && st.live.status == TurbineStatus.STORM_STOP -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = colors) {
        Column(Modifier.padding(12.dp)) {
            Text(st.station.name, style = MaterialTheme.typography.titleMedium)
            Text("${st.station.region} · ${st.station.turbines} × 2 МВт", style = MaterialTheme.typography.bodySmall)
            when (st) {
                is StationState.Failed -> Message("${st.error.title}: ${st.error.userMessage}", true)
                is StationState.Ok -> {
                    val l = st.live
                    InfoRow("Вітер 100 м / 10 м", "${l.hubSpeed.fmt()} / ${l.surfaceSpeed.fmt()} м/с")
                    InfoRow("Потужність", "${l.powerMw.fmt(1)} МВт")
                    LinearProgressIndicator(progress = { (l.capacityFactor / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    InfoRow("КВВП", "${l.capacityFactor.fmt(0)} %")
                    InfoRow("Стан", l.status.title)
                    InfoRow("Виробіток за 24 год", "${l.next24h.energyMwh.fmt(0)} МВт·год")
                    AssistChip(onClick = {}, label = { Text("Дані: ${l.origin.title}, ${ageText(l.fetchedAt)}") },
                        leadingIcon = { Icon(Icons.Filled.Air, null) })
                }
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkRepoApp(vm: HomeworkRepoViewModel = viewModel()) {
    val stations by vm.stations.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle(if (form.editingId == null) "Нова вітрова станція" else "Редагування станції", "CRUD через Repository · ${stations.size} записів")
            StationFields(form, vm::edit, full = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::save) { Text(if (form.editingId == null) "Додати" else "Зберегти") }
                if (form.editingId != null) OutlinedButton(onClick = vm::cancel) { Text("Скасувати") }
                else OutlinedButton(onClick = vm::fillDemo) { Text("Приклад") }
            }
            Message(msg?.first, msg?.second ?: false)
            FormField("Пошук", query, { vm.query.value = it })
            HorizontalDivider()
        }
        items(stations, key = { it.id }) { s ->
            StationRow(s, onEdit = { vm.startEdit(s) }, onDelete = { vm.delete(s) })
        }
    }
}
