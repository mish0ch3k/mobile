package ua.kpi.windfarm3.domain

/**
 * Вітрова електростанція — об'єкт моніторингу (варіант 2).
 * Координати потрібні, щоб запитати прогноз вітру для місця розташування станції.
 */
data class Station(
    val id: Long = 0,
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val turbines: Int
) {
    /** Встановлена потужність станції, МВт (турбіни класу 2 МВт). */
    val installedMw: Double get() = turbines * TurbineSpec().ratedPowerKw / 1000.0
}

/** Перевірка полів станції перед збереженням. Повертає текст помилки або null. */
object StationValidator {
    fun name(v: String): String? = when {
        v.isBlank() -> "Введіть назву"
        v.trim().length < 3 -> "Щонайменше 3 символи"
        else -> null
    }

    fun latitude(v: String): String? = number(v, -90.0..90.0, "Широта −90…90")
    fun longitude(v: String): String? = number(v, -180.0..180.0, "Довгота −180…180")

    fun turbines(v: String): String? {
        val n = v.trim().toIntOrNull() ?: return "Ціле число"
        return if (n in 1..500) null else "1…500 турбін"
    }

    private fun number(v: String, range: ClosedFloatingPointRange<Double>, msg: String): String? {
        val x = parse(v) ?: return "Число"
        return if (x in range) null else msg
    }

    /** Приймає і крапку, і кому як десятковий роздільник. */
    fun parse(v: String): Double? = v.trim().replace(',', '.').toDoubleOrNull()

    fun errors(name: String, lat: String, lon: String, turbines: String): List<String?> =
        listOf(name(name), latitude(lat), longitude(lon), turbines(turbines))
}

/**
 * Демонстраційні станції у вітрових регіонах півдня України.
 * Назви умовні; координати — реальні точки відповідних районів.
 */
object DemoStations {
    val all = listOf(
        Station(name = "ВЕС «Приазовська»", region = "Запорізька обл.", latitude = 46.70, longitude = 35.25, turbines = 60),
        Station(name = "ВЕС «Тилігул»", region = "Миколаївська обл.", latitude = 46.93, longitude = 31.27, turbines = 20),
        Station(name = "ВЕС «Узбережжя»", region = "Одеська обл.", latitude = 46.17, longitude = 30.33, turbines = 35),
        Station(name = "ВЕС «Степова»", region = "Херсонська обл.", latitude = 46.48, longitude = 33.60, turbines = 25),
        Station(name = "ВЕС «Карпатська»", region = "Львівська обл.", latitude = 49.03, longitude = 23.60, turbines = 12)
    )
}
