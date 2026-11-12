package ua.kpi.windfarm6.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Тип станції — для фільтрації та звітів. */
@Serializable
enum class StationType(val title: String) {
    @SerialName("onshore") ONSHORE("Наземна"),
    @SerialName("offshore") OFFSHORE("Морська"),
    @SerialName("hybrid") HYBRID("Гібридна (ВЕС + СЕС)")
}

/** Стан станції за добу. */
@Serializable
enum class StationStatus(val title: String) {
    @SerialName("normal") NORMAL("Норма"),
    @SerialName("limited") LIMITED("Обмеження"),
    @SerialName("storm") STORM("Штормова зупинка"),
    @SerialName("maintenance") MAINTENANCE("ТО")
}

/**
 * Добовий запис виробітку станції.
 * [date] зберігається як «yyyy-MM-dd»: такий рядок сортується так само, як дата,
 * і не потребує java.time (недоступного на minSdk 24 без desugaring).
 */
@Serializable
data class EnergyRecord(
    val id: Long,
    val station: String,
    val type: StationType,
    val date: String,
    @SerialName("energy_mwh") val energyMwh: Double,
    @SerialName("avg_wind_ms") val avgWind: Double,
    val status: StationStatus = StationStatus.NORMAL,
    val note: String = ""
)

/** Обгортка JSON-файлу: версія формату дозволяє змінювати структуру без втрати старих файлів. */
@Serializable
data class RecordsFile(val version: Int = 1, val generated: String = "", val records: List<EnergyRecord> = emptyList())

/** Перевірка запису перед збереженням. Повертає список помилок (порожній — запис коректний). */
object RecordValidator {
    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

    fun validate(r: EnergyRecord): List<String> = buildList {
        if (r.station.isBlank()) add("Не вказано станцію")
        if (r.station.contains('\n')) add("Назва станції містить перенесення рядка")
        if (!DATE.matches(r.date) || Dates.parse(r.date) == null) add("Дата має формат РРРР-ММ-ДД")
        if (!r.energyMwh.isFinite() || r.energyMwh < 0) add("Виробіток має бути невід'ємним числом")
        if (r.energyMwh > 10_000) add("Виробіток понад 10 000 МВт·год за добу — помилка введення?")
        if (!r.avgWind.isFinite() || r.avgWind !in 0.0..60.0) add("Середній вітер 0…60 м/с")
    }
}

/** Робота з датами «yyyy-MM-dd» без java.time. */
object Dates {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }

    fun parse(s: String): Long? = runCatching { fmt().parse(s)?.time }.getOrNull()
    fun format(ms: Long): String = fmt().format(ms)
    fun today(): String = format(System.currentTimeMillis())

    /** Дата, зсунута на [days] діб. */
    fun plusDays(date: String, days: Int): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = parse(date) ?: System.currentTimeMillis()
        c.add(Calendar.DAY_OF_MONTH, days)
        return format(c.timeInMillis)
    }

    /** «2026-11-05» → «05.11». */
    fun short(date: String) = if (date.length == 10) "${date.substring(8)}.${date.substring(5, 7)}" else date
}

/** Демонстраційні дані: 30 діб для п'яти станцій (детерміновано). */
object SampleData {
    val stations = listOf(
        "Приазовська" to StationType.ONSHORE, "Тилігульська" to StationType.ONSHORE,
        "Одеса-Офшор" to StationType.OFFSHORE, "Ботієвська" to StationType.ONSHORE, "Степова-Гібрид" to StationType.HYBRID
    )

    fun generate(days: Int = 30, until: String = Dates.today()): List<EnergyRecord> {
        val rnd = kotlin.random.Random(6)
        var id = 1L
        return (days - 1 downTo 0).flatMap { back ->
            val date = Dates.plusDays(until, -back)
            stations.mapIndexed { i, (name, type) ->
                val wind = (6.5 + i * 0.6 + rnd.nextDouble(-3.0, 4.5)).coerceIn(1.0, 27.0)
                val status = when {
                    wind > 24 -> StationStatus.STORM
                    rnd.nextInt(30) == 0 -> StationStatus.MAINTENANCE
                    else -> StationStatus.NORMAL
                }
                val capacity = listOf(200.0, 76.0, 150.0, 200.0, 60.0)[i]
                val factor = (wind / 13.0).let { it * it * it }.coerceAtMost(1.0) * if (status == StationStatus.NORMAL) 0.95 else 0.2
                EnergyRecord(id++, name, type, date, Math.round(capacity * 24 * factor * 10) / 10.0, Math.round(wind * 10) / 10.0, status)
            }
        }
    }
}

/** Спільний налаштований Json: стійкий до нових полів і пропущених значень. */
val AppJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true   // старий застосунок прочитає файл нової версії
    coerceInputValues = true   // невідоме значення enum → значення за замовчуванням
    encodeDefaults = true
}
