package ua.kpi.windfarm3.domain

/** Погодинний прогноз вітру на висоті маточини турбіни (100 м). */
data class HourlyWind(
    val time: String,          // ISO 8601, місцевий час станції: 2026-11-11T14:00
    val speed100: Double,      // м/с
    val temperature: Double    // °C
) {
    /** Година доби «14:00» з рядка часу. */
    val hourLabel: String get() = time.substringAfter('T', time).take(5)
    val dateLabel: String get() = time.substringBefore('T')
}

/** Агрегований прогноз виробітку станції. */
data class EnergyForecast(
    val hours: Int,
    val energyMwh: Double,
    val capacityFactor: Double,   // %
    val productiveHours: Int,     // години, коли турбіни генерують
    val stormHours: Int,          // години аварійної зупинки
    val avgSpeed: Double,
    val peak: HourlyWind?
)

/**
 * Бізнес-логіка прогнозування на основі даних REST API:
 * перерахунок прогнозу вітру у виробіток електроенергії станції.
 */
object ForecastAnalytics {

    fun powerMw(h: HourlyWind, turbines: Int, spec: TurbineSpec = TurbineSpec()): Double =
        WindPowerCalculator.turbinePowerKw(h.speed100.coerceAtLeast(0.0), spec, h.temperature.coerceIn(-50.0, 50.0)) * turbines / 1000.0

    /** Кожен запис — одна година, тож енергія (МВт·год) = сума потужностей (МВт). */
    fun forecast(hours: List<HourlyWind>, turbines: Int, spec: TurbineSpec = TurbineSpec()): EnergyForecast {
        require(turbines > 0) { "Кількість турбін має бути більшою за 0" }
        if (hours.isEmpty()) return EnergyForecast(0, 0.0, 0.0, 0, 0, 0.0, null)
        val powers = hours.map { powerMw(it, turbines, spec) }
        val energy = powers.sum()
        val maxEnergy = turbines * spec.ratedPowerKw / 1000.0 * hours.size
        return EnergyForecast(
            hours = hours.size,
            energyMwh = energy,
            capacityFactor = energy / maxEnergy * 100.0,
            productiveHours = powers.count { it > 0.0 },
            stormHours = hours.count { it.speed100 >= spec.cutOutSpeed },
            avgSpeed = hours.map { it.speed100 }.average(),
            peak = hours.maxByOrNull { it.speed100 }
        )
    }

    /** Фільтр «лише години, коли станція генерує». */
    fun productive(hours: List<HourlyWind>, spec: TurbineSpec = TurbineSpec()): List<HourlyWind> =
        hours.filter { it.speed100 >= spec.cutInSpeed && it.speed100 < spec.cutOutSpeed }

    enum class Sort(val title: String) { TIME("За часом"), SPEED_DESC("Вітер ↓"), SPEED_ASC("Вітер ↑") }

    fun sort(hours: List<HourlyWind>, sort: Sort): List<HourlyWind> = when (sort) {
        Sort.TIME -> hours.sortedBy { it.time }
        Sort.SPEED_DESC -> hours.sortedByDescending { it.speed100 }
        Sort.SPEED_ASC -> hours.sortedBy { it.speed100 }
    }

    /**
     * Найкраще вікно для техобслуговування: [length] годин поспіль з найменшим
     * сумарним вітром — станція втратить найменше енергії. null, якщо годин замало.
     */
    fun maintenanceWindow(hours: List<HourlyWind>, length: Int = 3): List<HourlyWind>? {
        require(length >= 1)
        if (hours.size < length) return null
        val start = (0..hours.size - length).minBy { i -> (i until i + length).sumOf { hours[it].speed100 } }
        return hours.subList(start, start + length)
    }
}
