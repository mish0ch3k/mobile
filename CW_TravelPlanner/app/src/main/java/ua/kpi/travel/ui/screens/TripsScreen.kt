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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripStatus
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.ui.TripFilter
import ua.kpi.travel.ui.TripsViewModel
import ua.kpi.travel.ui.common.ChipRow
import ua.kpi.travel.ui.common.short
import ua.kpi.travel.ui.theme.Ok
import ua.kpi.travel.ui.theme.Sunset
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(onOpen: (Long) -> Unit, onCreate: () -> Unit, vm: TripsViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Мої подорожі") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Нова подорож") },
                modifier = Modifier.testTag("addTrip"))
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(s.query, vm::setQuery, Modifier.fillMaxWidth(), placeholder = { Text("Пошук за назвою або містом") },
                leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true)
            Spacer(Modifier.padding(4.dp))
            ChipRow(TripFilter.entries, s.filter, { it.title }, vm::setFilter)
            if (s.loaded && s.trips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (s.query.isBlank() && s.filter == TripFilter.ALL) "Подорожей ще немає.\nНатисніть «Нова подорож»." else "Нічого не знайдено",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(s.trips, key = { it.id }) { TripCard(it) { onOpen(it.id) } }
                item { Spacer(Modifier.padding(36.dp)) }
            }
        }
    }
}

@Composable
private fun TripCard(t: Trip, onClick: () -> Unit) {
    val today = LocalDate.now()
    val status = TripTimeline.status(t, today)
    val until = TripTimeline.daysUntil(t, today)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flight, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(t.destination, style = MaterialTheme.typography.bodyMedium)
                Text("${t.start.short()} – ${t.end.short()} · ${t.days} дн. · ${t.type.title}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val label = when (status) {
                TripStatus.PLANNED -> if (until == 1L) "завтра" else "через $until дн."
                TripStatus.ONGOING -> "триває"
                TripStatus.FINISHED -> "завершена"
            }
            AssistChip(onClick = onClick, label = {
                Text(label, color = when (status) { TripStatus.ONGOING -> Ok; TripStatus.PLANNED -> Sunset; else -> MaterialTheme.colorScheme.onSurfaceVariant })
            })
        }
    }
}
