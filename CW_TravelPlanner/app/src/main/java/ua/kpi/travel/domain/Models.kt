package ua.kpi.travel.domain

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/** Тип подорожі: впливає на шаблон речей і категорії активностей за замовчуванням. */
enum class TripType(val title: String) {
    CITY("Місто"), BEACH("Море"), MOUNTAINS("Гори"), BUSINESS("Відрядження"), ROAD("Автоподорож")
}

enum class ActivityCategory(val title: String) {
    TRANSPORT("Транспорт"), STAY("Проживання"), SIGHTSEEING("Екскурсія"), FOOD("Їжа"),
    ACTIVE("Активний відпочинок"), MEETING("Зустріч"), OTHER("Інше")
}

enum class ExpenseCategory(val title: String) {
    TRANSPORT("Транспорт"), STAY("Проживання"), FOOD("Їжа"), TICKETS("Квитки й екскурсії"),
    SHOPPING("Покупки"), INSURANCE("Страховка"), OTHER("Інше")
}

enum class PackingCategory(val title: String) {
    DOCUMENTS("Документи"), CLOTHES("Одяг"), HYGIENE("Гігієна"), ELECTRONICS("Електроніка"),
    HEALTH("Аптечка"), GEAR("Спорядження"), OTHER("Інше")
}

/**
 * Подорож. Дати зберігаються як epochDay (кількість днів від 1970-01-01) — так їх зручно
 * порівнювати в SQL і не залежати від часового поясу.
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val destination: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val startDay: Long,
    val endDay: Long,
    val budget: Double = 0.0,
    val currency: String = "UAH",
    val type: TripType = TripType.CITY,
    val travelers: Int = 1,
    val notes: String = ""
) {
    val start: LocalDate get() = LocalDate.ofEpochDay(startDay)
    val end: LocalDate get() = LocalDate.ofEpochDay(endDay)
    /** Тривалість у днях включно з днями виїзду й повернення. */
    val days: Int get() = (endDay - startDay + 1).toInt()
}

/** Пункт маршруту: подія певного дня з часом початку і тривалістю. */
@Entity(
    tableName = "activities",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val day: Long,                 // epochDay
    val startMinute: Int,          // хвилин від початку доби, 0..1439
    val durationMin: Int,
    val title: String,
    val place: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val category: ActivityCategory = ActivityCategory.SIGHTSEEING,
    val cost: Double = 0.0,
    val done: Boolean = false,
    val remind: Boolean = false
) {
    val endMinute: Int get() = startMinute + durationMin
}

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val title: String,
    val amount: Double,
    val currency: String,
    val category: ExpenseCategory,
    val day: Long
)

@Entity(
    tableName = "packing",
    foreignKeys = [ForeignKey(entity = Trip::class, parentColumns = ["id"], childColumns = ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")]
)
data class PackingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val category: PackingCategory,
    val quantity: Int = 1,
    val packed: Boolean = false
)

/** Добовий прогноз для пункту призначення. */
data class DayWeather(
    val day: LocalDate,
    val tMin: Double,
    val tMax: Double,
    val precipitationProbability: Int,
    val code: Int
) {
    val description: String get() = WeatherCodes.describe(code)
}

/** Знайдене геокодуванням місце. */
data class Place(val name: String, val country: String, val region: String, val latitude: Double, val longitude: Double) {
    val label: String get() = listOf(name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

/** Розшифровка кодів погоди WMO, які повертає Open-Meteo. */
object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2 -> "Мінлива хмарність"
        3 -> "Хмарно"
        45, 48 -> "Туман"
        51, 53, 55, 56, 57 -> "Мряка"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Дощ"
        71, 73, 75, 77, 85, 86 -> "Сніг"
        95, 96, 99 -> "Гроза"
        else -> "Невідомо"
    }

    fun isBad(code: Int): Boolean = code >= 61
}
