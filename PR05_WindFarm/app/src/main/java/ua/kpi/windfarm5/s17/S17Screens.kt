package ua.kpi.windfarm5.s17

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorSample
import ua.kpi.windfarm5.sensors.Sensors
import ua.kpi.windfarm5.ui.common.Chips
import ua.kpi.windfarm5.ui.common.InfoRow
import ua.kpi.windfarm5.ui.common.Message
import ua.kpi.windfarm5.ui.common.MultiChips
import ua.kpi.windfarm5.ui.common.ScreenTitle
import ua.kpi.windfarm5.ui.common.SectionCard
import ua.kpi.windfarm5.ui.common.Sparkline
import ua.kpi.windfarm5.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun Float.f(d: Int = 2) = toDouble().fmt(d)

/** Значення сенсора текстом: для векторних — X/Y/Z і модуль. */
private fun SensorSample.text(): String =
    if (kind.vector) "X ${values[0].f()}  Y ${values[1].f()}  Z ${values[2].f()}  |a| ${scalar.f()} ${kind.unit}"
    else "${scalar.f(1)} ${kind.unit}"

// ============================ БАЗОВИЙ ============================
/** Один сенсор (акселерометр): поточні значення, графік модуля і таблиця останніх показів. */
@Composable
fun BasicSensorApp(vm: BasicSensorViewModel = viewModel()) {
    val latest by vm.latest.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val table by vm.table.collectAsStateWithLifecycle()
    val k = SensorKind.ACCELEROMETER
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Вібрація башти", "Акселерометр телефону як датчик вібрації турбіни")
            Message(errors[k], isError = true)
            val s = latest[k]
            SectionCard("Поточні значення") {
                InfoRow("X", "${s?.values?.get(0)?.f() ?: "—"} м/с²")
                InfoRow("Y", "${s?.values?.get(1)?.f() ?: "—"} м/с²")
                InfoRow("Z", "${s?.values?.get(2)?.f() ?: "—"} м/с²")
                InfoRow("Модуль |a|", "${s?.scalar?.f() ?: "—"} м/с²")
            }
            SectionCard("Модуль прискорення, останні ${history[k]?.size ?: 0} показів") {
                Sparkline(history[k].orEmpty(), Modifier.fillMaxWidth().height(100.dp))
            }
            Text("Останні покази", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth()) {
                listOf("Час" to 1.2f, "X" to 1f, "Y" to 1f, "Z" to 1f, "|a|" to 1f).forEach { (t, w) ->
                    Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider()
        }
        items(table) { s -> // без key: два покази можуть мати однаковий час у мс
            Row(Modifier.fillMaxWidth()) {
                Text(timeFmt.format(Date(s.timestampMs)), Modifier.weight(1.2f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                (0..2).forEach { i -> Text(s.values[i].f(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Text(s.scalar.f(), Modifier.weight(1f), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
/** Кілька сенсорів одночасно: картка з описом, значеннями та міні-графіком для кожного. */
@Composable
fun MediumSensorApp(vm: MediumSensorViewModel = viewModel()) {
    val latest by vm.latest.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val kinds = listOf(SensorKind.ACCELEROMETER, SensorKind.GYROSCOPE, SensorKind.MAGNETOMETER, SensorKind.PRESSURE)
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("Сенсори станції", "Оновлення в реальному часі") }
        items(kinds) { k ->
            SensorCard(k, latest[k], history[k].orEmpty(), errors[k])
        }
    }
}

@Composable
private fun SensorCard(k: SensorKind, s: SensorSample?, history: List<Float>, error: String?) {
    SectionCard(k.title) {
        Text(k.description, style = MaterialTheme.typography.bodySmall)
        when {
            error != null -> Message(error, isError = true)
            s == null -> Text("Очікування даних…", style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(s.text(), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Sparkline(history, Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp))
            }
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
/** Панель: вибір сенсорів, пороги сповіщень, журнал із фільтрами та сортуванням. */
@Composable
fun AdvancedSensorApp(vm: AdvancedSensorViewModel = viewModel()) {
    val shown by vm.shown.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val thresholds by vm.thresholds.collectAsStateWithLifecycle()
    val alarms by vm.alarms.collectAsStateWithLifecycle()
    val table by vm.table.collectAsStateWithLifecycle()
    val filterKinds by vm.filterKinds.collectAsStateWithLifecycle()
    val minValue by vm.minValue.collectAsStateWithLifecycle()
    val onlyExceeded by vm.onlyExceeded.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Панель моніторингу сенсорів")
            Text("Показувати сенсори:", style = MaterialTheme.typography.labelLarge)
            MultiChips(vm.availableKinds, shown, { it.title }, vm::toggleShown)
            if (vm.availableKinds.isEmpty()) Message("На пристрої немає підтримуваних сенсорів", isError = true)
        }
        items(vm.availableKinds.filter { it in shown }) { k ->
            val limit = thresholds[k] ?: 0f
            val s = latest[k]
            val over = s != null && s.scalar > limit
            SectionCard(k.title) {
                Text(s?.text() ?: "Очікування даних…", fontFamily = FontFamily.Monospace,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Sparkline(history[k].orEmpty(), Modifier.fillMaxWidth().height(40.dp),
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                // Поріг сповіщення: верхня межа слайдера — до половини діапазону сенсора (для тиску — повний діапазон)
                val top = if (k == SensorKind.PRESSURE) k.validRange.endInclusive else minOf(k.validRange.endInclusive, maxOf(limit * 3, 10f))
                Text("Поріг: ${limit.f(1)} ${k.unit}", style = MaterialTheme.typography.bodySmall)
                Slider(limit, { vm.setThreshold(k, it) }, valueRange = (if (k == SensorKind.PRESSURE) 900f else 0f)..top)
            }
        }
        item {
            if (alarms.isNotEmpty()) SectionCard("Перевищення порогів") {
                alarms.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Text("Журнал показів", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text("Тип сенсора (порожньо — усі):", style = MaterialTheme.typography.labelMedium)
            MultiChips(shown.toList().sortedBy { it.ordinal }, filterKinds, { it.title }) { k ->
                vm.filterKinds.value = if (k in filterKinds) filterKinds - k else filterKinds + k
            }
            Text("Мінімальне |значення|: ${minValue.f(1)}", style = MaterialTheme.typography.labelMedium)
            Slider(minValue, { vm.minValue.value = it }, valueRange = 0f..50f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(onlyExceeded, { vm.onlyExceeded.value = it }); Text("Лише перевищення")
            }
            Chips(RowSort.entries, sort, { "↓ ${it.title}" }) { vm.sort.value = it }
            HorizontalDivider(Modifier.padding(top = 4.dp))
            if (table.isEmpty()) Text("Немає записів за фільтрами", style = MaterialTheme.typography.bodySmall)
        }
        items(table, key = { "${it.kind}-${it.time}" }) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(timeFmt.format(Date(r.time)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(r.kind.title, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
                Text("${r.value.f()} ${r.kind.unit}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall,
                    color = if (r.exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
/** Метеосенсори телефону → густина повітря → потужність турбіни за поточного вітру. */
@Composable
fun HomeworkSensorApp(vm: HomeworkSensorViewModel = viewModel()) {
    val air by vm.air.collectAsStateWithLifecycle()
    val wind by vm.wind.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Метеостанція ВЕС", "Барометр, термометр і гігрометр телефону")
            SectionCard("Атмосфера") {
                InfoRow("Тиск", air.pressureHpa?.let { "${it.f(1)} гПа" } ?: "немає сенсора (1013.3)")
                InfoRow("Температура", air.temperatureC?.let { "${it.f(1)} °C" } ?: "немає сенсора (15.0)")
                InfoRow("Вологість", air.humidity?.let { "${it.f(0)} %" } ?: "немає сенсора")
                InfoRow("Густина повітря ρ", "${air.density.fmt(3)} кг/м³")
                Sparkline(history[SensorKind.PRESSURE].orEmpty(), Modifier.fillMaxWidth().height(40.dp))
                Text("Тиск, останні покази", style = MaterialTheme.typography.labelSmall)
            }
            SectionCard("Турбіна 2 МВт") {
                Text("Швидкість вітру: ${wind.f(1)} м/с")
                Slider(wind, { vm.wind.value = it }, valueRange = 0f..30f)
                InfoRow("Потужність за фактичної ρ", "${air.powerKw.fmt(0)} кВт")
                InfoRow("За стандартної атмосфери", "${air.standardPowerKw.fmt(0)} кВт")
                InfoRow("Відхилення", "${if (air.deltaPercent >= 0) "+" else ""}${air.deltaPercent.fmt(1)} %")
            }
            if (air.icing) Message("Ризик обмерзання лопатей: мороз і висока вологість", isError = true)
            errors.values.forEach { Message("$it — використано стандартне значення", isError = false) }
            Text("Для емулятора: ⋮ → Extended controls → Virtual sensors → Environment.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Опис апаратних сенсорів (для довідки на екрані налаштувань). */
fun sensorInfo(): List<String> = SensorKind.entries.map { k -> "${k.title}: ${Sensors.repo.describe(k) ?: "відсутній"}" }
