package ua.kpi.windfarm.rgr.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST API Open-Meteo (безкоштовний, без ключа): погодинний прогноз вітру на висоті 100 м
 * (висота маточини турбіни), поривів, температури й тиску. Час — у секундах Unix (timeformat=unixtime).
 */
interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "wind_speed_100m,wind_gusts_10m,temperature_2m,surface_pressure,relative_humidity_2m",
        @Query("current") current: String = "wind_speed_10m,wind_direction_10m,temperature_2m,surface_pressure,relative_humidity_2m",
        @Query("forecast_days") days: Int = 3,
        @Query("wind_speed_unit") unit: String = "ms",
        @Query("timeformat") timeFormat: String = "unixtime",
        @Query("timezone") timezone: String = "auto"
    ): ForecastResponse

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

data class ForecastResponse(
    val latitude: Double,
    val longitude: Double,
    val hourly: HourlyDto?,
    val current: CurrentDto?
)

data class HourlyDto(
    val time: List<Long>,
    @SerializedName("wind_speed_100m") val wind100m: List<Double?>,
    @SerializedName("wind_gusts_10m") val gusts: List<Double?>?,
    @SerializedName("temperature_2m") val temperature: List<Double?>,
    @SerializedName("surface_pressure") val pressure: List<Double?>?,
    @SerializedName("relative_humidity_2m") val humidity: List<Double?>?
)

data class CurrentDto(
    val time: Long,
    @SerializedName("wind_speed_10m") val windMs: Double?,
    @SerializedName("wind_direction_10m") val windDir: Double?,
    @SerializedName("temperature_2m") val temperature: Double?,
    @SerializedName("surface_pressure") val pressure: Double?,
    @SerializedName("relative_humidity_2m") val humidity: Double?
)

/** Помилки мережі, зрозумілі користувачеві. */
sealed class NetworkError(message: String) : Exception(message) {
    class NoConnection : NetworkError("Немає з'єднання з сервером прогнозу")
    class Http(code: Int) : NetworkError("Сервер прогнозу відповів помилкою $code")
    class BadData(what: String) : NetworkError("Некоректні дані прогнозу: $what")
}

/** Перетворення винятків Retrofit/OkHttp на NetworkError. */
fun Throwable.toNetworkError(): NetworkError = when (this) {
    is NetworkError -> this
    is HttpException -> NetworkError.Http(code())
    is IOException -> NetworkError.NoConnection()
    else -> NetworkError.BadData(message ?: javaClass.simpleName)
}

object ApiFactory {
    fun openMeteo(baseUrl: String = OpenMeteoApi.BASE_URL): OpenMeteoApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build())
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenMeteoApi::class.java)
}
