package ua.kpi.windfarm2.s7

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm2.domain.TurbineSpec
import ua.kpi.windfarm2.domain.WindPowerCalculator
import ua.kpi.windfarm2.s7.viewmodel.AdvancedWindViewModel
import ua.kpi.windfarm2.s7.viewmodel.BasicWindViewModel
import ua.kpi.windfarm2.s7.viewmodel.FarmResult
import ua.kpi.windfarm2.s7.viewmodel.MediumWindViewModel
import ua.kpi.windfarm2.s7.viewmodel.MonitoringViewModel
import ua.kpi.windfarm2.s7.viewmodel.StationSnapshot
import ua.kpi.windfarm2.s7.viewmodel.UiState
import ua.kpi.windfarm2.ui.common.FormField
import ua.kpi.windfarm2.ui.common.InfoRow
import ua.kpi.windfarm2.ui.common.ScreenTitle
import ua.kpi.windfarm2.ui.common.fmt

/** Базовий: UI підписаний на StateFlow ViewModel і оновлюється при зміні значень. */
@Composable
fun BasicMvvmApp(vm: BasicWindViewModel = viewModel()) {
    val speed by vm.windSpeed.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenTitle("Датчик вітру (ViewModel)")
        Text("${speed.fmt()} м/с", style = MaterialTheme.typography.displaySmall)
        Text("Оновлень: $updates")
        Button(onClick = vm::refresh) { Text("Отримати вимір") }
    }
}

/** Середній: кілька потоків, валідація і похідні значення у двох окремих компонентах. */
@Composable
fun MediumMvvmApp(vm: MediumWindViewModel = viewModel()) {
    val text by vm.speedText.collectAsStateWithLifecycle()
    val error by vm.speedError.collectAsStateWithLifecycle()
    val n by vm.turbines.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Розрахунок станції (MVVM)")
        // Компонент 1 — введення
        Card { Column(Modifier.padding(12.dp)) {
            FormField("Швидкість вітру, м/с", text, vm::onSpeedChange, error, "0…60 м/с", showError = true, keyboardType = KeyboardType.Decimal)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.onTurbinesChange(-1) }) { Text("−") }
                Text("Турбін: $n")
                OutlinedButton(onClick = { vm.onTurbinesChange(+1) }) { Text("+") }
            }
        } }
        // Компонент 2 — результат (той самий ViewModel, інший composable)
        ResultCard(result)
    }
}

@Composable
private fun ResultCard(r: FarmResult?) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        if (r == null) Text("Немає коректних даних", color = MaterialTheme.colorScheme.error)
        else {
            InfoRow("Потужність турбіни", "${r.turbineKw.fmt(0)} кВт")
            InfoRow("Потужність станції", "${r.farmMw.fmt(2)} МВт")
            InfoRow("Бофорт", "${r.beaufort} — ${WindPowerCalculator.beaufortName(r.beaufort)}")
            InfoRow("Стан", r.status.title)
        }
    } }
}

/** Просунутий: асинхронне завантаження, стани Loading / Success / Error, адаптивний макет. */
@Composable
fun AdvancedMvvmApp(vm: AdvancedWindViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Телеметрія станції", "Дані завантажуються з «сервера» (25% збоїв для демонстрації)")
        when (val s = state) {
            UiState.Loading -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Text("Завантаження…")
            }
            is UiState.Error -> Column(Modifier.padding(16.dp)) {
                Text("Помилка: ${s.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::load) { Text("Повторити") }
            }
            is UiState.Success -> SnapshotView(s.station, vm::load)
        }
    }
}

@Composable
private fun SnapshotView(st: StationSnapshot, onRefresh: () -> Unit) {
    BoxWithConstraints {
        val wide = maxWidth >= 600.dp
        val summary = @Composable {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                InfoRow("Сумарна потужність", "${st.totalMw.fmt(2)} МВт")
                InfoRow("Середній вітер", "${st.avgSpeed.fmt()} м/с")
                InfoRow("Зупинені турбіни", "${st.stopped} з ${st.speeds.size}")
                Button(onClick = onRefresh) { Text("Оновити") }
            } }
        }
        val list = @Composable {
            Column { st.speeds.forEachIndexed { i, v ->
                InfoRow("ВТ-${i + 1}", "${v.fmt()} м/с · ${WindPowerCalculator.turbinePowerKw(v, TurbineSpec()).fmt(0)} кВт")
            } }
        }
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) { summary() }; Column(Modifier.weight(1f)) { list() }
        } else Column(Modifier.verticalScroll(rememberScrollState())) { summary(); list() }
    }
}

/** Домашнє: моніторинг у реальному часі, графік останніх 20 вимірів (Canvas). */
@Composable
fun HomeworkMvvmApp(vm: MonitoringViewModel = viewModel()) {
    val history by vm.history.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val last = history.lastOrNull() ?: 0.0
    val color = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Моніторинг швидкості вітру")
        InfoRow("Поточний вітер", "${last.fmt()} м/с")
        InfoRow("Потужність турбіни", "${WindPowerCalculator.turbinePowerKw(last, TurbineSpec()).fmt(0)} кВт")
        InfoRow("Стан", WindPowerCalculator.status(last, TurbineSpec()).title)
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (history.size >= 2) {
                val path = Path()
                history.forEachIndexed { i, v ->
                    val p = Offset(size.width * i / 19f, size.height * (1 - (v / 30.0).toFloat()))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(width = 4f))
            }
        }
        Button(onClick = vm::toggle, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Зупинити моніторинг" else "Запустити моніторинг") }
    }
}
