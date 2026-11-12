package ua.kpi.windfarm3.auth

import android.content.Context
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import ua.kpi.windfarm3.data.remote.ApiClient
import kotlin.math.abs
import kotlin.random.Random

// ------------------------------- сховище токена -------------------------------

/** Сховище токена: ViewModel не знає, де саме він зберігається. */
interface TokenStore {
    var token: String?
}

class MemoryTokenStore : TokenStore {
    @Volatile override var token: String? = null
}

/** Збереження токена в SharedPreferences — сесія переживає перезапуск застосунку. */
class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("auth", Context.MODE_PRIVATE)
    override var token: String?
        get() = prefs.getString(KEY, null)
        set(value) { prefs.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply() }

    private companion object { const val KEY = "access_token" }
}

// ------------------------------- DTO і Retrofit API -------------------------------

data class LoginRequest(val login: String, val password: String, @SerializedName("ttl") val ttlSeconds: Long)
data class LoginResponse(val token: String?, @SerializedName("expires_at") val expiresAt: Long?, val name: String?, val role: String?)
data class MessageDto(val message: String?)
data class SummaryDto(val station: String?, val turbines: Int?, @SerializedName("power_mw") val powerMw: Double?, @SerializedName("wind_ms") val windMs: Double?, val alarms: Int?)
data class TurbineTelemetryDto(val id: Int?, val wind: Double?, @SerializedName("power_kw") val powerKw: Double?, val rpm: Double?, val running: Boolean?, val alarm: String?)

/** Захищене API станції. Базова адреса обслуговується MockServerInterceptor. */
interface StationAuthApi {
    @POST("api/login")
    suspend fun login(@Body body: LoginRequest): retrofit2.Response<LoginResponse>

    @GET("api/station/summary")
    suspend fun summary(): retrofit2.Response<SummaryDto>

    @GET("api/telemetry")
    suspend fun telemetry(): retrofit2.Response<List<TurbineTelemetryDto>>

    @POST("api/alarms/ack")
    suspend fun ackAlarms(): retrofit2.Response<MessageDto>

    @POST("api/turbines/{id}/{action}")
    suspend fun control(@Path("id") id: Int, @Path("action") action: String): retrofit2.Response<MessageDto>

    companion object { const val BASE_URL = "https://auth.windfarm.local/" }
}

// ------------------------------- інтерсептори -------------------------------

/**
 * Автоматично додає заголовок Authorization: Bearer <token> до кожного запиту,
 * крім входу. Токен читається зі сховища в момент запиту, тож після повторного
 * входу нові запити одразу йдуть з новим токеном.
 */
class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val token = store.token
        if (token.isNullOrBlank() || req.url.encodedPath.endsWith("/login")) return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * Імітація сервера (mocked API) на рівні OkHttp: запит не виходить у мережу,
 * інтерсептор сам формує HTTP-відповідь із JSON. Логіку токенів і ролей виконує MockAuthBackend.
 */
class MockServerInterceptor(private val backend: MockAuthBackend) : Interceptor {
    private val gson = ApiClient.gson
    private val stopped = mutableSetOf<Int>()
    @Volatile private var alarms = 2

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != "auth.windfarm.local") return chain.proceed(req)
        Thread.sleep(400) // імітація мережевої затримки
        val path = req.url.encodedPath
        val auth = req.header("Authorization")

        fun respond(code: Int, body: Any) = Response.Builder()
            .request(req).protocol(Protocol.HTTP_1_1).code(code)
            .message(if (code < 400) "OK" else "Error")
            .body(gson.toJson(body).toResponseBody("application/json".toMediaType()))
            .build()

        fun guarded(p: Permission, block: () -> Any): Response = when (val a = backend.authorize(auth, p)) {
            is MockAuthBackend.Access.Denied -> respond(a.code, mapOf("error" to true, "reason" to a.message))
            is MockAuthBackend.Access.Granted -> respond(200, block())
        }

        return when {
            req.method == "POST" && path == "/api/login" -> {
                val raw = Buffer().also { req.body?.writeTo(it) }.readUtf8()
                val body = runCatching { gson.fromJson(raw, LoginRequest::class.java) }.getOrNull()
                when (val r = backend.login(body?.login.orEmpty(), body?.password.orEmpty(), (body?.ttlSeconds ?: 3600).coerceIn(10, 86_400))) {
                    is MockAuthBackend.LoginResult.Success ->
                        respond(200, LoginResponse(r.token, r.claims.expiresAt, r.claims.name, r.claims.role.name))
                    is MockAuthBackend.LoginResult.Failure -> respond(r.code, mapOf("error" to true, "reason" to r.message))
                }
            }
            req.method == "GET" && path == "/api/station/summary" -> guarded(Permission.VIEW_SUMMARY) {
                val t = telemetry()
                SummaryDto("ВЕС «Приазовська»", t.size, t.sumOf { it.powerKw ?: 0.0 } / 1000.0, t.mapNotNull { it.wind }.average(), alarms)
            }
            req.method == "GET" && path == "/api/telemetry" -> guarded(Permission.VIEW_TELEMETRY) { telemetry() }
            req.method == "POST" && path == "/api/alarms/ack" -> guarded(Permission.ACK_ALARMS) {
                val n = alarms; alarms = 0
                MessageDto("Підтверджено тривог: $n")
            }
            req.method == "POST" && path.startsWith("/api/turbines/") -> guarded(Permission.CONTROL_TURBINES) {
                val parts = path.split('/') // ["", "api", "turbines", id, action]
                val id = parts.getOrNull(3)?.toIntOrNull() ?: 0
                val action = parts.getOrNull(4)
                if (action == "stop") stopped += id else stopped -= id
                MessageDto("Турбіну ВТ-$id ${if (action == "stop") "зупинено" else "запущено"}")
            }
            else -> respond(404, mapOf("error" to true, "reason" to "Ресурс $path не знайдено"))
        }
    }

    /** Псевдовипадкова, але стабільна в межах хвилини телеметрія 6 турбін. */
    private fun telemetry(): List<TurbineTelemetryDto> {
        val rnd = Random(System.currentTimeMillis() / 60_000)
        return (1..6).map { id ->
            val wind = (rnd.nextDouble(4.0, 16.0) * 10).toInt() / 10.0
            val running = id !in stopped
            val kw = if (running) ua.kpi.windfarm3.domain.WindPowerCalculator.turbinePowerKw(wind, ua.kpi.windfarm3.domain.TurbineSpec()) else 0.0
            TurbineTelemetryDto(id, wind, kw, if (running) abs(wind * 1.4) else 0.0, running,
                if (id == 4 && alarms > 0) "Вібрація редуктора" else if (id == 2 && alarms > 1) "Перегрів генератора" else null)
        }
    }
}
