package ua.kpi.windfarm3.s12

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm3.data.remote.FaultScenario
import ua.kpi.windfarm3.errors.AppError
import ua.kpi.windfarm3.errors.ErrorHandler
import ua.kpi.windfarm3.errors.LogEntry
import ua.kpi.windfarm3.ui.common.Chips
import ua.kpi.windfarm3.ui.common.FormField
import ua.kpi.windfarm3.ui.common.InfoRow
import ua.kpi.windfarm3.ui.common.Loading
import ua.kpi.windfarm3.ui.common.Message
import ua.kpi.windfarm3.ui.common.ScreenTitle
import ua.kpi.windfarm3.ui.common.SectionCard
import ua.kpi.windfarm3.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Індикатор стану мережі. */
@Composable
private fun NetworkBadge(online: Boolean) {
    val color = if (online) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(if (online) Icons.Filled.Wifi else Icons.Filled.WifiOff, null, tint = color)
        Text(if (online) "Мережа доступна" else "Немає підключення до інтернету", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** Результат або помилка запиту. */
@Composable
private fun StateView(state: NetState) {
    when (state) {
        NetState.Idle -> {}
        is NetState.Loading -> Loading(state.text)
        is NetState.Failed -> SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.error)
                Text(state.error.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(state.error.userMessage)
            Text(if (state.error.retryable) "Тимчасова помилка — запит можна повторити" else "Повтор не допоможе — потрібно змінити запит або права",
                style = MaterialTheme.typography.bodySmall)
        }
        is NetState.Success -> SectionCard("Дані отримано") {
            state.note?.let { Message(it, false) }
            val c = state.forecast.current
            InfoRow("Час", c.time.replace('T', ' '))
            InfoRow("Вітер 10 м", "${c.speed10.fmt()} м/с, ${c.directionName}")
            InfoRow("Пориви", "${c.gusts10.fmt()} м/с")
            InfoRow("Температура", "${c.temperature.fmt()} °C")
        }
    }
}

@Composable
private fun LogView(entries: List<LogEntry>, max: Int = 8) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    if (entries.isEmpty()) Text("Журнал порожній", style = MaterialTheme.typography.bodySmall)
    entries.take(max).forEach { e ->
        Text("${fmt.format(Date(e.time))} · ${e.source} · ${e.title}: ${e.message}", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 2.dp))
    }
    if (entries.size > max) Text("… і ще ${entries.size - max}", style = MaterialTheme.typography.bodySmall)
}

/** Діалог для критичних помилок (немає мережі). */
@Composable
private fun ErrorDialog(error: AppError?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (error == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WifiOff, null) },
        title = { Text(error.title) },
        text = { Text(error.userMessage) },
        confirmButton = { TextButton(onClick = { onDismiss(); onRetry() }) { Text("Повторити") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрити") } }
    )
}

/**
 * Каркас із SnackbarHost: кожна подія ErrorHandler показується Snackbar-ом
 * з кнопкою «Повторити» (лише для тимчасових помилок).
 */
@Composable
private fun SnackbarScreen(handler: ErrorHandler?, onRetry: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val host = remember { SnackbarHostState() }
    if (handler != null) LaunchedEffect(handler) {
        handler.events.collect { e ->
            if (e == AppError.NoInternet) return@collect // для неї — діалог
            val r = host.showSnackbar("${e.title}: ${e.userMessage}", actionLabel = if (e.retryable) "Повторити" else null, withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) onRetry()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(host) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), content = content)
    }
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicErrorsApp(vm: BasicErrorsViewModel = viewModel()) {
    val online by vm.online.collectAsStateWithLifecycle()
    val lat by vm.lat.collectAsStateWithLifecycle()
    val lon by vm.lon.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val inputErr by vm.inputError.collectAsStateWithLifecycle()
    SnackbarScreen(null, vm::load) {
        ScreenTitle("Перевірка перед запитом", "Введення та підключення перевіряються до звернення до сервера")
        NetworkBadge(online)
        Chips(vm.presets, null, { it.title }) { vm.apply(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField("Широта", lat, { vm.lat.value = it }, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            FormField("Довгота", lon, { vm.lon.value = it }, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        }
        Message(inputErr?.let { "Некоректне введення: $it" }, true)
        Button(onClick = vm::load, modifier = Modifier.fillMaxWidth()) { Text("Отримати прогноз") }
        StateView(state)
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumErrorsApp(vm: MediumErrorsViewModel = viewModel()) {
    val online by vm.online.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val last = remember { arrayOf(Scenario.OK) } // останній сценарій — для «Повторити»
    SnackbarScreen(vm.handler, { vm.run(last[0]) }) {
        ScreenTitle("Централізований обробник помилок", "ErrorHandler → журнал → Snackbar / діалог")
        NetworkBadge(online)
        Text("Сценарій запиту:", style = MaterialTheme.typography.labelLarge)
        Chips(Scenario.entries, null, { it.title }) { last[0] = it; vm.run(it) }
        StateView(state)
        SectionCard("Журнал помилок (${log.size})") {
            LogView(log)
            if (log.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text("Очистити") }
        }
    }
    ErrorDialog(dialog, { vm.run(last[0]) }) { vm.dialog.value = null }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedErrorsApp(vm: AdvancedErrorsViewModel = viewModel()) {
    val online by vm.online.collectAsStateWithLifecycle()
    val scenario by vm.scenario.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    SnackbarScreen(vm.handler, vm::run) {
        ScreenTitle("Повтори, HTTP-коди, журнал у файлі", "Експоненційна затримка: 0,5 → 1 → 2 с")
        NetworkBadge(online)
        Text("Імітація відповіді сервера:", style = MaterialTheme.typography.labelLarge)
        Chips(FaultScenario.entries, scenario, { it.title }) { vm.scenario.value = it }
        Button(onClick = vm::run, enabled = state !is NetState.Loading, modifier = Modifier.fillMaxWidth()) { Text("Виконати запит") }
        if (attempts.isNotEmpty()) SectionCard("Хід виконання") { attempts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        StateView(state)
        SectionCard("Журнал у файлі (${log.size})") {
            Text(vm.logPath, style = MaterialTheme.typography.labelSmall)
            LogView(log, max = 6)
            if (log.isNotEmpty()) TextButton(onClick = vm::clearLog) { Text("Очистити журнал") }
        }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkErrorsApp(vm: HomeworkErrorsViewModel = viewModel()) {
    val online by vm.online.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val fault by vm.fault.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val logSize by vm.logSize.collectAsStateWithLifecycle()
    SnackbarScreen(vm.handler, vm::load) {
        ScreenTitle("Прогноз вітру для станції", "Мережа · повтори · Snackbar · журнал ($logSize записів)")
        NetworkBadge(online)
        Chips(vm.stations, selected, { it.name.removePrefix("ВЕС ") }) { vm.selected.value = it }
        Text("Стан сервера (для перевірки):", style = MaterialTheme.typography.labelLarge)
        Chips(vm.faultOptions, fault, { it.title }) { vm.fault.value = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::load, enabled = state !is NetState.Loading) { Text("Завантажити") }
            OutlinedButton(onClick = { vm.fault.value = FaultScenario.NONE; vm.load() }) { Text("Скинути збій") }
        }
        StateView(state)
    }
    ErrorDialog(dialog, vm::load) { vm.dialog.value = null }
}
