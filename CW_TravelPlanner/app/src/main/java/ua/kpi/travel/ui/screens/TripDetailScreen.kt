package ua.kpi.travel.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.travel.domain.TripStatus
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.ui.TripDetailViewModel
import ua.kpi.travel.ui.common.short
import java.time.LocalDate

private val TABS = listOf("Маршрут", "Бюджет", "Речі", "Погода", "Мапа")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(id: Long, onEdit: (Long) -> Unit, onBack: () -> Unit, vm: TripDetailViewModel = viewModel()) {
    LaunchedEffect(id) { vm.load(id) }
    val s by vm.state.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(s.deleted) { if (s.deleted) onBack() }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.message.value = null } }
    val trip = s.trip

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(trip?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (trip != null) {
                            val until = TripTimeline.daysUntil(trip, LocalDate.now())
                            val st = when (s.status) {
                                TripStatus.PLANNED -> "через $until дн."
                                TripStatus.ONGOING -> "день ${-until + 1} з ${trip.days}"
                                TripStatus.FINISHED -> "завершена"
                            }
                            Text("${trip.destination} · ${trip.start.short()}–${trip.end.short()} · $st",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton({
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, trip?.title).putExtra(Intent.EXTRA_TEXT, vm.report()), "Поділитися планом"))
                    }) { Icon(Icons.Filled.Share, "Поділитися") }
                    IconButton({ onEdit(id) }) { Icon(Icons.Filled.Edit, "Редагувати") }
                    IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Видалити") }
                })
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            if (trip != null) when (tab) {
                0 -> ItineraryTab(trip, s, vm)
                1 -> BudgetTab(trip, s, vm)
                2 -> PackingTab(s, vm)
                3 -> WeatherTab(trip, vm)
                4 -> MapTab(trip, s)
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Видалити подорож?") },
        text = { Text("Маршрут, витрати та список речей буде видалено без можливості відновлення.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteTrip() }) { Text("Видалити", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Скасувати") } })
}
