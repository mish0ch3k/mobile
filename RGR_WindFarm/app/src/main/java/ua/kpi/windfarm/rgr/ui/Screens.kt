package ua.kpi.windfarm.rgr.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ua.kpi.windfarm.rgr.WindApp
import ua.kpi.windfarm.rgr.data.local.StationEntity
import ua.kpi.windfarm.rgr.domain.AlertType
import ua.kpi.windfarm.rgr.domain.TurbineState
import ua.kpi.windfarm.rgr.domain.WindPhysics
import ua.kpi.windfarm.rgr.notify.AlertNotifier
import ua.kpi.windfarm.rgr.ui.common.Chart
import ua.kpi.windfarm.rgr.ui.common.ChartSeries
import ua.kpi.windfarm.rgr.ui.common.Chips
import ua.kpi.windfarm.rgr.ui.common.InfoRow
import ua.kpi.windfarm.rgr.ui.common.Message
import ua.kpi.windfarm.rgr.ui.common.ScreenTitle
import ua.kpi.windfarm.rgr.ui.common.SectionCard
import ua.kpi.windfarm.rgr.ui.common.Sparkline
import ua.kpi.windfarm.rgr.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dhm = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("EE dd.MM", Locale("uk"))

@Composable
private fun container() = (LocalContext.current.applicationContext as WindApp).container

fun stateColor(s: TurbineState) = when (s) {
    TurbineState.IDLE -> Color(0xFF90A4AE); TurbineState.PARTIAL -> Color(0xFF1E88E5)
    TurbineState.RATED -> Color(0xFF43A047); TurbineState.STORM -> Color(0xFFE53935)
}

@Composable
private fun StationChips(list: List<StationEntity>, selected: Long, onSelect: (Long) -> Unit) =
    Chips(list.map { it.id }, selected, { id -> list.firstOrNull { it.id == id }?.name?.removeSuffix(" ВЕС") ?: "—" }, onSelect)

// ============================ ЗАВДАННЯ 1: ДАШБОРД ============================

/** Кругова шкала швидкості вітру з зонами роботи турбіни (ввімкнення, номінал, зупинка). */
@Composable
fun WindGauge(windMs: Double, dirDeg: Double, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(windMs.toFloat().coerceIn(0f, 30f), tween(700), label = "wind")
    val dir by animateFloatAsState(dirDeg.toFloat(), tween(700), label = "dir")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val needle = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.aspectRatio(1f)) {
        val sw = size.minDimension * 0.08f
        val d = size.minDimension - sw * 2
        val tl = Offset(sw, sw)
        val arc = androidx.compose.ui.geometry.Size(d, d)
        fun a(x: Float) = 135f + x / 30f * 270f
        drawArc(track, 135f, 270f, false, tl, arc, style = Stroke(sw))
        drawArc(Color(0xFF90A4AE), 135f, a(3f) - 135f, false, tl, arc, style = Stroke(sw))
        drawArc(Color(0xFF1E88E5), a(3f), a(12f) - a(3f), false, tl, arc, style = Stroke(sw))
        drawArc(Color(0xFF43A047), a(12f), a(25f) - a(12f), false, tl, arc, style = Stroke(sw))
        drawArc(Color(0xFFE53935), a(25f), a(30f) - a(25f), false, tl, arc, style = Stroke(sw))
        val r = d / 2 - sw
        val ang = Math.toRadians(a(v).toDouble())
        drawLine(needle, center, Offset(center.x + (r * cos(ang)).toFloat(), center.y + (r * sin(ang)).toFloat()), strokeWidth = 6f, cap = StrokeCap.Round)
        drawCircle(needle, 10f, center)
        // стрілка напрямку вітру (звідки дме) — маленький трикутник по краю
        val da = Math.toRadians(dir - 90.0)
        drawCircle(Color(0xFFEF6C00), sw * 0.45f, Offset(center.x + (r * 0.55f * cos(da)).toFloat(), center.y + (r * 0.55f * sin(da)).toFloat()))
    }
}

@Composable
fun DashboardScreen(onOpenStation: (Long) -> Unit) {
    val c = container()
    val vm: DashboardViewModel = viewModel(factory = viewModelFactory { initializer { DashboardViewModel(c.stations, c.measurements, c.telemetry.weatherBoost) } })
    val list by vm.stationList.collectAsStateWithLifecycle()
    val sel by vm.selected.collectAsStateWithLifecycle()
    val st by vm.state.collectAsStateWithLifecycle()
    val boost by vm.weatherBoost.collectAsStateWithLifecycle()
    val m = st.latest
    val color by animateColorAsState(stateColor(st.state), tween(600), label = "state")
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Моніторинг станції", "Телеметрія кожні 10 с · Room + Flow")
        StationChips(list, sel, vm::select)
        val s = st.station
        if (s == null || m == null) { Text("Очікування телеметрії…"); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                WindGauge(m.windMs, m.windDirDeg, Modifier.fillMaxWidth().testTag("wind_gauge"))
                Text("${m.windMs.fmt()} м/с", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Бофорт ${st.beaufort} · напрям ${m.windDirDeg.fmt(0)}°", style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${m.powerMw.fmt(1)} МВт", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = color)
                Text("з ${st.capacityMw.fmt(0)} МВт (${st.loadPercent.fmt(0)} %)", style = MaterialTheme.typography.bodySmall)
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth((st.loadPercent / 100).toFloat().coerceIn(0f, 1f)).height(10.dp).background(color))
                }
                Text(st.state.title, color = color, style = MaterialTheme.typography.titleSmall)
            }
        }
        SectionCard("Метеодані") {
            InfoRow("Температура", "${m.temperatureC.fmt()} °C")
            InfoRow("Тиск", "${m.pressureHpa.fmt(1)} гПа")
            InfoRow("Густина повітря", "${WindPhysics.airDensity(m.temperatureC, m.pressureHpa).fmt(3)} кг/м³")
            InfoRow("Оновлено", hm.format(Date(m.timestamp)))
        }
        SectionCard("Вітер, останні ${st.history.size} вимірів") { Sparkline(st.history.map { it.toFloat() }, Modifier.fillMaxWidth().height(60.dp)) }
        SectionCard("Потужність, МВт") { Sparkline(st.powerHistory.map { it.toFloat() }, Modifier.fillMaxWidth().height(60.dp), MaterialTheme.colorScheme.tertiary) }
        OutlinedButton({ onOpenStation(s.id) }) { Text("Параметри станції") }
        SectionCard("Демонстрація: зміна погоди") {
            Text("Зсув середнього вітру: ${if (boost >= 0) "+" else ""}${boost.fmt()} м/с", style = MaterialTheme.typography.bodySmall)
            Slider(boost.toFloat(), { vm.setBoost(Math.round(it * 2) / 2.0) }, valueRange = -8f..20f)
        }
    }
}

// ============================ ЗАВДАННЯ 2: КАЛЬКУЛЯТОР ============================

@Composable
private fun ParamSlider(title: String, value: Double, range: ClosedFloatingPointRange<Float>, digits: Int, unit: String, step: Double, onChange: (Double) -> Unit) {
    Text("$title: ${value.fmt(digits)} $unit", style = MaterialTheme.typography.bodySmall)
    Slider(value.toFloat(), { onChange(Math.round(it / step) * step) }, valueRange = range)
}

@Composable
fun CalculatorScreen() {
    val vm: CalculatorViewModel = viewModel()
    val i by vm.input.collectAsStateWithLifecycle()
    val r by vm.result.collectAsStateWithLifecycle()
    var sel by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenTitle("Розрахунок потужності", "P = ½·ρ·A·v³·Cp, ρ = p / (R·T)")
        SectionCard("Результат") {
            r.error?.let { Message(it, true) }
            InfoRow("Вітер на висоті маточини 100 м", "${r.hubWindMs.fmt()} м/с")
            InfoRow("Густина повітря", "${r.density.fmt(3)} кг/м³")
            InfoRow("Потужність турбіни", "${r.turbineKw.fmt(0)} кВт")
            InfoRow("Межа Бетца для цього ротора", "${r.betzKw.fmt(0)} кВт")
            InfoRow("Потужність станції", "${r.stationMw.fmt(1)} МВт")
            InfoRow("Виробіток за добу", "${r.dailyMwh.fmt(0)} МВт·год")
            Text(r.state.title, color = stateColor(r.state), style = MaterialTheme.typography.titleSmall)
        }
        if (r.curve.isNotEmpty()) SectionCard("Крива потужності, кВт") {
            Chart(listOf(ChartSeries(r.curve, Color(0xFF1565C0), false, "кВт")), (0..30).map { "$it" }, Modifier.fillMaxWidth().height(180.dp),
                sel, { sel = it }, tooltip = { k -> "${r.curve[k].fmt(0)} кВт при $k м/с" })
        }
        SectionCard("Вітер") {
            ParamSlider("Швидкість", i.windMs, 0f..30f, 1, "м/с", 0.5) { v -> vm.update { it.copy(windMs = v) } }
            ParamSlider("Висота анемометра", i.measuredAtM, 10f..150f, 0, "м", 5.0) { v -> vm.update { it.copy(measuredAtM = v) } }
            ParamSlider("Температура", i.temperatureC, -30f..40f, 0, "°C", 1.0) { v -> vm.update { it.copy(temperatureC = v) } }
            ParamSlider("Тиск", i.pressureHpa, 950f..1050f, 0, "гПа", 1.0) { v -> vm.update { it.copy(pressureHpa = v) } }
        }
        SectionCard("Турбіна і станція") {
            ParamSlider("Радіус ротора", i.rotorRadiusM, 20f..90f, 0, "м", 1.0) { v -> vm.update { it.copy(rotorRadiusM = v) } }
            ParamSlider("Коефіцієнт Cp", i.cp, 0.2f..0.6f, 2, "", 0.01) { v -> vm.update { it.copy(cp = v) } }
            ParamSlider("Номінальна потужність", i.ratedKw, 500f..8000f, 0, "кВт", 100.0) { v -> vm.update { it.copy(ratedKw = v) } }
            ParamSlider("Кількість турбін", i.turbines.toDouble(), 1f..200f, 0, "", 1.0) { v -> vm.update { it.copy(turbines = v.toInt()) } }
        }
    }
}

// ============================ ЗАВДАННЯ 3: ПРОГНОЗ ============================

@Composable
fun ForecastScreen() {
    val c = container()
    val vm: ForecastViewModel = viewModel(factory = viewModelFactory { initializer { ForecastViewModel(c.stations, c.forecast, c.telemetry.thresholds) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val list by vm.stationList.collectAsStateWithLifecycle()
    val sel by Selection.stationId.collectAsStateWithLifecycle()
    var selHour by remember { mutableStateOf<Int?>(null) }
    var selDay by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Прогноз виробітку", "Open-Meteo · вітер на 100 м · 3 доби")
        StationChips(list, sel, vm::select)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.refresh(true) }, enabled = !ui.loading) { Text(if (ui.loading) "Завантаження…" else "Оновити") }
            ui.fetchedAt?.let { Text("отримано ${dhm.format(Date(it))}", style = MaterialTheme.typography.labelSmall) }
        }
        ui.message?.let { Message(it, ui.isError) }
        val e = ui.energy
        if (e == null) { Text("Прогнозу ще немає — потрібне підключення до інтернету."); return@Column }
        SectionCard("Підсумок на ${e.hours.size} год") {
            InfoRow("Очікуваний виробіток", "${e.totalMwh.fmt(0)} МВт·год")
            InfoRow("КВВП", "${(e.capacityFactor * 100).fmt(1)} %")
            InfoRow("Годин штормової зупинки", "${e.stormHours}")
            InfoRow("Годин без генерації (штиль)", "${e.idleHours}")
        }
        SectionCard("Погодинно: потужність (МВт) і вітер (м/с ×10)") {
            val n = minOf(e.hours.size, 72)
            val h = e.hours.take(n)
            Chart(listOf(ChartSeries(h.map { it.powerMw }, Color(0xFF1565C0), true, "МВт"), ChartSeries(h.map { it.windMs * 10 }, Color(0xFF2E7D32), false, "Вітер ×10")),
                h.map { hm.format(Date(it.timeMs)) }, Modifier.fillMaxWidth().height(220.dp), selHour, { selHour = it },
                tooltip = { k -> "${h[k].powerMw.fmt(1)} МВт\nвітер ${h[k].windMs.fmt()} м/с\n${h[k].state.title}" })
        }
        val daily = e.daily()
        SectionCard("По добах, МВт·год") {
            Chart(listOf(ChartSeries(daily, Color(0xFFEF6C00), true, "МВт·год")), daily.indices.map { k -> dayFmt.format(Date(e.hours[k * 24].timeMs)) },
                Modifier.fillMaxWidth().height(160.dp), selDay, { selDay = it }, tooltip = { k -> "${daily[k].fmt(0)} МВт·год" })
        }
        if (ui.events.isNotEmpty()) SectionCard("Попередження з прогнозу") {
            ui.events.forEach { ev -> Text("• ${ev.message} (${dhm.format(Date(ev.timeMs))})", color = if (ev.type.critical) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

// ============================ ЗАВДАННЯ 4: ЖУРНАЛ І АНАЛІТИКА ============================

@Composable
fun AnalyticsScreen() {
    val c = container()
    val vm: AnalyticsViewModel = viewModel(factory = viewModelFactory { initializer { AnalyticsViewModel(c.stations, c.measurements) } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val list by vm.stationList.collectAsStateWithLifecycle()
    val sel by Selection.stationId.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val minWind by vm.minWind.collectAsStateWithLifecycle()
    var selBin by remember { mutableStateOf<Int?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Журнал і аналітика", "SQL-агрегація Room, розподіл Вейбулла")
            StationChips(list, sel, vm::select)
            Chips(Period.entries, period, { it.title }) { vm.period.value = it }
            Text("Мінімальний вітер у журналі: ${minWind.fmt()} м/с", style = MaterialTheme.typography.bodySmall)
            Slider(minWind.toFloat(), { vm.minWind.value = Math.round(it * 2) / 2.0 }, valueRange = 0f..20f)
            SectionCard("Показники за період") {
                InfoRow("Виробіток", "${ui.energyMwh.fmt(0)} МВт·год")
                InfoRow("КВВП", "${(ui.capacityFactor * 100).fmt(1)} %")
                InfoRow("Вітер: середній / макс.", "${ui.summary.mean.fmt()} / ${ui.summary.max.fmt()} м/с")
                InfoRow("Стандартне відхилення", "${ui.summary.std.fmt(2)} м/с")
                ui.weibull?.let { (k, cc) -> InfoRow("Вейбулл: k / c", "${k.fmt(2)} / ${cc.fmt(2)} м/с") }
                ui.annualMwh?.let { InfoRow("Оцінка річного виробітку", "${(it / 1000).fmt(1)} ГВт·год") }
            }
            SectionCard("Розподіл швидкостей вітру (крок 2 м/с)") {
                Chart(listOf(ChartSeries(ui.histogram.map { it.toDouble() }, Color(0xFF6A1B9A), true, "вимірів")), ui.histogram.indices.map { "${it * 2}" },
                    Modifier.fillMaxWidth().height(160.dp), selBin, { selBin = it }, tooltip = { k -> "${k * 2}–${k * 2 + 2} м/с: ${ui.histogram[k]}" })
            }
            Text("По добах", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth()) {
                listOf("Доба" to 1.2f, "Сер. м/с" to 1f, "Макс." to 0.8f, "МВт·год" to 1.1f).forEach { (t, w) -> Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium) }
            }
            HorizontalDivider()
        }
        items(ui.daily) { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(d.day.substring(5), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                Text(d.avgWind.fmt(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(d.maxWind.fmt(), Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
                Text(d.energyMwh.fmt(0), Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Останні виміри (${ui.measurements.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        items(ui.measurements.take(50), key = { it.id }) { m ->
            Text("${dhm.format(Date(m.timestamp))}  ${m.windMs.fmt()} м/с  ${m.powerMw.fmt(1)} МВт  ${m.temperatureC.fmt()} °C  ${if (m.source == "history") "архів" else "онлайн"}",
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ============================ ЗАВДАННЯ 5: ТРИВОГИ ============================

@Composable
fun AlertsScreen() {
    val c = container()
    val vm: AlertsViewModel = viewModel(factory = viewModelFactory { initializer { AlertsViewModel(c.alerts, c.telemetry.thresholds, c.stations) } })
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val active by vm.activeCount.collectAsStateWithLifecycle()
    val t by vm.thresholds.collectAsStateWithLifecycle()
    val names by vm.stationNames.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AlertNotifier(context).canPost()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Тривоги", "Активних: $active")
            if (!allowed && Build.VERSION.SDK_INT >= 33) Row(verticalAlignment = Alignment.CenterVertically) {
                Message("Сповіщення вимкнені", true)
                TextButton({ launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Дозволити") }
            }
            SectionCard("Пороги") {
                ParamSlider("Шторм від", t.stormMs, 15f..30f, 1, "м/с", 0.5) { v -> vm.setThresholds { it.copy(stormMs = v) } }
                ParamSlider("Пориви від", t.gustMs, 20f..40f, 1, "м/с", 0.5) { v -> vm.setThresholds { it.copy(gustMs = v) } }
                ParamSlider("Знижений виробіток нижче", t.lowOutputRatio * 100, 30f..95f, 0, "% очікуваного", 5.0) { v -> vm.setThresholds { it.copy(lowOutputRatio = v / 100) } }
                ParamSlider("Штиль для вікна ТО від", t.calmHours.toDouble(), 2f..24f, 0, "год", 1.0) { v -> vm.setThresholds { it.copy(calmHours = v.toInt()) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.acknowledgeAll() }) { Text("Підтвердити всі") }
                OutlinedButton({ vm.clear() }) { Text("Видалити підтверджені") }
            }
            if (alerts.isEmpty()) Text("Тривог немає. Для перевірки збільште «зсув вітру» на дашборді до +18 м/с.", style = MaterialTheme.typography.bodySmall)
        }
        items(alerts, key = { it.id }) { a ->
            val type = runCatching { AlertType.valueOf(a.type) }.getOrDefault(AlertType.SENSOR)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (a.acknowledged) MaterialTheme.colorScheme.surfaceVariant else if (type.critical) Color(0x33E53935) else Color(0x331E88E5)).padding(10.dp)) {
                Text("${names[a.stationId] ?: "Станція ${a.stationId}"}: ${type.title}", style = MaterialTheme.typography.titleSmall)
                Text(a.message, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(dhm.format(Date(a.timestamp)) + if (a.acknowledged) " · підтверджено" else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    if (!a.acknowledged) TextButton({ vm.acknowledge(a.id) }) { Text("Підтвердити") }
                }
            }
        }
    }
}

// ============================ ПАРАМЕТРИ СТАНЦІЇ ============================

@Composable
fun StationScreen(id: Long, onBack: () -> Unit) {
    val c = container()
    val vm: StationViewModel = viewModel(key = "station$id", factory = viewModelFactory { initializer { StationViewModel(c.stations, id) } })
    val s by vm.station.collectAsStateWithLifecycle()
    val st = s ?: return
    var turbines by rememberSaveable(st.id) { mutableIntStateOf(st.turbines) }
    var avail by rememberSaveable(st.id) { mutableFloatStateOf(st.availability.toFloat()) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle(st.name, "${st.latitude.fmt(2)}° пн. ш., ${st.longitude.fmt(2)}° сх. д.")
        SectionCard("Турбіни") {
            InfoRow("Номінальна потужність", "${st.ratedKw.fmt(0)} кВт")
            InfoRow("Радіус ротора / Cp", "${st.rotorRadiusM.fmt(0)} м / ${st.cp.fmt(2)}")
            InfoRow("Встановлена потужність", "${(st.ratedKw * turbines / 1000).fmt(1)} МВт")
        }
        Text("Кількість турбін: $turbines")
        Slider(turbines.toFloat(), { turbines = it.toInt() }, valueRange = 1f..150f)
        Text("Готовність (справні турбіни): ${(avail * 100).toInt()} %")
        Slider(avail, { avail = Math.round(it * 100) / 100f }, valueRange = 0.5f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ vm.save(turbines, avail.toDouble()); onBack() }) { Text("Зберегти") }
            OutlinedButton(onBack) { Text("Скасувати") }
        }
        Text("Параметри зберігаються в Room і одразу враховуються в моніторингу та прогнозі.", style = MaterialTheme.typography.bodySmall)
    }
}

/** Маленький ротор для заголовка (без анімації — декоративний). */
@Composable
fun RotorIcon(modifier: Modifier = Modifier.size(28.dp), color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.rotate(15f)) {
        val r = size.minDimension / 2
        repeat(3) { i ->
            val a = Math.toRadians(i * 120.0 - 90)
            drawLine(color, center, Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat()), strokeWidth = r * 0.22f, cap = StrokeCap.Round)
        }
    }
}

