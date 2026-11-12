package ua.kpi.windfarm.rgr.domain

/** Погодинна точка прогнозу на висоті маточини. */
data class ForecastHour(val timeMs: Long, val windMs: Double, val temperatureC: Double, val pressureHpa: Double, val gustMs: Double = windMs)

/** Прогноз виробітку станції на одну годину. */
data class EnergyHour(val timeMs: Long, val windMs: Double, val powerMw: Double, val state: TurbineState)

data class EnergyForecast(val hours: List<EnergyHour>, val totalMwh: Double, val capacityFactor: Double, val stormHours: Int, val idleHours: Int) {
    /** Сума по добах: ключ — номер доби від початку прогнозу. */
    fun daily(): List<Double> = hours.chunked(24).map { d -> d.sumOf { it.powerMw } }
}

/**
 * Прогноз виробітку станції з [turbines] турбінами за погодинним прогнозом вітру:
 * для кожної години — густина повітря з температури й тиску, потужність за кривою турбіни.
 * [availability] — частка справних турбін (0…1), враховує заплановане ТО.
 */
object ForecastCalculator {
    fun energy(hours: List<ForecastHour>, turbines: Int, spec: TurbineSpec = TurbineSpec(), availability: Double = 1.0): EnergyForecast {
        require(turbines > 0) { "Кількість турбін має бути додатною" }
        require(availability in 0.0..1.0) { "Готовність 0…1" }
        val e = hours.map { h ->
            val rho = WindPhysics.airDensity(h.temperatureC, h.pressureHpa)
            val kw = WindPhysics.powerKw(h.windMs, spec, rho)
            EnergyHour(h.timeMs, h.windMs, kw * turbines * availability / 1000, WindPhysics.state(h.windMs, spec, rho))
        }
        val total = e.sumOf { it.powerMw }
        return EnergyForecast(e, total, WindPhysics.capacityFactor(total * 1000, spec.ratedPowerKw * turbines, hours.size.toDouble()),
            e.count { it.state == TurbineState.STORM }, e.count { it.state == TurbineState.IDLE })
    }
}

/** Тип тривоги та її важливість. */
enum class AlertType(val title: String, val critical: Boolean) {
    STORM("Шторм: вітер вище швидкості зупинки", true),
    GUSTS("Сильні пориви", true),
    ICING("Ризик обмерзання лопатей", true),
    LOW_OUTPUT("Виробіток нижче очікуваного", false),
    CALM("Тривалий штиль", false),
    SENSOR("Несправність датчика", true)
}

data class AlertEvent(val type: AlertType, val message: String, val value: Double, val timeMs: Long)

/** Пороги правил — налаштовуються користувачем. */
data class AlertThresholds(
    val stormMs: Double = 25.0,
    val gustMs: Double = 30.0,
    val icingTempC: Double = 1.0,
    val lowOutputRatio: Double = 0.6,
    val calmHours: Int = 6
)

/**
 * Правила тривог для вимірів і прогнозу. Повертають список подій, а не показують їх, —
 * тож правила тестуються окремо від сповіщень і бази даних.
 */
object AlertRules {
    /** Перевірка одного виміру: шторм, обмерзання, «застиглий» або неможливий показ датчика. */
    fun checkMeasurement(windMs: Double, temperatureC: Double, humidity: Double?, actualKw: Double, expectedKw: Double,
                         previousWinds: List<Double>, t: AlertThresholds, now: Long): List<AlertEvent> = buildList {
        if (!windMs.isFinite() || windMs < 0 || windMs > 75) { add(AlertEvent(AlertType.SENSOR, "Неможливий показ анемометра: $windMs м/с", windMs, now)); return@buildList }
        if (previousWinds.size >= 10 && previousWinds.takeLast(10).all { it == windMs } && windMs > 0)
            add(AlertEvent(AlertType.SENSOR, "Анемометр видає те саме значення 10 вимірів поспіль", windMs, now))
        if (windMs >= t.stormMs) add(AlertEvent(AlertType.STORM, "Вітер ${"%.1f".format(windMs)} м/с ≥ ${t.stormMs} м/с", windMs, now))
        if (temperatureC <= t.icingTempC && (humidity ?: 0.0) >= 85) add(AlertEvent(AlertType.ICING, "t = ${"%.1f".format(temperatureC)} °C, вологість ${humidity?.toInt()} %", temperatureC, now))
        if (expectedKw > 200 && actualKw < expectedKw * t.lowOutputRatio)
            add(AlertEvent(AlertType.LOW_OUTPUT, "Фактично ${actualKw.toInt()} кВт з очікуваних ${expectedKw.toInt()} кВт", actualKw, now))
    }

    /** Перевірка прогнозу: перша година шторму / поривів і найдовший період штилю. */
    fun checkForecast(hours: List<ForecastHour>, spec: TurbineSpec, t: AlertThresholds): List<AlertEvent> = buildList {
        hours.firstOrNull { it.windMs >= t.stormMs }?.let { add(AlertEvent(AlertType.STORM, "Прогноз шторму: ${"%.1f".format(it.windMs)} м/с", it.windMs, it.timeMs)) }
        hours.firstOrNull { it.gustMs >= t.gustMs }?.let { add(AlertEvent(AlertType.GUSTS, "Прогноз поривів до ${"%.1f".format(it.gustMs)} м/с", it.gustMs, it.timeMs)) }
        var run = 0; var best = 0; var start = 0L; var bestStart = 0L
        hours.forEach { h -> if (h.windMs < spec.cutInMs) { if (run == 0) start = h.timeMs; run++; if (run > best) { best = run; bestStart = start } } else run = 0 }
        if (best >= t.calmHours) add(AlertEvent(AlertType.CALM, "Штиль $best год поспіль — зручне вікно для ТО", best.toDouble(), bestStart))
    }
}
