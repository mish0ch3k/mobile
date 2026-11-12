package ua.kpi.windfarm3.data.remote

import com.google.gson.annotations.SerializedName
import ua.kpi.windfarm3.domain.HourlyWind
import ua.kpi.windfarm3.errors.InvalidDataException

/*
 * DTO — «сирі» класи відповіді Open-Meteo, у які Gson розбирає JSON.
 * Усі поля nullable: Gson не перевіряє Kotlin-nullability, тож перевірку
 * виконує OpenMeteoMapper, перетворюючи DTO на доменні моделі.
 */

/** GET https://api.open-meteo.com/v1/forecast — вкладені об'єкти current і hourly. */
data class ForecastDto(
    val latitude: Double?,
    val longitude: Double?,
    val timezone: String?,
    val elevation: Double?,
    @SerializedName("current_units") val currentUnits: Map<String, String>?,
    val current: CurrentDto?,
    val hourly: HourlyDto?
)

data class CurrentDto(
    val time: String?,
    @SerializedName("wind_speed_10m") val windSpeed10m: Double?,
    @SerializedName("wind_direction_10m") val windDirection10m: Double?,
    @SerializedName("wind_gusts_10m") val windGusts10m: Double?,
    @SerializedName("temperature_2m") val temperature2m: Double?
)

/** Масиви однакової довжини: i-й елемент кожного масиву відповідає i-й годині. */
data class HourlyDto(
    val time: List<String>?,
    @SerializedName("wind_speed_100m") val windSpeed100m: List<Double?>?,
    @SerializedName("temperature_2m") val temperature2m: List<Double?>?
)

/** GET https://geocoding-api.open-meteo.com/v1/search — масив results (може бути відсутній). */
data class GeoResponseDto(val results: List<GeoPlaceDto>?)

data class GeoPlaceDto(
    val id: Long?,
    val name: String?,
    val latitude: Double?,
    val longitude: Double?,
    val elevation: Double?,
    val country: String?,
    @SerializedName("admin1") val region: String?,
    val population: Long?
)

/** Тіло помилки Open-Meteo: {"error": true, "reason": "..."} */
data class ApiErrorDto(val error: Boolean?, val reason: String?)

// ----------------------------- доменні моделі -----------------------------

data class CurrentWind(
    val time: String,
    val speed10: Double,
    val directionDeg: Double,
    val gusts10: Double,
    val temperature: Double
) {
    /** Напрям вітру словами (звідки дме). */
    val directionName: String
        get() = listOf("Пн", "ПнСх", "Сх", "ПдСх", "Пд", "ПдЗх", "Зх", "ПнЗх")[(((directionDeg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]
}

data class WindForecast(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val current: CurrentWind,
    val hourly: List<HourlyWind>
)

data class GeoPlace(val name: String, val region: String, val country: String, val latitude: Double, val longitude: Double, val population: Long?)

/** Перевірка отриманих даних і перетворення DTO → домен. */
object OpenMeteoMapper {

    fun toForecast(dto: ForecastDto?): WindForecast {
        if (dto == null) throw InvalidDataException("порожня відповідь")
        val c = dto.current ?: throw InvalidDataException("немає блоку current")
        val current = CurrentWind(
            time = c.time ?: throw InvalidDataException("немає часу виміру"),
            speed10 = check(c.windSpeed10m, "wind_speed_10m", 0.0..100.0),
            directionDeg = check(c.windDirection10m, "wind_direction_10m", 0.0..360.0),
            gusts10 = check(c.windGusts10m, "wind_gusts_10m", 0.0..150.0),
            temperature = check(c.temperature2m, "temperature_2m", -80.0..60.0)
        )
        return WindForecast(
            latitude = dto.latitude ?: throw InvalidDataException("немає широти"),
            longitude = dto.longitude ?: throw InvalidDataException("немає довготи"),
            timezone = dto.timezone ?: "GMT",
            current = current,
            hourly = toHourly(dto.hourly)
        )
    }

    /** Години з пропущеними значеннями (null) відкидаються, різна довжина масивів — помилка. */
    fun toHourly(h: HourlyDto?): List<HourlyWind> {
        if (h == null) return emptyList()
        val times = h.time ?: throw InvalidDataException("немає масиву hourly.time")
        val speeds = h.windSpeed100m ?: throw InvalidDataException("немає масиву wind_speed_100m")
        val temps = h.temperature2m ?: List(times.size) { 15.0 }
        if (speeds.size != times.size || temps.size != times.size) throw InvalidDataException("масиви hourly різної довжини")
        return times.indices.mapNotNull { i ->
            val v = speeds[i] ?: return@mapNotNull null
            if (v < 0 || v > 100) throw InvalidDataException("швидкість вітру поза межами: $v")
            HourlyWind(times[i], v, temps[i] ?: 15.0)
        }
    }

    fun toPlaces(dto: GeoResponseDto?): List<GeoPlace> = dto?.results.orEmpty().mapNotNull { p ->
        val lat = p.latitude ?: return@mapNotNull null
        val lon = p.longitude ?: return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        GeoPlace(p.name ?: "?", p.region.orEmpty(), p.country.orEmpty(), lat, lon, p.population)
    }

    private fun check(v: Double?, field: String, range: ClosedFloatingPointRange<Double>): Double {
        if (v == null) throw InvalidDataException("немає поля $field")
        if (v !in range) throw InvalidDataException("$field = $v поза межами")
        return v
    }
}
