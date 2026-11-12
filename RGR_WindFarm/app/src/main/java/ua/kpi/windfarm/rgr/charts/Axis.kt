package ua.kpi.windfarm.rgr.charts

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Масштаб осі: «круглі» межі та кроки поділок (1, 2, 5 × 10^n), щоб підписи читалися легко.
 */
object AxisScale {
    fun niceStep(range: Double, ticks: Int): Double {
        if (range <= 0) return 1.0
        val raw = range / ticks
        val mag = 10.0.pow(floor(log10(raw)))
        val f = raw / mag
        val nice = when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
        return nice * mag
    }

    /** Межі осі від 0 (або мінімуму) до «круглого» максимуму і значення поділок. */
    fun ticks(min: Double, max: Double, count: Int = 4): List<Double> {
        val step = niceStep(max - min, count)
        val lo = floor(min / step) * step
        val hi = ceil(max / step) * step
        return generateSequence(lo) { it + step }.takeWhile { it <= hi + step / 2 }.toList()
    }
}

/**
 * Видиме вікно графіка під час масштабування: [start, end) індексів.
 * Зум відбувається відносно фокусної точки, щоб вона лишалася на тому ж місці екрана.
 */
data class Viewport(val start: Int, val end: Int, val total: Int) {
    val size get() = end - start

    fun zoom(factor: Float, focusFraction: Float = 0.5f, minSize: Int = 6): Viewport {
        val newSize = (size / factor).toInt().coerceIn(minOf(minSize, total), total)
        val focus = start + size * focusFraction.coerceIn(0f, 1f)
        val newStart = (focus - newSize * focusFraction).toInt().coerceIn(0, total - newSize)
        return copy(start = newStart, end = newStart + newSize)
    }

    fun pan(delta: Int): Viewport {
        val s = (start + delta).coerceIn(0, total - size)
        return copy(start = s, end = s + size)
    }

    /** Індекс точки під координатою x (частка ширини 0…1). */
    fun indexAt(fraction: Float): Int = (start + (fraction.coerceIn(0f, 0.9999f) * size).toInt()).coerceIn(start, end - 1)

    companion object { fun all(total: Int) = Viewport(0, total, total) }
}
