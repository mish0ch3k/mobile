package ua.kpi.windfarm2.s8

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm2.data.MeasurementValidator
import ua.kpi.windfarm2.data.RepoResult
import ua.kpi.windfarm2.data.SortOrder
import ua.kpi.windfarm2.data.WindMeasurement
import ua.kpi.windfarm2.domain.TurbineSpec
import ua.kpi.windfarm2.domain.WindPowerCalculator
import ua.kpi.windfarm2.ui.common.FormField
import ua.kpi.windfarm2.ui.common.InfoRow
import ua.kpi.windfarm2.ui.common.ScreenTitle
import ua.kpi.windfarm2.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

/**
 * Форма введення виміру (перевикористовується на всіх рівнях).
 * [initial] — запис для редагування; null — новий запис.
 */
@Composable
private fun MeasurementForm(initial: WindMeasurement? = null, onSave: (WindMeasurement) -> Unit, onCancel: (() -> Unit)? = null) {
    var turbine by remember(initial) { mutableStateOf(initial?.turbine ?: "ВТ-01") }
    var speed by remember(initial) { mutableStateOf(initial?.windSpeed?.toString() ?: "") }
    var temp by remember(initial) { mutableStateOf(initial?.temperature?.toString() ?: "15") }
    var submitted by remember(initial) { mutableStateOf(false) }
    val errors = listOf(MeasurementValidator.turbine(turbine), MeasurementValidator.speed(speed), MeasurementValidator.temperature(temp))
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FormField("Турбіна", turbine, { turbine = it }, errors[0], showError = submitted, modifier = Modifier.weight(1f))
            FormField("Вітер, м/с", speed, { speed = it }, errors[1], showError = submitted, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            FormField("t, °C", temp, { temp = it }, errors[2], showError = submitted, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(0.8f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                submitted = true
                if (errors.all { it == null }) {
                    val m = (initial ?: WindMeasurement(turbine = "", windSpeed = 0.0, temperature = 0.0)).copy(
                        turbine = turbine.trim(),
                        windSpeed = speed.trim().replace(',', '.').toDouble(),
                        temperature = temp.trim().replace(',', '.').toDouble()
                    )
                    onSave(m)
                    if (initial == null) { speed = ""; submitted = false }
                }
            }) { Text(if (initial == null) "Додати" else "Зберегти") }
            if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Скасувати") }
        }
    }
}

/** Елемент списку виміру з розрахованою потужністю. */
@Composable
private fun MeasurementItem(m: WindMeasurement, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val p = WindPowerCalculator.turbinePowerKw(m.windSpeed, TurbineSpec(), m.temperature)
    ListItem(
        headlineContent = { Text("${m.turbine}: ${m.windSpeed.fmt()} м/с, ${m.temperature.fmt()} °C") },
        supportingContent = { Text("${p.fmt(0)} кВт · ${timeFmt.format(Date(m.timestamp))} · ${m.source}") },
        trailingContent = {
            Row {
                if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Редагувати") }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Видалити") }
            }
        }
    )
}

// ========================= БАЗОВИЙ =========================
/** Room + Repository + ViewModel: вставка й отримання записів. */
@Composable
fun BasicRoomApp(vm: BasicRoomViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Журнал вимірів (Room)", "Записів у БД: ${items.size}")
        MeasurementForm(onSave = { vm.add(it) })
        LazyColumn { items(items, key = { it.id }) { MeasurementItem(it) } }
    }
}

// ========================= СЕРЕДНІЙ =========================
/** Два джерела (локальне + «віддалене»), оновлення, видалення, пошук; реактивний список. */
@Composable
fun MediumRoomApp(vm: MediumRoomViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<WindMeasurement?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Виміри: локальні + сервер")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сервер доступний"); Switch(online, vm::setOnline)
            Button(onClick = { vm.sync() }) { Text("Синхронізувати") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        MeasurementForm(editing, onSave = { m -> if (editing == null) vm.add(m) else vm.update(m); editing = null },
            onCancel = if (editing != null) ({ editing = null }) else null)
        FormField("Пошук за турбіною", query, { vm.query.value = it }, helper = "Знайдено: ${items.size}")
        LazyColumn { items(items, key = { it.id }) { m -> MeasurementItem(m, { editing = m }, { vm.delete(m) }) } }
    }
}

// ========================= ПРОСУНУТИЙ =========================
/** Кешування, обробка помилок, агрегація, фільтрація і сортування, адаптивний UI. */
@Composable
fun AdvancedRoomApp(vm: AdvancedRoomViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val minSpeed by vm.minSpeed.collectAsStateWithLifecycle()
    val order by vm.order.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf(true) }

    val controls = @Composable {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("Статистика станції", style = MaterialTheme.typography.titleSmall)
            InfoRow("Записів", "${stats.count}")
            InfoRow("Середній / макс. вітер", "${stats.avgSpeed.fmt()} / ${stats.maxSpeed.fmt()} м/с")
            InfoRow("Середня потужність", "${stats.avgPowerKw.fmt(0)} кВт")
            InfoRow("Вимірів у бурю", "${stats.storms}")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Онлайн"); Switch(online, { online = it; vm.setOnline(it) })
                OutlinedButton(onClick = { vm.refresh(true) }) { Text("Оновити") }
                OutlinedButton(onClick = { vm.clear() }) { Text("Очистити") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let {
                when (it) {
                    is RepoResult.Ok -> Text(it.message, color = MaterialTheme.colorScheme.tertiary)
                    is RepoResult.Fail -> Text(it.message, color = MaterialTheme.colorScheme.error)
                }
            }
        } }
        MeasurementForm(onSave = { vm.add(it) })
    }
    val filters = @Composable {
        Column {
            FormField("Пошук за турбіною", query, { vm.query.value = it })
            Text("Мінімальний вітер: ${minSpeed.toDouble().fmt()} м/с")
            Slider(minSpeed, { vm.minSpeed.value = it }, valueRange = 0f..30f)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortOrder.entries.forEach { FilterChip(it == order, { vm.order.value = it }, { Text(it.title) }) }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
        if (maxWidth >= 600.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { controls() }
            Column(Modifier.weight(1f)) {
                filters()
                LazyColumn { items(items, key = { it.id }) { m -> MeasurementItem(m, onDelete = { vm.delete(m) }) } }
            }
        } else {
            // На телефоні весь екран — один LazyColumn, тож журнал прокручується разом із панеллю керування
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { Column { controls() } } // Column: controls містить кілька елементів
                item { filters() }
                items(items, key = { it.id }) { m -> MeasurementItem(m, onDelete = { vm.delete(m) }) }
            }
        }
    }
}

// ========================= ДОМАШНЄ =========================
/** CRUD з валідацією: додавання, отримання, оновлення та видалення вимірів. */
@Composable
fun HomeworkRoomApp(vm: MediumRoomViewModel = viewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<WindMeasurement?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Параметри вітрової станції", "Room · MVVM · ${items.size} записів")
        MeasurementForm(editing, onSave = { m -> if (editing == null) vm.add(m) else vm.update(m); editing = null },
            onCancel = if (editing != null) ({ editing = null }) else null)
        LazyColumn { items(items, key = { it.id }) { m -> MeasurementItem(m, { editing = m }, { vm.delete(m) }) } }
    }
}
