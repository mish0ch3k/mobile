package ua.kpi.windfarm3.data.remote

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import ua.kpi.windfarm3.errors.ApiHttpException
import ua.kpi.windfarm3.errors.InvalidDataException
import java.util.concurrent.TimeUnit

/**
 * REST API прогнозу погоди Open-Meteo (безкоштовний, без ключа).
 * Швидкість вітру запитується в м/с; wind_speed_100m — висота маточини турбіни.
 */
interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun forecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "wind_speed_10m,wind_direction_10m,wind_gusts_10m,temperature_2m",
        @Query("hourly") hourly: String = "wind_speed_100m,temperature_2m",
        @Query("forecast_days") days: Int = 2,
        @Query("wind_speed_unit") unit: String = "ms",
        @Query("timezone") timezone: String = "auto"
    ): Response<ForecastDto>

    /** Свідомо неіснуючий шлях — для демонстрації обробки HTTP 404. */
    @GET("v1/no-such-endpoint")
    suspend fun missing(@Query("latitude") latitude: Double, @Query("longitude") longitude: Double): Response<ForecastDto>

    companion object { const val BASE_URL = "https://api.open-meteo.com/" }
}

/** Другий REST-ресурс: пошук населених пунктів за назвою (вкладений масив results). */
interface GeocodingApi {
    @GET("v1/search")
    suspend fun search(
        @Query("name") name: String,
        @Query("count") count: Int = 5,
        @Query("language") language: String = "uk",
        @Query("format") format: String = "json"
    ): Response<GeoResponseDto>

    companion object { const val BASE_URL = "https://geocoding-api.open-meteo.com/" }
}

/**
 * Фабрика Retrofit. Один OkHttpClient на базову адресу; інтерсептори додаються
 * у порядку виклику: спочатку власні (мережа, автентифікація, імітація збоїв),
 * останнім — журнал HTTP у Logcat.
 */
object ApiClient {
    val gson: Gson = Gson()

    fun okHttp(vararg interceptors: Interceptor, timeoutSec: Long = 10): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .apply { interceptors.forEach { addInterceptor(it) } }
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()

    fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson)) // Gson: JSON ⇄ DTO
            .build()

    inline fun <reified T> create(baseUrl: String, vararg interceptors: Interceptor, timeoutSec: Long = 10): T =
        retrofit(baseUrl, okHttp(*interceptors, timeoutSec = timeoutSec)).create(T::class.java)
}

/**
 * Перетворює Retrofit Response на тіло або виняток:
 * код 4xx/5xx → ApiHttpException з причиною з JSON-тіла помилки, порожнє тіло → InvalidDataException.
 */
fun <T> Response<T>.bodyOrThrow(): T {
    if (!isSuccessful) {
        val raw = runCatching { errorBody()?.string() }.getOrNull()
        val reason = raw?.let { runCatching { ApiClient.gson.fromJson(it, ApiErrorDto::class.java)?.reason }.getOrNull() }
        throw ApiHttpException(code(), reason ?: message().takeIf { it.isNotBlank() })
    }
    return body() ?: throw InvalidDataException("порожнє тіло відповіді")
}
