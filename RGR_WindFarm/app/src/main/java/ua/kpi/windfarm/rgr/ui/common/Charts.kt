package ua.kpi.windfarm.rgr.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm.rgr.charts.AxisScale

/** Мініатюрний графік без осей (для карток сенсорів). */
@Composable
fun Sparkline(values: List<Float>, modifier: Modifier = Modifier.fillMaxWidth().height(60.dp), color: Color = MaterialTheme.colorScheme.primary) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        drawLine(grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        if (values.size < 2) return@Canvas
        val min = values.min(); val max = values.max()
        val range = (max - min).takeIf { it > 1e-6f } ?: 1f
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val y = size.height - (v - min) / range * size.height
            if (i == 0) path.moveTo(0f, y) else path.lineTo(i * step, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

/** Опис серії для графіка. */
data class ChartSeries(val values: List<Double>, val color: Color, val asBars: Boolean, val name: String)

/**
 * Універсальний графік на Canvas: лінії й/або стовпчики, вісь Y з «круглими» поділками,
 * натискання показує підказку (tooltip) для найближчої точки, жест «щипок» — масштаб.
 *  - [labels] — підписи по X (кожен n-й виводиться під віссю);
 *  - [selected] / [onSelect] — індекс точки з підказкою (стан тримає викликач);
 *  - [onZoom] — коефіцієнт масштабування і фокус (частка ширини) від жесту.
 */
@Composable
fun Chart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier.fillMaxWidth().height(220.dp),
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    onZoom: ((Float, Float) -> Unit)? = null,
    yUnit: String = "",
    tooltip: (Int) -> String = { i -> series.joinToString("\n") { s -> "${s.name}: ${"%.1f".format(s.values.getOrElse(i) { 0.0 })}" } }
) {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tipBg = MaterialTheme.colorScheme.inverseSurface
    val tipFg = MaterialTheme.colorScheme.inverseOnSurface
    val n = labels.size
    val maxV = series.flatMap { it.values }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val ticks = AxisScale.ticks(0.0, maxV, 4)
    val top = ticks.last()

    var m = modifier.pointerInput(n) {
        detectTapGestures { p ->
            val left = 48.dp.toPx()
            val w = size.width - left
            if (n == 0 || p.x < left) onSelect(null)
            else onSelect(((p.x - left) / w * n).toInt().coerceIn(0, n - 1))
        }
    }
    if (onZoom != null) m = m.pointerInput(n) {
        detectTransformGestures { centroid, _, zoom, _ -> if (zoom != 1f) onZoom(zoom, (centroid.x / size.width).coerceIn(0f, 1f)) }
    }

    Canvas(m) {
        val left = 48.dp.toPx()
        val bottom = 22.dp.toPx()
        val w = size.width - left
        val h = size.height - bottom
        if (n == 0) return@Canvas
        val slot = w / n
        fun y(v: Double) = (h - v / top * h).toFloat()

        // сітка й підписи осі Y
        ticks.forEach { t ->
            drawLine(gridColor, Offset(left, y(t)), Offset(size.width, y(t)))
            text("${"%.0f".format(t)}$yUnit", 4f, y(t) + 4.dp.toPx(), axisColor.toArgb(), 10.dp.toPx())
        }
        // підписи осі X — приблизно 6 на ширину
        val every = maxOf(1, n / 6)
        for (i in 0 until n step every) text(labels[i], left + i * slot, size.height - 4.dp.toPx(), axisColor.toArgb(), 9.dp.toPx())

        // стовпчики, потім лінії (лінії поверх)
        series.filter { it.asBars }.forEach { s ->
            s.values.take(n).forEachIndexed { i, v ->
                val barW = slot * 0.7f
                val c = if (i == selected) s.color else s.color.copy(alpha = 0.75f)
                drawRoundRect(c, Offset(left + i * slot + (slot - barW) / 2, y(v)), Size(barW, h - y(v)), CornerRadius(3f, 3f))
            }
        }
        series.filterNot { it.asBars }.forEach { s ->
            val path = Path()
            s.values.take(n).forEachIndexed { i, v ->
                val x = left + i * slot + slot / 2
                if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
            }
            drawPath(path, s.color, style = Stroke(width = 4f))
        }

        // підказка для вибраної точки
        if (selected != null && selected in 0 until n) {
            val x = left + selected * slot + slot / 2
            drawLine(axisColor, Offset(x, 0f), Offset(x, h), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            series.filterNot { it.asBars }.forEach { s -> s.values.getOrNull(selected)?.let { drawCircle(s.color, 6.dp.toPx(), Offset(x, y(it))) } }
            val lines = (labels[selected] + "\n" + tooltip(selected)).split("\n")
            val lh = 14.dp.toPx()
            val tw = lines.maxOf { it.length } * 6.6.dp.toPx() * 0.55f + 16.dp.toPx()
            val th = lines.size * lh + 10.dp.toPx()
            val tx = (x + 8.dp.toPx()).let { if (it + tw > size.width) x - tw - 8.dp.toPx() else it }
            drawRoundRect(tipBg, Offset(tx, 4.dp.toPx()), Size(tw, th), CornerRadius(8f, 8f))
            lines.forEachIndexed { i, l -> text(l, tx + 8.dp.toPx(), 4.dp.toPx() + lh * (i + 1), tipFg.toArgb(), 11.dp.toPx()) }
        }
    }
}

/** Текст на Canvas через нативний Paint (у Compose Canvas немає простого drawText без TextMeasurer). */
private fun DrawScope.text(s: String, x: Float, y: Float, color: Int, sizePx: Float) = drawIntoCanvas {
    it.nativeCanvas.drawText(s, x, y, android.graphics.Paint().apply { this.color = color; textSize = sizePx; isAntiAlias = true })
}
