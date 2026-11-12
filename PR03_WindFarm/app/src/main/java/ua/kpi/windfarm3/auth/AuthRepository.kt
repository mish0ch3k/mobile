package ua.kpi.windfarm3.auth

import ua.kpi.windfarm3.data.remote.ApiClient
import ua.kpi.windfarm3.data.remote.bodyOrThrow
import ua.kpi.windfarm3.errors.InvalidDataException

/**
 * Repository автентифікації: вхід через API, збереження токена, доступ до захищених ресурсів.
 * ViewModel не працює з Retrofit і сховищем напряму — лише з цим класом.
 */
class AuthRepository(
    val store: TokenStore,
    backend: MockAuthBackend = MockAuthBackend()
) {
    // Порядок інтерсепторів важливий: спочатку додається токен, потім «сервер» його перевіряє
    private val api: StationAuthApi = ApiClient.create(
        StationAuthApi.BASE_URL,
        AuthInterceptor(store),
        MockServerInterceptor(backend)
    )

    /** Дані поточної сесії, прочитані з токена на клієнті (без перевірки підпису). */
    fun session(): TokenClaims? = store.token?.let(Jwt::decode)

    fun isExpired(nowSec: Long = System.currentTimeMillis() / 1000): Boolean = session()?.isExpired(nowSec) ?: true

    suspend fun login(login: String, password: String, ttlSeconds: Long): TokenClaims {
        val r = api.login(LoginRequest(login.trim(), password, ttlSeconds)).bodyOrThrow()
        val token = r.token ?: throw InvalidDataException("сервер не повернув токен")
        val claims = Jwt.decode(token) ?: throw InvalidDataException("некоректний токен")
        store.token = token
        return claims
    }

    fun logout() { store.token = null }

    suspend fun summary(): SummaryDto = api.summary().bodyOrThrow()
    suspend fun telemetry(): List<TurbineTelemetryDto> = api.telemetry().bodyOrThrow()
    suspend fun ackAlarms(): String = api.ackAlarms().bodyOrThrow().message.orEmpty()
    suspend fun control(id: Int, stop: Boolean): String = api.control(id, if (stop) "stop" else "start").bodyOrThrow().message.orEmpty()

    /** Для демонстрації: підміна ролі в payload без зміни підпису — сервер має відхилити такий токен. */
    fun tamperToken() {
        val t = store.token ?: return
        val parts = t.split('.')
        if (parts.size != 3) return
        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
            .replace(Regex("\"role\":\"[A-Z]+\""), "\"role\":\"ADMIN\"")
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        store.token = parts[0] + "." + forged + "." + parts[2]
    }
}
