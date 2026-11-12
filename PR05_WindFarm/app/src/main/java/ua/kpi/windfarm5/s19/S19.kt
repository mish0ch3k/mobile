package ua.kpi.windfarm5.s19

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm5.charts.CategoryValue
import ua.kpi.windfarm5.charts.ChartData
import ua.kpi.windfarm5.charts.HourPoint
import ua.kpi.windfarm5.charts.SeriesStats
import ua.kpi.windfarm5.charts.Viewport
import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.WindPowerCalculator
import ua.kpi.windfarm5.ui.common.Chart
import ua.kpi.windfarm5.ui.common.ChartSeries
import ua.kpi.windfarm5.ui.common.Chips
import ua.kpi.windfarm5.ui.common.InfoRow
import ua.kpi.windfarm5.ui.common.ScreenTitle
import ua.kpi.windfarm5.ui.common.SectionCard
import ua.kpi.windfarm5.ui.common.fmt
import kotlin.random.Random

private val Energy = Color(0xFF1565C0)
private val WindC = Color(0xFF2E7D32)
private val Avg = Color(0xFFEF6C00)

/** Кнопки масштабу (щипок працює на сенсорному екрані, кнопки — і в емуляторі). */
@Composable
private fun ZoomBar(vp: Viewport, onChange: (Viewport) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton({ onChange(vp.pan(-maxOf(1, vp.size / 3))) }) { Text("◀") }
        OutlinedButton({ onChange(vp.zoom(1.5f)) }) { Text("+") }
        OutlinedButton({ onChange(vp.zoom(1 / 1.5f)) }) { Text("−") }
        OutlinedButton({ onChange(vp.pan(maxOf(1, vp.size / 3))) }) { Text("▶") }
        Text("${vp.size} год", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatsCard(s: SeriesStats, labels: List<String>, unit: String) {
    SectionCard("Показники інтервалу") {
        InfoRow("Сумарно", "${s.total.fmt(0)} $unit")
        InfoRow("Середнє", "${s.average.fmt(1)} $unit/год")
        InfoRow("Пік", "${s.peak.fmt(1)} $unit/год · ${labels.getOrElse(s.peakIndex) { "—" }}")
        InfoRow("Мінімум", "${s.min.fmt(1)} $unit/год")
    }
}

// ============================ БАЗОВИЙ ============================
/** Лінійний графік виробітку за останні 24 год; нова точка приходить кожні 2 с (StateFlow). */
class BasicChartViewModel : ViewModel() {
    val points = MutableStateFlow(ChartData.generation(24))
    val selected = MutableStateFlow<Int?>(null)
    private val spec = TurbineSpec()

    init {
        viewModelScope.launch {
            val rnd = Random(3)
            while (isActive) {
                delay(2000)
                points.update { list ->
                    val last = list.last()
                    val w = (last.wind + rnd.nextDouble(-1.5, 1.5)).coerceIn(0.0, 28.0)
                    val p = HourPoint(last.hour + 1, Math.round(w * 10) / 10.0, WindPowerCalculator.turbinePowerKw(w, spec) * 60 / 1000.0)
                    (list + p).takeLast(24) // ковзне вікно — графік «їде» вліво
                }
            }
        }
    }
}

@Composable
fun BasicChartApp(vm: BasicChartViewModel = viewModel()) {
    val points by vm.points.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Виробіток станції", "60 турбін · МВт·год за годину · оновлення щосекунди")
        Chart(listOf(ChartSeries(points.map { it.energyMwh }, Energy, asBars = false, name = "Виробіток, МВт·год")),
            labels = points.map { "%02d:00".format(it.hour % 24) }, selected = selected, onSelect = { vm.selected.value = it },
            modifier = Modifier.fillMaxWidth().height(240.dp))
        val last = points.last()
        InfoRow("Остання година", "${last.energyMwh.fmt(1)} МВт·год при ${last.wind.fmt()} м/с")
        Text("Натисніть на графік, щоб побачити значення точки.", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ СЕРЕДНІЙ ============================
enum class StationMetric(val title: String) { ENERGY("Виробіток, МВт·год"), FACTOR("КВВП, %") }

class MediumChartViewModel : ViewModel() {
    val stations: List<CategoryValue> = ChartData.stations()
    val metric = MutableStateFlow(StationMetric.ENERGY)
    val selected = MutableStateFlow<Int?>(null)
    val sorted = MutableStateFlow(false)
    val view: StateFlow<List<CategoryValue>> = combine(metric, sorted) { m, s ->
        if (!s) stations else stations.sortedByDescending { if (m == StationMetric.ENERGY) it.value else it.capacityFactor }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, stations)
}

@Composable
fun MediumChartApp(vm: MediumChartViewModel = viewModel()) {
    val metric by vm.metric.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val sorted by vm.sorted.collectAsStateWithLifecycle()
    val st by vm.view.collectAsStateWithLifecycle()
    val values = st.map { if (metric == StationMetric.ENERGY) it.value else it.capacityFactor }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Порівняння станцій", "Добовий виробіток ВЕС України (модель)")
        Chips(StationMetric.entries, metric, { it.title }) { vm.metric.value = it; vm.selected.value = null }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(sorted, { vm.sorted.value = it; vm.selected.value = null }); Text("Сортувати за спаданням")
        }
        Chart(listOf(ChartSeries(values, Energy, asBars = true, name = metric.title)),
            labels = st.map { it.name.take(6) }, selected = selected, onSelect = { vm.selected.value = it },
            modifier = Modifier.fillMaxWidth().height(260.dp),
            tooltip = { i -> st[i].let { "${it.name}\n${it.value.fmt(0)} МВт·год\nПотужність ${it.capacityMw.fmt(0)} МВт\nКВВП ${it.capacityFactor.fmt(1)} %" } })
        selected?.let { i -> SectionCard(st[i].name) {
            InfoRow("Виробіток за добу", "${st[i].value.fmt(0)} МВт·год")
            InfoRow("Встановлена потужність", "${st[i].capacityMw.fmt(0)} МВт")
            InfoRow("КВВП", "${st[i].capacityFactor.fmt(1)} %")
        } }
        Text("Натисніть на стовпчик для підказки з деталями.", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ПРОСУНУТИЙ ============================
enum class Interval(val title: String, val hours: Int) { DAY("Доба", 24), THREE("3 доби", 72), WEEK("Тиждень", 168) }

/** Стан комбінованого графіка: інтервал, видиме вікно (масштаб), вибрана точка. */
class AdvancedChartViewModel : ViewModel() {
    private val all = ChartData.generation(168)
    val interval = MutableStateFlow(Interval.DAY)
    val viewport = MutableStateFlow(Viewport.all(24))
    val selected = MutableStateFlow<Int?>(null)
    val showWind = MutableStateFlow(true)

    /** Точки поточного інтервалу (останні N годин). */
    val data: StateFlow<List<HourPoint>> = combine(interval, viewport) { iv, vp ->
        val list = all.takeLast(iv.hours)
        // interval і viewport змінюються двома присвоєннями — на мить вони можуть не збігатися, тож межі обмежуємо
        val start = vp.start.coerceIn(0, list.size)
        list.subList(start, vp.end.coerceIn(start, list.size))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, all.takeLast(24))

    fun setInterval(i: Interval) { viewport.value = Viewport.all(i.hours); interval.value = i; selected.value = null }
    fun setViewport(v: Viewport) { viewport.value = v; selected.value = null }
}

@Composable
fun AdvancedChartApp(vm: AdvancedChartViewModel = viewModel()) {
    val interval by vm.interval.collectAsStateWithLifecycle()
    val vp by vm.viewport.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val showWind by vm.showWind.collectAsStateWithLifecycle()
    val energy = data.map { it.energyMwh }
    val labels = data.map { it.label }
    // Вітер (м/с) масштабується ×5, щоб лягти на ту саму вісь, що й МВт·год; у підказці — справжнє значення
    val series = buildList {
        add(ChartSeries(energy, Energy, asBars = true, name = "Виробіток"))
        add(ChartSeries(ChartData.movingAverage(energy, 6), Avg, asBars = false, name = "Середнє (6 год)"))
        if (showWind) add(ChartSeries(data.map { it.wind * 5 }, WindC, asBars = false, name = "Вітер ×5"))
    }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Аналітика виробітку")
        Chips(Interval.entries, interval, { it.title }, vm::setInterval)
        ZoomBar(vp, vm::setViewport)
        Chart(series, labels, Modifier.fillMaxWidth().height(260.dp), selected, { vm.selected.value = it },
            onZoom = { f, focus -> vm.setViewport(vp.zoom(f, focus)) },
            tooltip = { i -> "Виробіток: ${energy[i].fmt(1)} МВт·год\nВітер: ${data[i].wind.fmt()} м/с\nСереднє 6 год: ${ChartData.movingAverage(energy, 6)[i].fmt(1)}" })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(showWind, { vm.showWind.value = it }); Text("Лінія вітру")
        }
        Text("■ виробіток  — середнє  — вітер", style = MaterialTheme.typography.labelSmall)
        StatsCard(ChartData.stats(energy), labels, "МВт·год")
    }
}

// ============================ ДОМАШНЄ ============================
/**
 * Виробіток ВЕС за тиждень у реальному часі: щогодинні точки (лінія) та добові підсумки
 * (стовпчики); масштабування, підказки, оновлення кожні 3 с.
 */
class HomeworkChartViewModel : ViewModel() {
    val points = MutableStateFlow(ChartData.generation(168, seed = 5))
    val viewport = MutableStateFlow(Viewport(168 - 48, 168, 168))
    val selectedHour = MutableStateFlow<Int?>(null)
    val selectedDay = MutableStateFlow<Int?>(null)
    private val spec = TurbineSpec()

    init {
        viewModelScope.launch {
            val rnd = Random(11)
            while (isActive) {
                delay(3000)
                // остання година «доростає»: телеметрія уточнює виробіток
                points.update { l ->
                    val last = l.last()
                    val w = (last.wind + rnd.nextDouble(-0.8, 0.8)).coerceIn(0.0, 28.0)
                    l.dropLast(1) + last.copy(wind = Math.round(w * 10) / 10.0, energyMwh = WindPowerCalculator.turbinePowerKw(w, spec) * 60 / 1000.0)
                }
            }
        }
    }
}

@Composable
fun HomeworkChartApp(vm: HomeworkChartViewModel = viewModel()) {
    val points by vm.points.collectAsStateWithLifecycle()
    val vp by vm.viewport.collectAsStateWithLifecycle()
    val selH by vm.selectedHour.collectAsStateWithLifecycle()
    val selD by vm.selectedDay.collectAsStateWithLifecycle()
    val visible = points.subList(vp.start, vp.end)
    val daily = ChartData.aggregate(points, 24)
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Виробіток за тиждень", "Погодинно (лінія) і по добах (стовпчики)")
        ZoomBar(vp) { vm.viewport.value = it; vm.selectedHour.value = null }
        Chart(listOf(ChartSeries(visible.map { it.energyMwh }, Energy, false, "МВт·год"), ChartSeries(visible.map { it.wind }, WindC, false, "Вітер, м/с")),
            visible.map { it.label }, Modifier.fillMaxWidth().height(220.dp), selH, { vm.selectedHour.value = it },
            onZoom = { f, focus -> vm.viewport.value = vp.zoom(f, focus) })
        Chart(listOf(ChartSeries(daily, Avg, true, "МВт·год за добу")), List(daily.size) { "Д${it + 1}" },
            Modifier.fillMaxWidth().height(180.dp), selD, { vm.selectedDay.value = it })
        StatsCard(ChartData.stats(visible.map { it.energyMwh }), visible.map { it.label }, "МВт·год")
    }
}
