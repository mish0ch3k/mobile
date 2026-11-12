package ua.kpi.windfarm.rgr.domain

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/** Параметри турбіни. За замовчуванням — типова турбіна 2 МВт (діаметр ротора 90 м). */
data class TurbineSpec(
    val ratedPowerKw: Double = 2000.0,
    val rotorRadiusM: Double = 45.0,
    val powerCoefficient: Double = 0.42,
    val cutInMs: Double = 3.0,
    val ratedMs: Double = 12.0,
    val cutOutMs: Double = 25.0,
    val hubHeightM: Double = 100.0
) {
    init {
        require(ratedPowerKw > 0 && rotorRadiusM > 0) { "Потужність і радіус мають бути додатними" }
        require(powerCoefficient > 0 && powerCoefficient <= WindPhysics.BETZ_LIMIT) { "Cp має бути в межах (0; 0,593]" }
        require(cutInMs < ratedMs && ratedMs < cutOutMs) { "Має виконуватись cut-in < rated < cut-out" }
    }
    val sweptAreaM2 get() = PI * rotorRadiusM * rotorRadiusM
}

enum class TurbineState(val title: String) {
    IDLE("Очікування вітру"), PARTIAL("Часткове навантаження"), RATED("Номінальна потужність"), STORM("Штормова зупинка")
}

/**
 * Фізика вітрової турбіни.
 *  P = ½ · ρ · A · v³ · Cp — потужність, яку ротор знімає з потоку;
 *  ρ = p / (R · T)         — густина сухого повітря;
 *  v(h) = v(h₀) · (h / h₀)^α — перерахунок швидкості на висоту маточини (степеневий закон, α ≈ 0,14).
 */
object WindPhysics {
    const val BETZ_LIMIT = 16.0 / 27.0
    const val R_AIR = 287.05            // Дж/(кг·К)
    const val STANDARD_PRESSURE = 1013.25
    const val SHEAR_EXPONENT = 0.14

    fun airDensity(temperatureC: Double = 15.0, pressureHpa: Double = STANDARD_PRESSURE): Double {
        require(pressureHpa > 0) { "Тиск має бути додатним" }
        require(temperatureC > -100 && temperatureC < 100) { "Температура поза фізичними межами" }
        return pressureHpa * 100 / (R_AIR * (temperatureC + 273.15))
    }

    /** Швидкість на висоті [toH] за відомою на [fromH] (наприклад, 10 м → 100 м). */
    fun windAtHeight(v: Double, fromH: Double, toH: Double, alpha: Double = SHEAR_EXPONENT): Double {
        require(fromH > 0 && toH > 0)
        return v * (toH / fromH).pow(alpha)
    }

    /** Потужність турбіни, кВт, з урахуванням робочої характеристики (ввімкнення, номінал, зупинка). */
    fun powerKw(v: Double, spec: TurbineSpec = TurbineSpec(), density: Double = airDensity()): Double {
        if (!v.isFinite() || v < spec.cutInMs || v >= spec.cutOutMs) return 0.0
        val theoretical = 0.5 * density * spec.sweptAreaM2 * v.pow(3) * spec.powerCoefficient / 1000
        return minOf(theoretical, spec.ratedPowerKw)
    }

    fun state(v: Double, spec: TurbineSpec = TurbineSpec(), density: Double = airDensity()): TurbineState = when {
        v < spec.cutInMs -> TurbineState.IDLE
        v >= spec.cutOutMs -> TurbineState.STORM
        v >= spec.ratedMs || powerKw(v, spec, density) >= spec.ratedPowerKw -> TurbineState.RATED
        else -> TurbineState.PARTIAL
    }

    /** Коефіцієнт використання встановленої потужності (КВВП), 0…1. */
    fun capacityFactor(energyKwh: Double, ratedKw: Double, hours: Double): Double =
        if (ratedKw <= 0 || hours <= 0) 0.0 else (energyKwh / (ratedKw * hours)).coerceIn(0.0, 1.0)

    /** Бал за шкалою Бофорта (0…12). */
    fun beaufort(v: Double): Int {
        val limits = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)
        return limits.indexOfFirst { v < it }.let { if (it < 0) 12 else it }
    }
}

/** Статистика вітрового ресурсу за рядом вимірів. */
object WindStatistics {
    data class Summary(val count: Int, val mean: Double, val max: Double, val std: Double)

    fun summary(v: List<Double>): Summary {
        if (v.isEmpty()) return Summary(0, 0.0, 0.0, 0.0)
        val m = v.average()
        val std = sqrt(v.sumOf { (it - m).pow(2) } / v.size)
        return Summary(v.size, m, v.max(), std)
    }

    /** Гістограма швидкостей з кроком [step] м/с: кількість вимірів у кожному інтервалі [i·step; (i+1)·step). */
    fun histogram(v: List<Double>, step: Double = 2.0, maxV: Double = 30.0): IntArray {
        val bins = IntArray((maxV / step).toInt())
        v.forEach { x -> if (x >= 0) bins[(x / step).toInt().coerceAtMost(bins.size - 1)]++ }
        return bins
    }

    /**
     * Параметри розподілу Вейбулла (k — форма, c — масштаб) емпіричним методом Джастуса:
     * k = (σ / v̄)^−1,086, c = v̄ / Γ(1 + 1/k). Розподіл Вейбулла — стандартна модель вітрового ресурсу.
     */
    fun weibull(v: List<Double>): Pair<Double, Double>? {
        val s = summary(v)
        if (s.count < 3 || s.mean <= 0 || s.std <= 0) return null
        val k = (s.std / s.mean).pow(-1.086)
        val c = s.mean / gamma(1 + 1 / k)
        return k to c
    }

    /** Імовірність швидкості в інтервалі [a, b] за Вейбуллом. */
    fun weibullProbability(a: Double, b: Double, k: Double, c: Double): Double =
        exp(-(a / c).pow(k)) - exp(-(b / c).pow(k))

    /** Очікуваний річний виробіток турбіни, МВт·год, за розподілом Вейбулла та кривою потужності. */
    fun annualEnergyMwh(k: Double, c: Double, spec: TurbineSpec = TurbineSpec()): Double {
        var e = 0.0
        var x = 0.0
        val dv = 0.5
        while (x < 30) { e += WindPhysics.powerKw(x + dv / 2, spec) * weibullProbability(x, x + dv, k, c); x += dv }
        return e * 8760 / 1000
    }

    /** Гамма-функція (наближення Ланцоша), потрібна для параметра c. */
    fun gamma(x: Double): Double {
        if (x < 0.5) return PI / (kotlin.math.sin(PI * x) * gamma(1 - x))
        val g = 7.0
        val coef = doubleArrayOf(0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7)
        val xx = x - 1
        var a = coef[0]
        val t = xx + g + 0.5
        for (i in 1 until 9) a += coef[i] / (xx + i)
        return sqrt(2 * PI) * t.pow(xx + 0.5) * exp(-t) * a
    }

}
