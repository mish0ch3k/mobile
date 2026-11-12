package ua.kpi.windfarm6.s21

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm6.domain.TurbineSpec
import ua.kpi.windfarm6.domain.TurbineStatus
import ua.kpi.windfarm6.domain.WindPowerCalculator
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import ua.kpi.windfarm6.ui.common.Sparkline
import ua.kpi.windfarm6.ui.common.fmt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ============================ ДАНІ ============================

data class TurbineLive(val name: String, val wind: Double, val powerKw: Double, val status: TurbineStatus)

/** Стан станції цілком — визначає «режим» для синхронних анімацій. */
enum class StationMode(val title: String) { CALM("Штиль"), NORMAL("Робота"), RATED("Номінал"), STORM("Шторм") }

data class StationLive(val turbines: List<TurbineLive>, val tick: Int) {
    val totalMw get() = turbines.sumOf { it.powerKw } / 1000
    val avgWind get() = turbines.map { it.wind }.average()
    val mode get() = when {
        turbines.count { it.status == TurbineStatus.STORM_STOP } >= 3 -> StationMode.STORM
        turbines.count { it.status == TurbineStatus.RATED } >= 3 -> StationMode.RATED
        avgWind < 3.5 -> StationMode.CALM
        else -> StationMode.NORMAL
    }
}

/**
 * Телеметрія станції з 5 турбін: оновлюється кожні [periodMs] мс у viewModelScope.
 * UI підписується на StateFlow і анімує перехід від старого значення до нового.
 */
class LiveStationViewModel : ViewModel() {
    private val spec = TurbineSpec()
    private val rnd = Random(21)
    val station = MutableStateFlow(generate(List(5) { 8.0 + it }, 0))
    val history = MutableStateFlow(listOf(station.value.totalMw))
    val running = MutableStateFlow(true)
    val periodMs = MutableStateFlow(2000L)
    /** Зсув «погоди»: дозволяє вручну перевести станцію в штиль або шторм. */
    val weatherBias = MutableStateFlow(0f)

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(periodMs.value)
                if (!running.value) continue
                station.update { s ->
                    val winds = s.turbines.map { t ->
                        val target = 10.0 + weatherBias.value
                        (t.wind + (target - t.wind) * 0.25 + rnd.nextDouble(-2.0, 2.0)).coerceIn(0.0, 32.0)
                    }
                    generate(winds, s.tick + 1)
                }
                history.update { (it + station.value.totalMw).takeLast(30) }
            }
        }
    }

    private fun generate(winds: List<Double>, tick: Int) = StationLive(winds.mapIndexed { i, w ->
        TurbineLive("ВТ-0${i + 1}", w, WindPowerCalculator.turbinePowerKw(w, spec), WindPowerCalculator.status(w, spec))
    }, tick)
}

private fun statusColor(s: TurbineStatus) = when (s) {
    TurbineStatus.IDLE -> Color(0xFF90A4AE)
    TurbineStatus.PARTIAL_LOAD -> Color(0xFF1E88E5)
    TurbineStatus.RATED -> Color(0xFF43A047)
    TurbineStatus.STORM_STOP -> Color(0xFFE53935)
}

private fun modeColor(m: StationMode) = when (m) {
    StationMode.CALM -> Color(0xFF78909C); StationMode.NORMAL -> Color(0xFF1E88E5)
    StationMode.RATED -> Color(0xFF43A047); StationMode.STORM -> Color(0xFFE53935)
}

/** Ротор, що обертається безперервно; швидкість задається періодом оберту. */
@Composable
fun Rotor(periodMs: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    // rememberInfiniteTransition — нескінченна анімація; при зміні periodMs створюється нова специфікація
    val angle by rememberInfiniteTransition(label = "rotor").animateFloat(0f, 360f,
        infiniteRepeatable(tween(periodMs.coerceAtLeast(1), easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val still = periodMs <= 0
    Canvas(modifier.aspectRatio(1f).rotate(if (still) 0f else angle)) {
        val r = size.minDimension / 2
        repeat(3) { i ->
            val a = Math.toRadians(i * 120.0 - 90)
            drawLine(color, center, Offset(center.x + (r * 0.95f * cos(a)).toFloat(), center.y + (r * 0.95f * sin(a)).toFloat()), strokeWidth = r * 0.12f, cap = StrokeCap.Round)
        }
        drawCircle(color, r * 0.13f, center)
    }
}

/** Період оберту ротора за вітром: сильніший вітер — швидше; у шторм і штиль ротор стоїть. */
private fun rotorPeriod(t: TurbineLive) = if (t.status == TurbineStatus.IDLE || t.status == TurbineStatus.STORM_STOP) 0 else (6000 / t.wind).toInt().coerceIn(300, 3000)

// ============================ БАЗОВИЙ ============================
/** Шкала потужності станції: ширина й колір анімуються, число змінюється з переходом. */
@Composable
fun BasicAnimApp(vm: LiveStationViewModel = viewModel()) {
    val st by vm.station.collectAsStateWithLifecycle()
    val fraction by animateFloatAsState((st.totalMw / 10.0).toFloat().coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "power")
    val color by animateColorAsState(modeColor(st.mode), tween(900), label = "color")
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Потужність станції", "5 турбін × 2 МВт · оновлення кожні 2 с")
        Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).clip(RoundedCornerShape(18.dp)).background(color))
        }
        // AnimatedContent: старе число виїжджає, нове заїжджає
        AnimatedContent(st.totalMw.fmt(2), transitionSpec = { slideInVertically { it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut() }, label = "value") { v ->
            Text("$v МВт", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = color)
        }
        Text("Режим: ${st.mode.title} · середній вітер ${st.avgWind.fmt()} м/с")
    }
}

// ============================ СЕРЕДНІЙ ============================
/** Кілька анімаційних API разом: стовпчики (animateFloatAsState), ротори (infinite), Crossfade стану. */
@Composable
fun MediumAnimApp(vm: LiveStationViewModel = viewModel()) {
    val st by vm.station.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Турбіни в реальному часі")
        Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            st.turbines.forEach { t ->
                val h by animateFloatAsState((t.powerKw / 2000).toFloat().coerceIn(0.02f, 1f), spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "bar")
                val c by animateColorAsState(statusColor(t.status), tween(600), label = "barColor")
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text("${t.powerKw.fmt(0)}", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.fillMaxWidth().fillMaxHeight(h * 0.85f).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(c))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            st.turbines.forEach { t ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Rotor(rotorPeriod(t), Modifier.fillMaxWidth(), statusColor(t.status))
                    Text(t.name, style = MaterialTheme.typography.labelSmall)
                    Text("${t.wind.fmt()} м/с", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // Crossfade плавно змінює підпис режиму станції
        Crossfade(st.mode, animationSpec = tween(700), label = "mode") { m ->
            Text("Станція: ${m.title}", style = MaterialTheme.typography.headlineSmall, color = modeColor(m))
        }
        Text("Висота стовпчика — потужність, колір — стан турбіни, швидкість ротора — вітер.", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Дашборд, де всі елементи синхронізовані через один updateTransition(mode):
 * колір тла, масштаб ротора, положення стрілки, банер тривоги змінюються узгоджено.
 */
@Composable
fun AdvancedAnimApp(vm: LiveStationViewModel = viewModel()) {
    val st by vm.station.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val bias by vm.weatherBias.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    val tr = updateTransition(st.mode, label = "station")
    val bg by tr.animateColor({ tween(800) }, label = "bg") { modeColor(it).copy(alpha = 0.15f) }
    val accent by tr.animateColor({ tween(800) }, label = "accent") { modeColor(it) }
    val rotorScale by tr.animateFloat({ spring(Spring.DampingRatioMediumBouncy) }, label = "scale") { if (it == StationMode.RATED) 1.15f else if (it == StationMode.STORM) 0.85f else 1f }
    val needle by animateFloatAsState((st.totalMw / 10.0).toFloat().coerceIn(0f, 1f), tween(900), label = "needle")

    Column(Modifier.fillMaxSize().background(bg).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Диспетчерська панель")
        AnimatedVisibility(st.mode == StationMode.STORM, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text("⚠ Штормова зупинка: турбіни у флюгерному положенні", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFE53935)).padding(10.dp), color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Rotor(rotorPeriod(st.turbines.maxBy { it.powerKw }), Modifier.size(96.dp).scale(rotorScale), accent)
            Column(Modifier.weight(1f)) {
                Text(st.mode.title, style = MaterialTheme.typography.headlineSmall, color = accent)
                Text("${st.totalMw.fmt(2)} МВт · ${st.avgWind.fmt()} м/с")
                Gauge(needle, accent, Modifier.fillMaxWidth().height(70.dp))
            }
        }
        Sparkline(history.map { it.toFloat() }, Modifier.fillMaxWidth().height(50.dp), accent)
        st.turbines.forEach { t ->
            // animateContentSize: картка плавно розгортається при натисканні
            Card(Modifier.fillMaxWidth().animateContentSize().clickable { expanded = if (expanded == t.name) null else t.name }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(statusColor(t.status)))
                        Text("  ${t.name}: ${t.powerKw.fmt(0)} кВт", Modifier.weight(1f))
                        Text("${t.wind.fmt()} м/с", style = MaterialTheme.typography.labelMedium)
                    }
                    if (expanded == t.name) {
                        InfoRow("Стан", t.status.title)
                        InfoRow("КВВП", "${(t.powerKw / 20).fmt(0)} %")
                        InfoRow("Бофорт", "${WindPowerCalculator.beaufort(t.wind)} — ${WindPowerCalculator.beaufortName(WindPowerCalculator.beaufort(t.wind))}")
                    }
                }
            }
        }
        SectionCard("Керування моделлю") {
            Text("Погода: ${if (bias >= 0) "+" else ""}${bias.toDouble().fmt()} м/с до середнього вітру", style = MaterialTheme.typography.bodySmall)
            Slider(bias, { vm.weatherBias.value = it }, valueRange = -10f..18f)
            OutlinedButton({ vm.running.update { !it } }) { Text(if (running) "Пауза" else "Продовжити") }
        }
    }
}

/** Напівкругла шкала зі стрілкою (частка 0…1). */
@Composable
private fun Gauge(fraction: Float, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val stroke = 10.dp.toPx()
        val d = minOf(size.width, size.height * 2) - stroke
        val tl = Offset((size.width - d) / 2, stroke / 2)
        drawArc(track, 180f, 180f, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        drawArc(color, 180f, 180f * fraction, false, tl, androidx.compose.ui.geometry.Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

// ============================ ДОМАШНЄ ============================
/**
 * Анімована шкала виробітку з індикацією тенденції: нове значення заїжджає згори,
 * якщо зросло, і знизу, якщо впало; колір стрілки тенденції анімується.
 * Натискання на шкалу показує деталі (AnimatedVisibility).
 */
@Composable
fun HomeworkAnimApp(vm: LiveStationViewModel = viewModel()) {
    val st by vm.station.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    val prev = history.getOrNull(history.size - 2) ?: st.totalMw
    val delta = st.totalMw - prev
    val trendColor by animateColorAsState(if (delta > 0.05) Color(0xFF43A047) else if (delta < -0.05) Color(0xFFE53935) else Color(0xFF90A4AE), tween(600), label = "trend")
    val level by animateFloatAsState((st.totalMw / 10.0).toFloat().coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing), label = "level")
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenTitle("Виробіток ВЕС", "Натисніть на шкалу для деталей")
        Row(Modifier.clickable { details = !details }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // вертикальна шкала-«термометр»
            Box(Modifier.width(56.dp).height(240.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(level).clip(RoundedCornerShape(28.dp)).background(modeColor(st.mode)))
            }
            Column(horizontalAlignment = Alignment.Start) {
                AnimatedContent(st.totalMw, transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith (slideOutVertically { if (up) -it else it } + fadeOut())
                }, label = "mw") { v -> Text("${v.fmt(2)} МВт", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold) }
                Text(if (delta > 0.05) "▲ +${delta.fmt(2)}" else if (delta < -0.05) "▼ ${delta.fmt(2)}" else "● стабільно",
                    Modifier.scale(if (kotlin.math.abs(delta) > 0.05) pulse else 1f), color = trendColor, style = MaterialTheme.typography.titleLarge)
                Text("за останні 2 с", style = MaterialTheme.typography.labelSmall)
            }
        }
        AnimatedVisibility(details, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SectionCard("Деталі") {
                InfoRow("Режим", st.mode.title)
                InfoRow("Середній вітер", "${st.avgWind.fmt()} м/с")
                InfoRow("Мін / макс за 1 хв", "${history.minOrNull()?.fmt(2)} / ${history.maxOrNull()?.fmt(2)} МВт")
                InfoRow("Виробіток за годину при такій потужності", "${st.totalMw.fmt(1)} МВт·год")
                Sparkline(history.map { it.toFloat() }, Modifier.fillMaxWidth().height(60.dp), trendColor)
            }
        }
    }
}
