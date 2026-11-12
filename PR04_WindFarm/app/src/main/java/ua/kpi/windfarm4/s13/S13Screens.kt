package ua.kpi.windfarm4.s13

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm4.domain.WindPowerCalculator
import ua.kpi.windfarm4.ui.common.InfoRow
import ua.kpi.windfarm4.ui.common.Message
import ua.kpi.windfarm4.ui.common.ScreenTitle
import ua.kpi.windfarm4.ui.common.SectionCard
import ua.kpi.windfarm4.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)

@Composable
private fun StartStop(running: Boolean, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(if (running) "Зупинити збір" else "Почати збір даних") }

/** Великий показник швидкості вітру. */
@Composable
private fun BigValue(speed: Double?, caption: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(speed?.fmt() ?: "—", fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
        Text("м/с  $caption", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
    }
}

/** Лінійний графік значень (найновіше — праворуч). */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier.fillMaxWidth().height(90.dp), max: Double = 30.0) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        listOf(0.25f, 0.5f, 0.75f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f)) }
        if (values.size < 2) return@Canvas
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val p = Offset(i * step, size.height * (1 - (v / max).toFloat().coerceIn(0f, 1f)))
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, color, style = Stroke(width = 4f))
    }
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicFlowApp(vm: BasicFlowViewModel = viewModel()) {
    val latest by vm.latest.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Холодний потік датчика", "flow { emit(); delay(1000) } → collect у viewModelScope")
        BigValue(latest?.speed, latest?.let { "вимір №${it.seq}" } ?: "")
        latest?.let { InfoRow("Напрям вітру", "${it.directionDeg.fmt(0)}°") }
        StartStop(running, vm::toggle)
        Sparkline(history.reversed().map { it.speed })
        SectionCard("Останні виміри") {
            if (history.isEmpty()) Text("Даних ще немає — запустіть збір")
            history.forEach { InfoRow("${timeFmt.format(Date(it.time))} · №${it.seq}", "${it.speed.fmt()} м/с") }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumFlowApp(vm: MediumFlowViewModel = viewModel()) {
    val s by vm.sample.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val rejected by vm.rejected.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val threshold by vm.threshold.collectAsStateWithLifecycle()
    val applied by vm.appliedThreshold.collectAsStateWithLifecycle()
    val alarm by vm.alarm.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Трансформації та помилки", "filter → map → catch; debounce для порогу")
        BigValue(s?.reading?.speed, s?.let { "→ ${it.powerKw.fmt(0)} кВт" } ?: "")
        s?.let {
            InfoRow("Бофорт", "${it.beaufort} — ${WindPowerCalculator.beaufortName(it.beaufort)}")
            InfoRow("Стан турбіни", it.status.title)
        }
        InfoRow("Відкинуто хибних значень", "$rejected")
        Text("Поріг тривоги: ${threshold.toDouble().fmt()} м/с (застосовано: ${applied.toDouble().fmt()})")
        Slider(threshold, { vm.threshold.value = it }, valueRange = 5f..25f)
        if (alarm) Message("⚠ Вітер перевищив поріг ${applied.toDouble().fmt()} м/с", true)
        StartStop(running, vm::toggle)
        SectionCard("Журнал потоку") {
            events.forEach { Text(it.text, color = if (it.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedFlowApp(vm: AdvancedFlowViewModel = viewModel()) {
    val running by vm.running.collectAsStateWithLifecycle()
    val subs by vm.subscribers.collectAsStateWithLifecycle()
    val retries by vm.retries.collectAsStateWithLifecycle()
    val a by vm.panelA.collectAsStateWithLifecycle()
    val b by vm.panelB.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val check by vm.check.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Кілька джерел даних", "SharedFlow · zip · combine · retryWhen")
        StartStop(running, vm::toggle)
        SectionCard("Гарячий потік датчика A") {
            InfoRow("Підписників", "$subs")
            InfoRow("Повторних підключень", "$retries")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) { Text("Панель 1"); BigValue(a?.speed, a?.let { "№${it.seq}" } ?: "") }
                Column(Modifier.weight(1f)) { Text("Панель 2"); BigValue(b?.speed, b?.let { "№${it.seq}" } ?: "") }
            }
            OutlinedButton(onClick = vm::toggleSecondSubscriber, enabled = running) {
                Text(if (b == null) "Під'єднати панель 2" else "Від'єднати панель 2")
            }
        }
        check?.let { c ->
            SectionCard("combine: датчик + прогнозна модель") {
                InfoRow("Датчик / прогноз", "${c.sensor.fmt()} / ${c.forecast.fmt()} м/с")
                InfoRow("Відхилення від прогнозу", "${c.errorPercent.fmt(0)} %")
                LinearProgressIndicator(progress = { (kotlin.math.abs(c.errorPercent) / 50).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        SectionCard("zip: анемометри A і B (вимір до виміру)") {
            if (paired.isEmpty()) Text("Очікування пар вимірів…")
            paired.forEach { InfoRow("№${it.seq}: A ${it.a.fmt()} · B ${it.b.fmt()}", "Δ ${it.diff.fmt()} м/с") }
        }
        SectionCard("Події") { log.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkFlowApp(vm: HomeworkFlowViewModel = viewModel()) {
    val running by vm.running.collectAsStateWithLifecycle()
    val turbines by vm.turbines.collectAsStateWithLifecycle()
    val power by vm.stationPowerKw.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Моніторинг станції в реальному часі", "merge трьох турбін · контроль відсутності даних")
        StartStop(running, vm::toggle)
        SectionCard("Станція") {
            InfoRow("Потужність (за свіжими даними)", "${power.fmt(0)} кВт")
            InfoRow("Турбін на зв'язку", "${turbines.count { !it.stale }} з ${turbines.size}")
        }
        turbines.forEach { t ->
            SectionCard("Турбіна ${t.id}") {
                if (t.reading == null) Text("Даних ще немає")
                else {
                    InfoRow("Вітер", "${t.reading.speed.fmt()} м/с, №${t.reading.seq}")
                    InfoRow("Оновлено", timeFmt.format(Date(t.reading.time)))
                }
                if (t.stale && running) Message("Немає даних понад 3,5 с — перевірте зв'язок", true)
            }
        }
        SectionCard("Помилки та відновлення") {
            if (errors.isEmpty()) Text("Помилок немає")
            errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}
