package ua.kpi.windfarm.logic

import kotlin.math.PI
import kotlin.math.pow

/**
 * Бізнес-логіка вітрової електростанції (варіант 2).
 *
 * Потужність турбіни: P = ½ · ρ · A · v³ · Cp,
 * де ρ — густина повітря, A = π·r² — площа, що омітається лопатями,
 * v — швидкість вітру, Cp — коефіцієнт використання енергії вітру (≤ 0.593, межа Бетца).
 */
object WindPowerCalculator {

    /** Межа Бетца — теоретичний максимум Cp. */
    const val BETZ_LIMIT = 16.0 / 27.0

    /** Газова стала сухого повітря, Дж/(кг·К). */
    private const val R_AIR = 287.05

    /** Густина повітря за тиском (Па) і температурою (°C): ρ = p / (R·T). */
    fun airDensity(temperatureC: Double, pressurePa: Double = 101_325.0): Double {
        require(temperatureC > -100 && temperatureC < 100) { "Температура поза межами −100…100 °C" }
        require(pressurePa > 0) { "Тиск має бути додатним" }
        return pressurePa / (R_AIR * (temperatureC + 273.15))
    }

    /** Площа, яку омітають лопаті, м². */
    fun sweptArea(rotorRadiusM: Double): Double {
        require(rotorRadiusM > 0) { "Радіус ротора має бути додатним" }
        return PI * rotorRadiusM.pow(2)
    }

    /**
     * Фактична потужність турбіни, кВт, з урахуванням робочої характеристики:
     * нижче швидкості ввімкнення та вище швидкості вимкнення — 0,
     * між номінальною швидкістю і вимкненням — номінальна потужність.
     */
    fun turbinePowerKw(windSpeed: Double, spec: TurbineSpec, temperatureC: Double = 15.0): Double {
        require(windSpeed >= 0) { "Швидкість вітру не може бути від'ємною" }
        if (windSpeed < spec.cutInSpeed || windSpeed >= spec.cutOutSpeed) return 0.0
        val rho = airDensity(temperatureC)
        val theoreticalW = 0.5 * rho * sweptArea(spec.rotorRadius) * windSpeed.pow(3) * spec.powerCoefficient
        return minOf(theoreticalW / 1000.0, spec.ratedPowerKw)
    }

    /** Сумарна потужність станції, МВт. */
    fun farmPowerMw(windSpeed: Double, spec: TurbineSpec, turbines: Int, temperatureC: Double = 15.0): Double {
        require(turbines > 0) { "Кількість турбін має бути більшою за 0" }
        return turbinePowerKw(windSpeed, spec, temperatureC) * turbines / 1000.0
    }

    /** Коефіцієнт використання встановленої потужності, %. */
    fun capacityFactor(powerKw: Double, spec: TurbineSpec): Double =
        (powerKw / spec.ratedPowerKw * 100.0).coerceIn(0.0, 100.0)

    /** Стан турбіни за швидкістю вітру. */
    fun status(windSpeed: Double, spec: TurbineSpec): TurbineStatus = when {
        windSpeed < spec.cutInSpeed -> TurbineStatus.IDLE
        windSpeed >= spec.cutOutSpeed -> TurbineStatus.STORM_STOP
        // номінал може бути досягнутий і трохи раніше ratedSpeed — орієнтуємося на фактичну потужність
        windSpeed >= spec.ratedSpeed || turbinePowerKw(windSpeed, spec) >= spec.ratedPowerKw -> TurbineStatus.RATED
        else -> TurbineStatus.PARTIAL_LOAD
    }

    /** Бал за шкалою Бофорта (0–12) за швидкістю вітру, м/с. */
    fun beaufort(windSpeed: Double): Int {
        val limits = doubleArrayOf(0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7)
        return limits.indexOfFirst { windSpeed < it }.let { if (it == -1) 12 else it }
    }

    fun beaufortName(scale: Int): String = listOf(
        "Штиль", "Тихий", "Легкий", "Слабкий", "Помірний", "Свіжий", "Сильний",
        "Міцний", "Дуже міцний", "Шторм", "Сильний шторм", "Жорстокий шторм", "Ураган"
    )[scale.coerceIn(0, 12)]
}

/** Технічні характеристики турбіни (за замовчуванням — клас 2 МВт). */
data class TurbineSpec(
    val rotorRadius: Double = 45.0,      // м
    val ratedPowerKw: Double = 2000.0,   // кВт
    val powerCoefficient: Double = 0.42, // Cp
    val cutInSpeed: Double = 3.0,        // м/с
    val ratedSpeed: Double = 12.0,       // м/с
    val cutOutSpeed: Double = 25.0       // м/с
) {
    init {
        require(rotorRadius > 0) { "Радіус ротора має бути додатним" }
        require(ratedPowerKw > 0) { "Номінальна потужність має бути додатною" }
        require(powerCoefficient > 0 && powerCoefficient <= WindPowerCalculator.BETZ_LIMIT) {
            "Cp має бути в межах 0…0.593 (межа Бетца)"
        }
        require(cutInSpeed in 0.0..ratedSpeed && ratedSpeed < cutOutSpeed) {
            "Має виконуватись: вмикання ≤ номінальна < вимкнення"
        }
    }
}

enum class TurbineStatus(val title: String) {
    IDLE("Очікування вітру"),
    PARTIAL_LOAD("Часткове навантаження"),
    RATED("Номінальна потужність"),
    STORM_STOP("Аварійна зупинка (буря)")
}
