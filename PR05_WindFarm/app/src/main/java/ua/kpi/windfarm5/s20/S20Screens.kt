package ua.kpi.windfarm5.s20

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm5.sensors.SensorMath
import ua.kpi.windfarm5.sensors.Vec3
import ua.kpi.windfarm5.ui.common.Chips
import ua.kpi.windfarm5.ui.common.InfoRow
import ua.kpi.windfarm5.ui.common.Message
import ua.kpi.windfarm5.ui.common.ScreenTitle
import ua.kpi.windfarm5.ui.common.SectionCard
import ua.kpi.windfarm5.ui.common.fmt
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun Float.f(d: Int = 1) = toDouble().fmt(d)

/** Палітра для налаштування компонентів. */
enum class Palette(val title: String, val color: Color) {
    BLUE("Синій", Color(0xFF1565C0)), GREEN("Зелений", Color(0xFF2E7D32)), ORANGE("Помаранч", Color(0xFFEF6C00)), PURPLE("Фіолет", Color(0xFF6A1B9A))
}

// ============================ КОМПОНЕНТИ ============================

/**
 * Рівень-«бульбашка»: кулька зміщується за нахилом пристрою (pitch/roll).
 * [maxAngle] — нахил, за якого кулька доходить до краю.
 */
@Composable
fun TiltIndicator(pitch: Float, roll: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, maxAngle: Float = 30f) {
    // spring робить рух кульки плавним і «фізичним», без ривків від шуму сенсора
    val x by animateFloatAsState((roll / maxAngle).coerceIn(-1f, 1f), spring(stiffness = 300f), label = "x")
    val y by animateFloatAsState((-pitch / maxAngle).coerceIn(-1f, 1f), spring(stiffness = 300f), label = "y")
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(outline, r - 2, c, style = Stroke(3f))
        drawCircle(outline, r / 3, c, style = Stroke(2f))
        drawLine(outline, Offset(c.x - r, c.y), Offset(c.x + r, c.y))
        drawLine(outline, Offset(c.x, c.y - r), Offset(c.x, c.y + r))
        val ball = r * 0.16f
        val reach = r - ball - 4
        val inZone = kotlin.math.abs(x) < 0.12f && kotlin.math.abs(y) < 0.12f
        drawCircle(if (inZone) Color(0xFF2E7D32) else color, ball, Offset(c.x + x * reach, c.y - y * reach))
    }
}

/**
 * Кругова шкала (дуга 240°) зі стрілкою; [value] анімується плавно.
 * Зони: зелена до [warn], жовта до [alarm], далі червона.
 */
@Composable
fun ArcGauge(value: Float, max: Float, warn: Float, alarm: Float, unit: String, modifier: Modifier = Modifier, strokeWidth: Dp = 14.dp) {
    val v by animateFloatAsState(value.coerceIn(0f, max), tween(400), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val needle = MaterialTheme.colorScheme.onSurface
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f)) {
            val sw = strokeWidth.toPx()
            val d = min(size.width, size.height * 1.4f) - sw
            val topLeft = Offset((size.width - d) / 2, sw / 2 + 4)
            val arc = Size(d, d)
            fun sweep(x: Float) = x / max * 240f
            drawArc(track, 150f, 240f, false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(Color(0xFF43A047), 150f, sweep(min(v, warn)), false, topLeft, arc, style = Stroke(sw, cap = StrokeCap.Round))
            if (v > warn) drawArc(Color(0xFFFFB300), 150f + sweep(warn), sweep(min(v, alarm) - warn), false, topLeft, arc, style = Stroke(sw))
            if (v > alarm) drawArc(Color(0xFFE53935), 150f + sweep(alarm), sweep(v - alarm), false, topLeft, arc, style = Stroke(sw))
            val c = Offset(topLeft.x + d / 2, topLeft.y + d / 2)
            val a = Math.toRadians((150f + sweep(v)).toDouble())
            val len = d / 2 - sw * 1.5f
            drawLine(needle, c, Offset(c.x + (len * cos(a)).toFloat(), c.y + (len * sin(a)).toFloat()), strokeWidth = 5f, cap = StrokeCap.Round)
            drawCircle(needle, 8f, c)
        }
        Text("${v.f()} $unit", style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * Компас: шкала обертається так, щоб «Пн» вказувала на північ; додаткова стрілка — [target]
 * (наприклад, напрям вітру). Обертання анімується найкоротшим шляхом.
 */
@Composable
fun Compass(heading: Float, target: Float?, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, showTicks: Boolean = true) {
    // накопичуємо кут, щоб перехід 359° → 1° не крутив шкалу на повне коло
    var acc by remember { mutableFloatStateOf(heading) }
    LaunchedEffect(heading) { acc += SensorMath.angleDiff(acc, heading) }
    val rot by animateFloatAsState(-acc, tween(250), label = "compass")
    val outline = MaterialTheme.colorScheme.outline
    val text = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2 - 6
        rotate(rot) {
            drawCircle(outline, r, center, style = Stroke(3f))
            if (showTicks) for (i in 0 until 36) {
                val a = Math.toRadians(i * 10.0 - 90)
                val inner = if (i % 9 == 0) r * 0.8f else r * 0.9f
                drawLine(outline, Offset(center.x + (inner * cos(a)).toFloat(), center.y + (inner * sin(a)).toFloat()),
                    Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat()), strokeWidth = if (i % 9 == 0) 4f else 2f)
            }
            // північна стрілка
            arrow(Color(0xFFE53935), 0f, r * 0.7f)
            arrow(outline, 180f, r * 0.7f)
            if (target != null) arrow(color, target, r * 0.85f, width = 10f)
        }
        drawLine(text, Offset(center.x, center.y - r - 4), Offset(center.x, center.y - r + 18), strokeWidth = 5f) // курс пристрою
    }
}

private fun DrawScope.arrow(color: Color, deg: Float, len: Float, width: Float = 14f) {
    val a = Math.toRadians(deg - 90.0)
    val tip = Offset(center.x + (len * cos(a)).toFloat(), center.y + (len * sin(a)).toFloat())
    val side = Math.toRadians(deg.toDouble())
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(center.x + (width * cos(side)).toFloat(), center.y + (width * sin(side)).toFloat())
        lineTo(center.x - (width * cos(side)).toFloat(), center.y - (width * sin(side)).toFloat())
        close()
    }
    drawPath(p, color)
}

/** Вектор (x, y) у площині екрана: стрілка з центру, довжина = модуль × [scale]. */
@Composable
fun VectorView(v: Vec3, scale: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val x by animateFloatAsState(v.x, tween(120), label = "vx")
    val y by animateFloatAsState(v.y, tween(120), label = "vy")
    val z by animateFloatAsState(v.z, tween(120), label = "vz")
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2
        drawCircle(grid, r - 2, center, style = Stroke(2f))
        drawLine(grid, Offset(0f, center.y), Offset(size.width, center.y))
        drawLine(grid, Offset(center.x, 0f), Offset(center.x, size.height))
        val end = Offset((center.x + x * scale * r).coerceIn(0f, size.width), (center.y - y * scale * r).coerceIn(0f, size.height))
        drawLine(color, center, end, strokeWidth = 8f, cap = StrokeCap.Round)
        // Z — кружок: радіус ∝ |z|, заливка — напрям «до нас», контур — «від нас»
        drawCircle(color.copy(alpha = 0.6f), (kotlin.math.abs(z) * scale * r * 0.5f).coerceIn(4f, r), center, style = if (z >= 0) Fill else Stroke(4f))
    }
}

enum class TurbineStyle(val title: String) { FILLED("Заливка"), OUTLINE("Контур") }

/**
 * Вітрогенератор, вид згори: гондола повертається за курсом пристрою, стрілка — напрям вітру,
 * ротор обертається зі швидкістю, пропорційною вітру. Налаштовуються колір, розмір, стиль.
 */
@Composable
fun TurbineTopView(nacelle: Float, windFrom: Float, wind: Double, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, style: TurbineStyle = TurbineStyle.FILLED) {
    var phase by remember { mutableFloatStateOf(0f) }
    val rpm = (wind.coerceIn(0.0, 25.0) * 1.2).toFloat() // умовні об/хв для анімації
    LaunchedEffect(rpm) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { t -> phase = (phase + rpm * 6f * (t - last) / 1e9f) % 360f; last = t }
    }
    val yaw by animateFloatAsState(nacelle, tween(300, easing = LinearEasing), label = "yaw")
    val outline = MaterialTheme.colorScheme.outline
    Canvas(modifier.aspectRatio(1f)) {
        val r = size.minDimension / 2
        drawCircle(outline, r - 2, center, style = Stroke(2f))
        arrow(Color(0xFF90A4AE), windFrom + 180f, r * 0.9f, width = 8f) // куди дме вітер
        rotate(yaw) {
            val body = Size(r * 0.22f, r * 0.55f)
            val drawStyle = if (style == TurbineStyle.FILLED) Fill else Stroke(5f)
            drawRoundRect(color, Offset(center.x - body.width / 2, center.y - body.height * 0.15f), body,
                androidx.compose.ui.geometry.CornerRadius(12f), style = drawStyle)
            // ротор видно збоку як лінію, що «миготить» — довжина змінюється з фазою обертання
            val blade = r * 0.75f * kotlin.math.abs(cos(Math.toRadians(phase.toDouble()))).toFloat().coerceAtLeast(0.15f)
            drawLine(color, Offset(center.x - blade, center.y - body.height * 0.2f), Offset(center.x + blade, center.y - body.height * 0.2f), strokeWidth = 10f, cap = StrokeCap.Round)
            drawCircle(color, r * 0.07f, Offset(center.x, center.y - body.height * 0.2f))
        }
    }
}

// ============================ ЕКРАНИ ============================

@Composable
private fun Missing(vm: MotionViewModel) {
    val missing by vm.missing.collectAsStateWithLifecycle()
    if (missing.isNotEmpty()) Message("Немає сенсорів: ${missing.joinToString()}", isError = true)
}

/** Базовий: індикатор нахилу за акселерометром. */
@Composable
fun BasicComponentApp(vm: MotionViewModel = viewModel()) {
    val m by vm.motion.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Нахил башти", "Нахиліть телефон — кулька покаже відхилення")
        Missing(vm)
        TiltIndicator(m.pitch, m.roll, Modifier.fillMaxWidth(0.85f).align(Alignment.CenterHorizontally))
        InfoRow("Pitch (вперед/назад)", "${m.pitch.f()}°")
        InfoRow("Roll (вліво/вправо)", "${m.roll.f()}°")
        Text("Зелена кулька — пристрій горизонтальний (±3,6°).", style = MaterialTheme.typography.bodySmall)
    }
}

/** Середній: кругова шкала відхилення від вертикалі та шкала вібрації. */
@Composable
fun MediumComponentApp(vm: MotionViewModel = viewModel()) {
    val m by vm.motion.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Стан башти")
        Missing(vm)
        SectionCard("Відхилення від вертикалі") {
            ArcGauge(m.inclination, 90f, warn = 5f, alarm = 15f, unit = "°", modifier = Modifier.fillMaxWidth())
        }
        SectionCard("Вібрація (RMS лінійного прискорення)") {
            ArcGauge(m.vibration, 10f, warn = 1.5f, alarm = 4f, unit = "м/с²", modifier = Modifier.fillMaxWidth(), strokeWidth = 10.dp)
        }
    }
}

/** Просунутий: компас + вектори гіроскопа й гравітації, налаштування кольору та масштабу. */
@Composable
fun AdvancedComponentApp(vm: MotionViewModel = viewModel()) {
    val m by vm.motion.collectAsStateWithLifecycle()
    var palette by rememberSaveable { mutableStateOf(Palette.BLUE) }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var ticks by rememberSaveable { mutableStateOf(true) }
    var showVectors by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Орієнтація гондоли")
        Missing(vm)
        SectionCard("Налаштування") {
            Chips(Palette.entries, palette, { it.title }) { palette = it }
            Text("Масштаб векторів: ×${scale.f()}", style = MaterialTheme.typography.bodySmall)
            Slider(scale, { scale = it }, valueRange = 0.2f..3f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(ticks, { ticks = it }); Text("Поділки компаса")
                Switch(showVectors, { showVectors = it }); Text("Вектори")
            }
        }
        Compass(m.heading ?: 0f, target = 240f, modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally), color = palette.color, showTicks = ticks)
        InfoRow("Курс", m.heading?.let { "${it.f(0)}°" } ?: "немає магнітометра")
        InfoRow("До напряму вітру (240°)", m.heading?.let { "${SensorMath.angleDiff(it, 240f).f(0)}°" } ?: "—")
        if (showVectors) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                VectorView(m.gyro, scale * 0.3f, Modifier.fillMaxWidth(), palette.color)
                Text("Гіроскоп ${m.gyro.magnitude.f(2)} рад/с", style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                VectorView(m.gravity, scale * 0.1f, Modifier.fillMaxWidth(), palette.color)
                Text("Гравітація ${m.gravity.magnitude.f(2)} м/с²", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** Домашнє: модель турбіни, якою керує телефон (компас — гондола, обертання — вітер). */
@Composable
fun HomeworkComponentApp(vm: MotionViewModel = viewModel()) {
    val m by vm.motion.collectAsStateWithLifecycle()
    val windDir by vm.windDirection.collectAsStateWithLifecycle()
    var palette by rememberSaveable { mutableStateOf(Palette.GREEN) }
    var style by rememberSaveable { mutableStateOf(TurbineStyle.FILLED) }
    var sizeDp by rememberSaveable { mutableFloatStateOf(260f) }
    val heading = m.heading ?: 0f
    val wind = vm.windFrom(m)
    val yawError = SensorMath.angleDiff(heading, windDir)
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Турбіна в руках", "Поверніть телефон до вітру й покрутіть його")
        Missing(vm)
        TurbineTopView(heading, windDir, wind, Modifier.size(sizeDp.dp).align(Alignment.CenterHorizontally), palette.color, style)
        InfoRow("Вітер (з обертання)", "${wind.fmt()} м/с з ${windDir.f(0)}°")
        InfoRow("Похибка орієнтації", "${yawError.f(0)}°")
        InfoRow("Потужність", "${vm.powerKw(wind, yawError).fmt(0)} кВт")
        SectionCard("Вигляд компонента") {
            Chips(Palette.entries, palette, { it.title }) { palette = it }
            Chips(TurbineStyle.entries, style, { it.title }) { style = it }
            Text("Розмір: ${sizeDp.toInt()} dp", style = MaterialTheme.typography.bodySmall)
            Slider(sizeDp, { sizeDp = it }, valueRange = 160f..320f)
            Text("Напрям вітру: ${windDir.f(0)}°", style = MaterialTheme.typography.bodySmall)
            Slider(windDir, { vm.windDirection.value = it }, valueRange = 0f..359f)
        }
    }
}
