package ua.kpi.travel.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Геокодування Open-Meteo: назва міста → координати (ключ не потрібен). */
interface GeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 5,
        @Query("language") language: String = "uk"
    ): GeocodingResponse

    companion object { const val BASE_URL = "https://geocoding-api.open-meteo.com/" }
}

data class GeocodingResponse(val results: List<GeoResultDto>?)
data class GeoResultDto(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String?,
    @SerializedName("admin1") val region: String?
)

/** Добовий прогноз Open-Meteo (до 16 днів наперед). */
interface WeatherApi {
    @GET("v1/forecast")
    suspend fun daily(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("daily") daily: String = "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
        @Query("forecast_days") days: Int = 16,
        @Query("timezone") timezone: String = "auto"
    ): DailyResponse

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

data class DailyResponse(val daily: DailyDto?)
data class DailyDto(
    val time: List<String>,
    @SerializedName("temperature_2m_max") val tMax: List<Double?>,
    @SerializedName("temperature_2m_min") val tMin: List<Double?>,
    @SerializedName("precipitation_probability_max") val precipitation: List<Int?>?,
    @SerializedName("weather_code") val code: List<Int?>
)

/** Курси валют ExchangeRate-API (відкритий ендпоінт без ключа, оновлення раз на добу). */
interface RatesApi {
    @GET("v6/latest/{base}")
    suspend fun latest(@Path("base") base: String = "UAH"): RatesResponse

    companion object { const val BASE_URL = "https://open.er-api.com/" }
}

data class RatesResponse(val result: String, val rates: Map<String, Double>?)

sealed class NetworkError(message: String) : Exception(message) {
    class NoConnection : NetworkError("Немає з'єднання з інтернетом")
    class Http(code: Int) : NetworkError("Сервер відповів помилкою $code")
    class BadData(what: String) : NetworkError("Некоректна відповідь сервера: $what")
}

fun Throwable.toNetworkError(): NetworkError = when (this) {
    is NetworkError -> this
    is HttpException -> NetworkError.Http(code())
    is IOException -> NetworkError.NoConnection()
    else -> NetworkError.BadData(message ?: javaClass.simpleName)
}

object ApiFactory {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl).client(client).addConverterFactory(GsonConverterFactory.create()).build()

    fun geocoding(): GeocodingApi = retrofit(GeocodingApi.BASE_URL).create(GeocodingApi::class.java)
    fun weather(): WeatherApi = retrofit(WeatherApi.BASE_URL).create(WeatherApi::class.java)
    fun rates(): RatesApi = retrofit(RatesApi.BASE_URL).create(RatesApi::class.java)
}
