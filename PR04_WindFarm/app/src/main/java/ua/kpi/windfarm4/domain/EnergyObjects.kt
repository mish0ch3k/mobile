package ua.kpi.windfarm4.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Типи енергетичних об'єктів інфраструктури вітроенергетики. */
enum class ObjectType(val title: String, val short: String) {
    WIND_FARM("Вітрова електростанція", "ВЕС"),
    TURBINE("Вітрова турбіна", "Турбіна"),
    MET_MAST("Метеощогла", "Метео"),
    SUBSTATION("Підстанція", "ПС"),
    STORAGE("Накопичувач енергії", "BESS")
}

/** Стан роботи об'єкта. */
enum class ObjectStatus(val title: String) {
    WORKING("Працює"),
    MAINTENANCE("Обслуговування"),
    STORM_STOP("Зупинка (буря)"),
    OFFLINE("Немає зв'язку")
}

/**
 * Енергетичний об'єкт для списків і мапи.
 * [capacityMw] — встановлена потужність (для метеощогли — 0), [windSpeed] — поточний вітер, м/с.
 */
data class EnergyObject(
    val id: Int,
    val name: String,
    val type: ObjectType,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val capacityMw: Double,
    val status: ObjectStatus,
    val windSpeed: Double,
    val updatedAt: Long,
    val description: String
) {
    /** Поточна потужність: для генерувальних об'єктів — за кривою турбіни 2 МВт. */
    val powerMw: Double
        get() = when {
            status != ObjectStatus.WORKING -> 0.0
            type == ObjectType.WIND_FARM || type == ObjectType.TURBINE ->
                WindPowerCalculator.turbinePowerKw(windSpeed, TurbineSpec()) / TurbineSpec().ratedPowerKw * capacityMw
            else -> 0.0
        }

    val generates: Boolean get() = type == ObjectType.WIND_FARM || type == ObjectType.TURBINE
    val loadPercent: Double get() = if (capacityMw > 0 && generates) powerMw / capacityMw * 100 else 0.0
}

/** Довідник об'єктів (імітаційне джерело даних). Назви умовні, координати — реальні точки регіонів. */
object EnergyCatalog {
    private data class Seed(val name: String, val type: ObjectType, val region: String, val lat: Double, val lon: Double, val cap: Double, val desc: String)

    private val seeds = listOf(
        Seed("ВЕС «Приазовська»", ObjectType.WIND_FARM, "Запорізька", 46.70, 35.25, 120.0, "60 турбін по 2 МВт на узбережжі Азовського моря; найвітряніший майданчик парку."),
        Seed("ВЕС «Бердянська коса»", ObjectType.WIND_FARM, "Запорізька", 46.66, 36.70, 90.0, "Прибережна станція, сильні вітри восени та взимку."),
        Seed("ВЕС «Тилігул»", ObjectType.WIND_FARM, "Миколаївська", 46.93, 31.27, 40.0, "Станція біля Тилігульського лиману, 20 турбін."),
        Seed("ВЕС «Узбережжя»", ObjectType.WIND_FARM, "Одеська", 46.17, 30.33, 70.0, "Станція на чорноморському узбережжі, 35 турбін."),
        Seed("ВЕС «Степова»", ObjectType.WIND_FARM, "Херсонська", 46.48, 33.60, 50.0, "Степовий майданчик з рівномірним вітровим потенціалом."),
        Seed("ВЕС «Сиваш»", ObjectType.WIND_FARM, "Херсонська", 46.10, 34.40, 250.0, "Найбільша станція парку, 125 турбін на березі Сиваша."),
        Seed("ВЕС «Карпатська»", ObjectType.WIND_FARM, "Львівська", 49.03, 23.60, 24.0, "Гірська станція на хребті, 12 турбін."),
        Seed("ВЕС «Дунайська»", ObjectType.WIND_FARM, "Одеська", 45.45, 29.20, 32.0, "Станція в пониззі Дунаю, 16 турбін."),
        Seed("Турбіна ВТ-17 «Приазовська»", ObjectType.TURBINE, "Запорізька", 46.72, 35.29, 2.0, "Окрема турбіна з розширеною телеметрією редуктора."),
        Seed("Турбіна ВТ-04 «Тилігул»", ObjectType.TURBINE, "Миколаївська", 46.95, 31.31, 2.0, "Пілотна турбіна з системою раннього виявлення обмерзання."),
        Seed("Метеощогла «Азов-1»", ObjectType.MET_MAST, "Запорізька", 46.68, 35.18, 0.0, "Щогла 100 м: анемометри на 10, 60 і 100 м."),
        Seed("Метеощогла «Тилігул-М»", ObjectType.MET_MAST, "Миколаївська", 46.90, 31.21, 0.0, "Вимірювання вітру для оцінки нового майданчика."),
        Seed("Метеощогла «Карпати-М»", ObjectType.MET_MAST, "Львівська", 49.06, 23.54, 0.0, "Гірська щогла з датчиком обмерзання."),
        Seed("ПС 150 кВ «Приморська»", ObjectType.SUBSTATION, "Запорізька", 46.74, 35.42, 200.0, "Видача потужності ВЕС «Приазовська» та «Бердянська коса»."),
        Seed("ПС 110 кВ «Очаківська»", ObjectType.SUBSTATION, "Миколаївська", 46.62, 31.55, 100.0, "Підключення ВЕС «Тилігул» до мережі."),
        Seed("ПС 150 кВ «Арцизька»", ObjectType.SUBSTATION, "Одеська", 45.99, 29.42, 150.0, "Вузол видачі потужності одеських станцій."),
        Seed("Накопичувач «Степ-BESS»", ObjectType.STORAGE, "Херсонська", 46.50, 33.66, 20.0, "Літій-залізо-фосфатні батареї 20 МВт / 40 МВт·год."),
        Seed("Накопичувач «Одеса-BESS»", ObjectType.STORAGE, "Одеська", 46.40, 30.70, 30.0, "Згладжування піків генерації узбережних станцій.")
    )

    /** Початковий стан: вітер і статус генеруються детерміновано (seed), щоб дані були стабільні. */
    fun initial(now: Long = System.currentTimeMillis(), seed: Int = 2): List<EnergyObject> {
        val rnd = Random(seed)
        return seeds.mapIndexed { i, s ->
            val wind = if (s.type == ObjectType.SUBSTATION || s.type == ObjectType.STORAGE) 0.0 else round1(rnd.nextDouble(2.0, 16.0))
            val status = when (i) {
                4 -> ObjectStatus.MAINTENANCE
                7 -> ObjectStatus.OFFLINE
                else -> ObjectStatus.WORKING
            }
            EnergyObject(i + 1, s.name, s.type, s.region, s.lat, s.lon, s.cap, status, wind, now - rnd.nextLong(1, 180) * 60_000, s.desc)
        }
    }

    val regions: List<String> get() = seeds.map { it.region }.distinct().sorted()
}

internal fun round1(v: Double) = (v * 10).toLong() / 10.0

/** Географічні обчислення. */
object Geo {
    private const val EARTH_KM = 6371.0

    /** Відстань між точками за формулою гаверсинусів, км. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_KM * asin(sqrt(a))
    }
}

/** Точка на мапі (позиція користувача). */
data class UserLocation(val latitude: Double, val longitude: Double, val accuracyM: Float = 0f, val provider: String = "")
