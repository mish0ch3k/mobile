package ua.kpi.travel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityCategory
import ua.kpi.travel.domain.ActivityValidator
import ua.kpi.travel.domain.Errors
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.ExpenseCategory
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.ui.TripDetailViewModel
import ua.kpi.travel.ui.common.ChipRow
import ua.kpi.travel.ui.common.Field
import ua.kpi.travel.ui.common.short
import ua.kpi.travel.ui.theme.Ok
import java.time.LocalDate

@Composable
fun ActivityDialog(trip: Trip, existing: Activity?, initialDay: Long, vm: TripDetailViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var place by remember { mutableStateOf(existing?.place ?: "") }
    var day by remember { mutableStateOf(existing?.day ?: initialDay) }
    var time by remember { mutableStateOf(ActivityValidator.formatTime(existing?.startMinute ?: 600)) }
    var duration by remember { mutableStateOf((existing?.durationMin ?: 60).toString()) }
    var cost by remember { mutableStateOf(existing?.cost?.takeIf { it > 0 }?.toString() ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: TripDetailViewModel.defaultCategory) }
    var remind by remember { mutableStateOf(existing?.remind ?: false) }
    var lat by remember { mutableStateOf(existing?.latitude) }
    var lon by remember { mutableStateOf(existing?.longitude) }
    var placeInfo by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableStateOf<Errors>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val days = TripTimeline.days(trip).map { it.toEpochDay() }

    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Нова активність" else "Активність") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field(title, { title = it }, "Назва", errors["title"])
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(place, { place = it; lat = null; lon = null }, "Місце / адреса", null, Modifier.weight(1f))
                    IconButton({
                        scope.launch {
                            val q = if (place.isBlank()) trip.destination else "$place"
                            vm.searchPlace(q).onSuccess { r ->
                                r.firstOrNull()?.let { lat = it.latitude; lon = it.longitude; placeInfo = "Знайдено: ${it.label}" }
                                    ?: run { placeInfo = "Місце не знайдено — вкажіть назву міста чи пам'ятки" }
                            }.onFailure { placeInfo = it.message }
                        }
                    }) { Icon(Icons.Filled.Search, "Знайти координати") }
                }
                (placeInfo ?: lat?.let { "Координати: %.4f, %.4f".format(lat, lon) })?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (lat != null) Ok else MaterialTheme.colorScheme.error)
                }
                Text("День", style = MaterialTheme.typography.labelLarge)
                ChipRow(days, day, { LocalDate.ofEpochDay(it).short() }) { day = it }
                errors["day"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(time, { time = it }, "Початок ГГ:ХХ", errors["time"], Modifier.weight(1f))
                    Field(duration, { duration = it }, "Тривалість, хв", errors["duration"], Modifier.weight(1f), number = true)
                }
                Field(cost, { cost = it }, "Вартість, ${trip.currency}", errors["cost"], number = true)
                Text("Категорія", style = MaterialTheme.typography.labelLarge)
                ChipRow(ActivityCategory.entries, category, { it.title }) { category = it }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Нагадати за годину", Modifier.weight(1f)); Switch(remind, { remind = it })
                }
            }
        },
        confirmButton = {
            TextButton({
                errors = vm.validateActivity(trip, title, day, time, duration, cost)
                if (errors.isEmpty()) {
                    vm.saveActivity((existing ?: Activity(tripId = trip.id, day = day, startMinute = 0, durationMin = 0, title = "")).copy(
                        title = title.trim(), place = place.trim(), day = day, startMinute = ActivityValidator.parseTime(time)!!,
                        durationMin = duration.trim().toInt(), cost = cost.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        category = category, remind = remind, latitude = lat, longitude = lon))
                    onDismiss()
                }
            }) { Text("Зберегти") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton({ vm.deleteActivity(existing); onDismiss() }) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
                TextButton(onDismiss) { Text("Скасувати") }
            }
        })
}

@Composable
fun ExpenseDialog(trip: Trip, vm: TripDetailViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(trip.currency) }
    var category by remember { mutableStateOf(TripDetailViewModel.defaultExpense) }
    var errors by remember { mutableStateOf<Errors>(emptyMap()) }
    val today = LocalDate.now().toEpochDay()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Нова витрата") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Field(title, { title = it }, "За що", errors["title"])
                Field(amount, { amount = it }, "Сума", errors["amount"], number = true)
                Text("Валюта", style = MaterialTheme.typography.labelLarge)
                ChipRow(CURRENCIES, currency, { it }) { currency = it }
                if (currency != trip.currency) vm.convert(1.0, currency, trip.currency)?.let {
                    Text("Курс: 1 $currency ≈ %.2f ${trip.currency}".format(it), style = MaterialTheme.typography.bodySmall)
                }
                Text("Категорія", style = MaterialTheme.typography.labelLarge)
                ChipRow(ExpenseCategory.entries, category, { it.title }) { category = it }
            }
        },
        confirmButton = {
            TextButton({
                errors = vm.validateExpense(title, amount, currency)
                if (errors.isEmpty()) {
                    vm.addExpense(Expense(tripId = trip.id, title = title.trim(), amount = amount.replace(',', '.').toDouble(),
                        currency = currency, category = category, day = today))
                    onDismiss()
                }
            }) { Text("Додати") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Скасувати") } })
}
