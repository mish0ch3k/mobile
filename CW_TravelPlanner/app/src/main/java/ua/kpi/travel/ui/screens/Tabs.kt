package ua.kpi.travel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityValidator.formatTime
import ua.kpi.travel.domain.BudgetCalculator
import ua.kpi.travel.domain.BudgetState
import ua.kpi.travel.domain.ItineraryPlanner
import ua.kpi.travel.domain.PackingCategory
import ua.kpi.travel.domain.PackingTemplates
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.domain.WeatherCodes
import ua.kpi.travel.ui.TripDetailState
import ua.kpi.travel.ui.TripDetailViewModel
import ua.kpi.travel.ui.WeatherState
import ua.kpi.travel.ui.common.ChipRow
import ua.kpi.travel.ui.common.KeyValue
import ua.kpi.travel.ui.common.Progress
import ua.kpi.travel.ui.common.SectionCard
import ua.kpi.travel.ui.common.epochToDate
import ua.kpi.travel.ui.common.icon
import ua.kpi.travel.ui.common.long
import ua.kpi.travel.ui.common.money
import ua.kpi.travel.ui.common.short
import ua.kpi.travel.ui.theme.Bad
import ua.kpi.travel.ui.theme.Ok
import ua.kpi.travel.ui.theme.Warn
import java.time.LocalDate

/* --------------------------------- Маршрут --------------------------------- */

@Composable
fun ItineraryTab(trip: Trip, s: TripDetailState, vm: TripDetailViewModel) {
    var editing by remember { mutableStateOf<Activity?>(null) }
    var creatingDay by remember { mutableStateOf<Long?>(null) }
    val byDay = ItineraryPlanner.byDay(s.activities)
    val conflictIds = s.conflicts.flatMap { listOf(it.first.id, it.second.id) }.toSet()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionCard {
                    KeyValue("Активностей", "${s.activities.size}, виконано ${s.activities.count { it.done }}")
                    Progress(ItineraryPlanner.progress(s.activities))
                }
            }
            if (s.conflicts.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Накладки в розкладі: ${s.conflicts.size}", fontWeight = FontWeight.SemiBold)
                            s.conflicts.take(3).forEach { c ->
                                Text("${epochToDate(c.first.day).short()}: «${c.first.title}» і «${c.second.title}» (${c.overlapMinutes} хв)",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            TripTimeline.days(trip).forEachIndexed { index, date ->
                val day = date.toEpochDay()
                val list = byDay[day].orEmpty()
                item(key = "d$day") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("День ${index + 1}. ${date.long()}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            val free = ItineraryPlanner.freeSlots(list).sumOf { it.length }
                            Text("Вільно з 8:00 до 22:00: ${free / 60} год ${free % 60} хв", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ creatingDay = day }) { Icon(Icons.Filled.Add, "Додати на цей день") }
                    }
                }
                items(list, key = { it.id }) { a -> ActivityRow(a, trip.currency, a.id in conflictIds, { vm.toggleDone(a) }) { editing = a } }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        FloatingActionButton({ creatingDay = maxOf(trip.startDay, minOf(trip.endDay, LocalDate.now().toEpochDay())) },
            Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("addActivity")) { Icon(Icons.Filled.Add, "Додати активність") }
    }
    if (editing != null || creatingDay != null) ActivityDialog(trip, editing, creatingDay ?: editing!!.day, vm,
        onDismiss = { editing = null; creatingDay = null })
}

@Composable
private fun ActivityRow(a: Activity, currency: String, conflict: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (conflict) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(a.done, { onToggle() })
            Column(Modifier.width(52.dp)) {
                Text(formatTime(a.startMinute), fontWeight = FontWeight.SemiBold)
                Text(formatTime(a.endMinute), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(a.category.icon(), null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, textDecoration = if (a.done) TextDecoration.LineThrough else null, fontWeight = FontWeight.Medium)
                if (a.place.isNotBlank()) Text(a.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.remind) Icon(Icons.Filled.NotificationsActive, "Нагадування", Modifier.padding(horizontal = 4.dp), tint = MaterialTheme.colorScheme.primary)
            if (a.cost > 0) Text(money(a.cost, currency), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/* --------------------------------- Бюджет ---------------------------------- */

@Composable
fun BudgetTab(trip: Trip, s: TripDetailState, vm: TripDetailViewModel) {
    var adding by remember { mutableStateOf(false) }
    val b = s.budget ?: return
    val color = when (b.state) { BudgetState.OK -> Ok; BudgetState.WARNING -> Warn; BudgetState.OVER -> Bad; BudgetState.NO_BUDGET -> Color.Gray }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionCard("Підсумок") {
                    KeyValue("Бюджет", if (b.budget > 0) money(b.budget, trip.currency) else "не задано")
                    KeyValue("Витрачено", money(b.spent, trip.currency), color)
                    if (b.budget > 0) {
                        Progress(b.usedShare, color)
                        KeyValue("Залишок", money(b.remaining, trip.currency), if (b.remaining < 0) Bad else Color.Unspecified)
                        BudgetCalculator.dailyAllowance(b, trip, LocalDate.now().toEpochDay())?.let { KeyValue("Можна витрачати на день", money(it, trip.currency)) }
                    }
                    KeyValue("Заплановано в маршруті", money(b.planned, trip.currency))
                    KeyValue("Прогноз загальних витрат", money(b.forecast, trip.currency))
                    if (trip.travelers > 1) KeyValue("На одного мандрівника", money(BudgetCalculator.perPerson(b.spent, trip.travelers), trip.currency))
                    Text(b.state.title, color = color, fontWeight = FontWeight.SemiBold)
                    if (b.unconverted > 0) Text("Витрат без курсу валюти: ${b.unconverted} — не враховано в сумі", color = Warn,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (b.byCategory.isNotEmpty()) item {
                SectionCard("За категоріями") {
                    val max = b.byCategory.values.max()
                    b.byCategory.entries.sortedByDescending { it.value }.forEach { (cat, v) ->
                        KeyValue(cat.title, money(v, trip.currency))
                        Progress(if (max > 0) v / max else 0.0, MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            item { Text("Витрати", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            if (s.expenses.isEmpty()) item { Text("Витрат ще немає", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(s.expenses, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, fontWeight = FontWeight.Medium)
                            Text("${e.category.title} · ${epochToDate(e.day).short()}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(money(e.amount, e.currency), fontWeight = FontWeight.SemiBold)
                            if (e.currency != trip.currency) vmConvert(vm, e.amount, e.currency, trip.currency)?.let {
                                Text("≈ ${money(it, trip.currency)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        IconButton({ vm.deleteExpense(e) }) { Icon(Icons.Filled.Close, "Видалити витрату") }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        FloatingActionButton({ adding = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("addExpense")) {
            Icon(Icons.Filled.Add, "Додати витрату")
        }
    }
    if (adding) ExpenseDialog(trip, vm) { adding = false }
}

private fun vmConvert(vm: TripDetailViewModel, amount: Double, from: String, to: String): Double? = vm.convert(amount, from, to)

/* ---------------------------------- Речі ----------------------------------- */

@Composable
fun PackingTab(s: TripDetailState, vm: TripDetailViewModel) {
    var name by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(PackingCategory.OTHER) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            SectionCard {
                KeyValue("Зібрано", "${s.packing.count { it.packed }} з ${s.packing.size}")
                Progress(PackingTemplates.progress(s.packing), Ok)
                Button(vm::generatePacking, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AutoAwesome, null); Text("  Рекомендувати речі")
                }
                Text("Список враховує тип подорожі, тривалість і прогноз погоди (відкрийте вкладку «Погода»).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Своя річ") }, singleLine = true)
                IconButton({ vm.addPackingItem(name, cat); name = "" }) { Icon(Icons.Filled.Add, "Додати річ") }
            }
            ChipRow(PackingCategory.entries, cat, { it.title }) { cat = it }
        }
        s.packing.groupBy { it.category }.toSortedMap().forEach { (category, list) ->
            item { Text(category.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            items(list, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.packed, { vm.togglePacked(p) })
                    Text(p.name + if (p.quantity > 1) " × ${p.quantity}" else "", Modifier.weight(1f),
                        textDecoration = if (p.packed) TextDecoration.LineThrough else null)
                    IconButton({ vm.deletePacking(p) }) { Icon(Icons.Filled.Close, "Видалити") }
                }
            }
        }
    }
}

/* ---------------------------------- Погода --------------------------------- */

@Composable
fun WeatherTab(trip: Trip, vm: TripDetailViewModel) {
    LaunchedEffect(trip.id) { vm.loadWeather() }
    val w by vm.weather.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val st = w) {
            WeatherState.Idle, WeatherState.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            WeatherState.NoCoordinates -> SectionCard("Немає координат") {
                Text("Відкрийте редагування подорожі й оберіть місто зі списку підказок — тоді з'явиться прогноз і мапа.")
            }
            is WeatherState.Error -> SectionCard("Не вдалося завантажити прогноз") {
                Text(st.message, color = MaterialTheme.colorScheme.error)
                OutlinedButton({ vm.loadWeather(true) }) { Icon(Icons.Filled.Refresh, null); Text("  Повторити") }
            }
            is WeatherState.Ready -> {
                if (st.inTrip.isEmpty()) SectionCard {
                    Text("Прогноз доступний на 16 днів уперед. Дати подорожі ще поза цим діапазоном — нижче погода на найближчі дні.",
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(st.days, key = { it.day.toEpochDay() }) { d ->
                        val inTrip = d.day.toEpochDay() in trip.startDay..trip.endDay
                        Card(colors = CardDefaults.cardColors(containerColor = if (inTrip) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.day.long(), fontWeight = if (inTrip) FontWeight.Bold else FontWeight.Normal)
                                    Text(d.description + if (d.precipitationProbability > 0) " · опади ${d.precipitationProbability} %" else "",
                                        style = MaterialTheme.typography.bodySmall, color = if (WeatherCodes.isBad(d.code)) Bad else Color.Unspecified)
                                }
                                Text("%.0f° / %.0f°".format(d.tMax, d.tMin), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ----------------------------------- Мапа ---------------------------------- */

@Composable
fun MapTab(trip: Trip, s: TripDetailState) {
    val withCoords = s.activities.filter { it.latitude != null && it.longitude != null }
    var selected by remember { mutableStateOf<Long?>(null) }
    if (trip.latitude == null && withCoords.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Немає координат: оберіть місто з підказок у формі подорожі або знайдіть місце в активності.")
        }
        return
    }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val done = Ok.toArgb()
    val markers = withCoords.map { a ->
        MapMarker(a.id, a.latitude!!, a.longitude!!, a.title,
            "${epochToDate(a.day).short()} ${formatTime(a.startMinute)} · ${a.place}", if (a.done) done else primary)
    }
    Column(Modifier.fillMaxSize()) {
        OsmMap(markers, selected, { selected = it }, Modifier.fillMaxWidth().weight(1f),
            center = (trip.latitude ?: markers.first().latitude) to (trip.longitude ?: markers.first().longitude))
        LazyColumn(Modifier.weight(0.6f), contentPadding = PaddingValues(8.dp)) {
            items(withCoords, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { selected = a.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(a.category.icon(), null, tint = if (a.id == selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                    Text("  ${epochToDate(a.day).short()} ${formatTime(a.startMinute)} — ${a.title}",
                        fontWeight = if (a.id == selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
