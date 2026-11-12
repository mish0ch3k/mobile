package ua.kpi.travel.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.travel.domain.TripType
import ua.kpi.travel.ui.TripEditViewModel
import ua.kpi.travel.ui.common.ChipRow
import ua.kpi.travel.ui.common.DateDialog
import ua.kpi.travel.ui.common.Field
import ua.kpi.travel.ui.common.epochToDate
import ua.kpi.travel.ui.common.long
import ua.kpi.travel.ui.theme.Ok

val CURRENCIES = listOf("UAH", "EUR", "USD", "PLN", "GBP", "CZK")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripEditScreen(id: Long, onDone: (Long) -> Unit, onBack: () -> Unit, vm: TripEditViewModel = viewModel()) {
    LaunchedEffect(id) { vm.load(id) }
    val f by vm.form.collectAsStateWithLifecycle()
    LaunchedEffect(f.savedId) { f.savedId?.let(onDone) }
    var picker by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (id == 0L) "Нова подорож" else "Редагування") },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(f.title, { v -> vm.update { it.copy(title = v) } }, "Назва подорожі", f.errors["title"], Modifier.fillMaxWidth().testTag("title"))
            Field(f.destination, vm::onDestination, "Пункт призначення (місто)", f.errors["destination"] ?: f.searchError, Modifier.fillMaxWidth().testTag("destination"))
            if (f.latitude != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, tint = Ok)
                Text(" Координати: %.4f, %.4f".format(f.latitude, f.longitude), style = MaterialTheme.typography.bodySmall)
            }
            if (f.suggestions.isNotEmpty()) Card {
                f.suggestions.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.choosePlace(p) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary); Text("  ${p.label}")
                    }
                    HorizontalDivider()
                }
            }
            Text("Дати", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ picker = "start" }, Modifier.weight(1f)) { Text(f.startDay?.let { "з " + epochToDate(it).long() } ?: "Початок") }
                OutlinedButton({ picker = "end" }, Modifier.weight(1f)) { Text(f.endDay?.let { "по " + epochToDate(it).long() } ?: "Кінець") }
            }
            (f.errors["start"] ?: f.errors["end"])?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("Тип подорожі", style = MaterialTheme.typography.labelLarge)
            ChipRow(TripType.entries, f.type, { it.title }) { v -> vm.update { it.copy(type = v) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.budget, { v -> vm.update { it.copy(budget = v) } }, "Бюджет", f.errors["budget"], Modifier.weight(1f).testTag("budget"), number = true)
                Field(f.travelers, { v -> vm.update { it.copy(travelers = v) } }, "Мандрівників", f.errors["travelers"], Modifier.weight(1f), number = true)
            }
            Text("Валюта бюджету", style = MaterialTheme.typography.labelLarge)
            ChipRow(CURRENCIES, f.currency, { it }) { v -> vm.update { it.copy(currency = v) } }
            Field(f.notes, { v -> vm.update { it.copy(notes = v) } }, "Нотатки", null, singleLine = false)
            Button(vm::save, Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("save")) { Text("Зберегти") }
        }
    }
    when (picker) {
        "start" -> DateDialog(f.startDay, { picker = null }) { d -> vm.update { it.copy(startDay = d, endDay = if ((it.endDay ?: d) < d) d else it.endDay) } }
        "end" -> DateDialog(f.endDay ?: f.startDay, { picker = null }) { d -> vm.update { it.copy(endDay = d) } }
    }
}
