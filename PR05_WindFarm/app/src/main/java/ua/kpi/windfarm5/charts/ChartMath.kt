package ua.kpi.windfarm5.charts

import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.WindPowerCalculator
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** Точка часового ряду: година від початку періоду, швидкість вітру та виробіток станції. */
data class HourPoint(val hour: Int, val wind: Double, val energyMwh: Double) {
    val label: String get() = "д${hour / 24 + 1} ${"%02d".format(hour % 24)}:00"
}

/** Сума / середнє / пік для обраного інтервалу. */
data class SeriesStats(val total: Double, val average: Double, val peak: Double, val peakIndex: Int, val min: Double)

/** Порівняння станцій (категорій) для стовпчикової діаграми. */
data class CategoryValue(val name: String, val value: Double, val capacityMw: Double) {
    val capacityFactor get() = if (capacityMw > 0) value / (capacityMw * 24) * 100 else 0.0
}

/**
 * Генерація та обробка даних для графіків виробітку ВЕС.
 * Вітер моделюється як добовий цикл + повільний синоптичний тренд + шум,
 * виробіток — через криву потужності турбіни 2 МВт.
 */
object ChartData {
    private val spec = TurbineSpec()

    fun windSeries(hours: Int, seed: Int = 2, base: Double = 9.0): List<Double> {
        val rnd = Random(seed)
        var noise = 0.0
        return List(hours) { h ->
            noise = noise * 0.8 + rnd.nextDouble(-1.0, 1.0)
            val daily = 1.8 * sin((h % 24 - 9) / 24.0 * 2 * PI)        // удень вітер сильніший
            val synoptic = 3.0 * sin(h / 70.0 * 2 * PI)                  // проходження фронтів
            (base + daily + synoptic + noise).coerceIn(0.0, 30.0)
        }
    }

    /** Погодинний ряд станції з [turbines] турбінами. */
    fun generation(hours: Int, turbines: Int = 60, seed: Int = 2, base: Double = 9.0): List<HourPoint> =
        windSeries(hours, seed, base).mapIndexed { h, w ->
            HourPoint(h, Math.round(w * 10) / 10.0, WindPowerCalculator.turbinePowerKw(w, spec) * turbines / 1000.0)
        }

    /** Добовий виробіток кількох станцій. */
    fun stations(): List<CategoryValue> = listOf(
        Triple("Приазовська", 60, 10.5), Triple("Бердянська", 45, 9.8), Triple("Тилігул", 20, 8.6),
        Triple("Узбережжя", 35, 8.9), Triple("Степова", 25, 7.4), Triple("Карпатська", 12, 6.8)
    ).mapIndexed { i, (name, n, base) ->
        val day = generation(24, n, seed = 10 + i, base = base)
        CategoryValue(name, day.sumOf { it.energyMwh }, n * spec.ratedPowerKw / 1000.0)
    }

    fun stats(values: List<Double>): SeriesStats {
        if (values.isEmpty()) return SeriesStats(0.0, 0.0, 0.0, -1, 0.0)
        val peakIdx = values.indices.maxBy { values[it] }
        return SeriesStats(values.sum(), values.average(), values[peakIdx], peakIdx, values.min())
    }

    /** Ковзне середнє з вікном [window] (для згладженої лінії поверх стовпчиків). */
    fun movingAverage(values: List<Double>, window: Int): List<Double> {
        require(window >= 1)
        return values.indices.map { i -> values.subList(maxOf(0, i - window + 1), i + 1).average() }
    }

    /** Агрегація по [bucket] годин (наприклад, 24 → добовий виробіток). */
    fun aggregate(points: List<HourPoint>, bucket: Int): List<Double> =
        points.chunked(bucket).map { c -> c.sumOf { it.energyMwh } }
}

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
