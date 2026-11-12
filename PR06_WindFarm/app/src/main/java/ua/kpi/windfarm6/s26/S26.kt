package ua.kpi.windfarm6.s26

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import ua.kpi.windfarm6.monitor.ResourceMath
import ua.kpi.windfarm6.monitor.ResourceMonitor
import ua.kpi.windfarm6.monitor.ResourceSnapshot
import ua.kpi.windfarm6.monitor.ThemeMode
import ua.kpi.windfarm6.monitor.ThemePrefs
import ua.kpi.windfarm6.ui.common.Chips
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.Message
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import ua.kpi.windfarm6.ui.common.Sparkline
import ua.kpi.windfarm6.ui.common.fmt

private fun Float.f(d: Int = 0) = toDouble().fmt(d)
private val levelColors = listOf(Color(0xFF43A047), Color(0xFFFFB300), Color(0xFFE53935))

/**
 * Моніторинг ресурсів: знімки з ResourceMonitor збираються в StateFlow (останній + історія).
 * Опитування працює, поки є підписники (WhileSubscribed), — на іншому екрані воно зупиняється.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val monitor = ResourceMonitor(app)
    val periodMs = MutableStateFlow(1000L)
    val history = MutableStateFlow<List<ResourceSnapshot>>(emptyList())
    val snapshot: StateFlow<ResourceSnapshot?> = monitor.snapshots { periodMs.value }
        .onEach { s -> history.update { (it + s).takeLast(60) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), null)

    /** Навантаження CPU: [threads] корутин на Dispatchers.Default рахують 8 с — видно в показниках. */
    val loadThreads = MutableStateFlow(0)
    private var load: List<Job> = emptyList()
    fun startLoad(threads: Int) {
        stopLoad()
        loadThreads.value = threads
        load = List(threads) {
            viewModelScope.launch(Dispatchers.Default) {
                val end = System.currentTimeMillis() + 8000
                var x = 0.0
                while (isActive && System.currentTimeMillis() < end) { x += Math.sqrt(x + 1.0) }
            }
        }
        viewModelScope.launch { load.forEach { it.join() }; loadThreads.value = 0 }
    }
    fun stopLoad() { load.forEach { it.cancel() }; loadThreads.value = 0 }
}

private fun cpuOf(s: ResourceSnapshot) = s.cpuPercent ?: s.appCpuPercent

// ---------- компоненти ----------

/** Кільцевий індикатор: колір залежить від рівня (зелений / жовтий / червоний), дуга анімується. */
@Composable
fun RingGauge(percent: Float, label: String, modifier: Modifier = Modifier, invert: Boolean = false) {
    val p by animateFloatAsState(percent.coerceIn(0f, 100f), tween(600), label = "ring")
    val lvl = ResourceMath.level(if (invert) 100 - percent else percent)
    val c by animateColorAsState(levelColors[lvl], tween(600), label = "ringColor")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp)) {
            val sw = size.minDimension * 0.12f
            val arc = Size(size.minDimension - sw, size.minDimension - sw)
            val tl = Offset(sw / 2, sw / 2)
            drawArc(track, -90f, 360f, false, tl, arc, style = Stroke(sw))
            drawArc(c, -90f, 360f * p / 100, false, tl, arc, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("${p.f()} %", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Сегментна кольорова шкала (10 сегментів) — власна візуалізація для домашнього завдання. */
@Composable
fun SegmentScale(percent: Float, modifier: Modifier = Modifier, invert: Boolean = false) {
    val p by animateFloatAsState(percent.coerceIn(0f, 100f), tween(500), label = "seg")
    val off = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val n = 10; val gap = 4.dp.toPx(); val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val segPct = (i + 1) * 10f
            val on = p >= segPct - 5
            val lvl = ResourceMath.level(if (invert) 100 - segPct else segPct)
            drawRoundRect(if (on) levelColors[lvl] else off, Offset(i * (w + gap), 0f), Size(w, size.height), CornerRadius(4.dp.toPx()))
        }
    }
}

@Composable
private fun ThemeSwitcher() {
    val mode by ThemePrefs.mode.collectAsStateWithLifecycle()
    Chips(ThemeMode.entries, mode, { it.title }, ThemePrefs::set)
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicMonitorApp(vm: MonitorViewModel = viewModel()) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Ресурси пристрою", "Оновлення щосекунди у фоновому потоці")
        val x = s ?: run { Text("Збір даних…"); return@Column }
        Text(if (x.cpuPercent != null) "CPU (система): ${x.cpuPercent.f()} %" else "CPU застосунку: ${x.appCpuPercent.f(1)} % (системний /proc/stat закритий)")
        LinearProgressIndicator({ cpuOf(x) / 100 }, Modifier.fillMaxWidth())
        Text("Оперативна пам'ять: ${x.ramUsedPercent.f()} % · вільно ${x.ramAvailMb} з ${x.ramTotalMb} МБ")
        LinearProgressIndicator({ x.ramUsedPercent / 100 }, Modifier.fillMaxWidth())
        Text("Батарея: ${x.batteryPercent} %${if (x.charging) " · заряджається" else ""}")
        LinearProgressIndicator({ x.batteryPercent / 100f }, Modifier.fillMaxWidth())
        InfoRow("Ядер CPU", "${x.cores}")
        InfoRow("Пам'ять застосунку (heap)", "${x.heapUsedMb.f(1)} / ${x.heapMaxMb.f()} МБ")
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumMonitorApp(vm: MonitorViewModel = viewModel()) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Монітор ресурсів")
        ThemeSwitcher()
        val x = s ?: run { Text("Збір даних…"); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RingGauge(cpuOf(x), if (x.cpuPercent != null) "CPU" else "CPU застос.", Modifier.weight(1f))
            RingGauge(x.ramUsedPercent, "RAM", Modifier.weight(1f))
            RingGauge(x.batteryPercent.toFloat(), "Батарея", Modifier.weight(1f), invert = true)
        }
        SectionCard("CPU, останні ${hist.size} с") { Sparkline(hist.map { cpuOf(it) }, Modifier.fillMaxWidth().height(70.dp)) }
        SectionCard("RAM, %") { Sparkline(hist.map { it.ramUsedPercent }, Modifier.fillMaxWidth().height(70.dp), MaterialTheme.colorScheme.tertiary) }
        Text("Колір шкал: зелений < 70 %, жовтий < 90 %, червоний ≥ 90 % (для батареї — навпаки).", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedMonitorApp(vm: MonitorViewModel = viewModel()) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    val period by vm.periodMs.collectAsStateWithLifecycle()
    val threads by vm.loadThreads.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Монітор · розширений")
        ThemeSwitcher()
        val x = s ?: run { Text("Збір даних…"); return@Column }
        if (x.lowMemory) Message("Система повідомляє про нестачу пам'яті", true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RingGauge(cpuOf(x), "CPU", Modifier.weight(1f))
            RingGauge(x.ramUsedPercent, "RAM", Modifier.weight(1f))
            RingGauge(x.storageUsedPercent, "Сховище", Modifier.weight(1f))
            RingGauge(x.batteryPercent.toFloat(), "Батарея", Modifier.weight(1f), invert = true)
        }
        SectionCard("Мережа") {
            InfoRow("Прийом / передача", "${x.rxKbps.f(1)} / ${x.txKbps.f(1)} КБ/с")
            Sparkline(hist.map { it.rxKbps + it.txKbps }, Modifier.fillMaxWidth().height(50.dp))
        }
        SectionCard("Батарея і сховище") {
            InfoRow("Температура / напруга", "${x.batteryTempC.f(1)} °C / ${x.batteryVoltage.f(2)} В")
            InfoRow("Вільно у сховищі", "${x.storageFreeGb.f(1)} з ${x.storageTotalGb.f(1)} ГБ")
            InfoRow("Heap застосунку", "${x.heapUsedMb.f(1)} / ${x.heapMaxMb.f()} МБ")
        }
        SectionCard("Багатопотоковість") {
            Text("Навантаження: ${if (threads > 0) "$threads потоків на Dispatchers.Default" else "немає"}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2, x.cores).distinct().forEach { n -> OutlinedButton({ vm.startLoad(n) }) { Text("$n") } }
                Button({ vm.stopLoad() }) { Text("Стоп") }
            }
            Text("CPU застосунку: ${x.appCpuPercent.f(1)} %", style = MaterialTheme.typography.bodySmall)
            Sparkline(hist.map { it.appCpuPercent }, Modifier.fillMaxWidth().height(50.dp), MaterialTheme.colorScheme.error)
        }
        Text("Інтервал опитування: ${period} мс", style = MaterialTheme.typography.bodySmall)
        Slider(period.toFloat(), { vm.periodMs.value = (it / 250).toLong() * 250 }, valueRange = 250f..5000f)
    }
}

// ============================ ДОМАШНЄ ============================
/** Стан планшета оператора ВЕС: сегментні шкали, графік, тема; натискання на блок — подробиці. */
@Composable
fun HomeworkMonitorApp(vm: MonitorViewModel = viewModel()) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val hist by vm.history.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Планшет оператора ВЕС", "Чи витримає пристрій зміну на станції")
        ThemeSwitcher()
        val x = s ?: run { Text("Збір даних…"); return@Column }
        val rows = listOf(
            Triple("CPU", cpuOf(x), false), Triple("Пам'ять", x.ramUsedPercent, false),
            Triple("Батарея", x.batteryPercent.toFloat(), true), Triple("Сховище", x.storageUsedPercent, false))
        rows.forEach { (name, v, inv) ->
            Column(Modifier.fillMaxWidth().clickable { details = if (details == name) null else name }) {
                Row { Text(name, Modifier.weight(1f)); Text("${v.f()} %", fontWeight = FontWeight.Bold) }
                SegmentScale(v, invert = inv)
                if (details == name) Text(when (name) {
                    "CPU" -> "Ядер: ${x.cores}; CPU застосунку ${x.appCpuPercent.f(1)} %"
                    "Пам'ять" -> "Вільно ${x.ramAvailMb} МБ з ${x.ramTotalMb} МБ"
                    "Батарея" -> "${x.batteryTempC.f(1)} °C, ${x.batteryVoltage.f(2)} В${if (x.charging) ", заряджається" else ""}"
                    else -> "Вільно ${x.storageFreeGb.f(1)} ГБ"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        // Прогноз роботи від батареї за спадом заряду за останню хвилину
        val first = hist.firstOrNull()
        val drop = if (first != null && !x.charging) first.batteryPercent - x.batteryPercent else 0
        val minutes = (x.time - (first?.time ?: x.time)) / 60000f
        InfoRow("Прогноз роботи", if (drop > 0 && minutes > 0) "${(x.batteryPercent / (drop / minutes) / 60).f(1)} год" else "заряд стабільний")
        SectionCard("Завантаження CPU і RAM") {
            Sparkline(hist.map { cpuOf(it) }, Modifier.fillMaxWidth().height(60.dp))
            Sparkline(hist.map { it.ramUsedPercent }, Modifier.fillMaxWidth().height(60.dp), MaterialTheme.colorScheme.tertiary)
        }
    }
}
