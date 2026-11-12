package ua.kpi.windfarm3.auth

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Ролі користувачів системи моніторингу ВЕС. */
enum class Role(val title: String, val permissions: Set<Permission>) {
    ADMIN("Адміністратор", Permission.entries.toSet()),
    OPERATOR("Оператор", setOf(Permission.VIEW_SUMMARY, Permission.VIEW_TELEMETRY, Permission.ACK_ALARMS)),
    VIEWER("Спостерігач", setOf(Permission.VIEW_SUMMARY))
}

/** Дії, доступ до яких розмежовується за роллю з токена. */
enum class Permission(val title: String) {
    VIEW_SUMMARY("Огляд станції"),
    VIEW_TELEMETRY("Телеметрія турбін"),
    ACK_ALARMS("Підтвердження тривог"),
    CONTROL_TURBINES("Керування турбінами")
}

/** Корисне навантаження (payload) токена. */
data class TokenClaims(
    val subject: String,   // логін
    val name: String,      // ім'я для відображення
    val role: Role,
    val issuedAt: Long,    // секунди Unix
    val expiresAt: Long
) {
    fun isExpired(nowSec: Long): Boolean = nowSec >= expiresAt
    fun secondsLeft(nowSec: Long): Long = (expiresAt - nowSec).coerceAtLeast(0)
}

/**
 * Мінімальна реалізація JWT (HS256) без сторонніх бібліотек:
 * header.payload.signature, кожна частина — Base64URL без доповнення «=».
 */
object Jwt {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private const val HEADER = """{"alg":"HS256","typ":"JWT"}"""

    fun sign(c: TokenClaims, secret: ByteArray): String {
        val payload = """{"sub":"${esc(c.subject)}","name":"${esc(c.name)}","role":"${c.role.name}","iat":${c.issuedAt},"exp":${c.expiresAt}}"""
        val unsigned = b64(HEADER) + "." + b64(payload)
        return unsigned + "." + encoder.encodeToString(hmac(unsigned, secret))
    }

    sealed interface Verification {
        data class Valid(val claims: TokenClaims) : Verification
        data class Invalid(val reason: String) : Verification
        data class Expired(val claims: TokenClaims) : Verification
    }

    /** Перевірка на «сервері»: формат, підпис (у сталий час), термін дії. */
    fun verify(token: String, secret: ByteArray, nowSec: Long): Verification {
        val parts = token.split('.')
        if (parts.size != 3) return Verification.Invalid("Некоректний формат токена")
        val expected = hmac(parts[0] + "." + parts[1], secret)
        val actual = runCatching { decoder.decode(parts[2]) }.getOrNull()
            ?: return Verification.Invalid("Некоректний підпис")
        if (!java.security.MessageDigest.isEqual(expected, actual)) return Verification.Invalid("Підпис токена не збігається")
        val claims = decode(token) ?: return Verification.Invalid("Некоректне навантаження токена")
        return if (claims.isExpired(nowSec)) Verification.Expired(claims) else Verification.Valid(claims)
    }

    /**
     * Розбір без перевірки підпису — так робить клієнт: він не знає секрету,
     * але читає роль і термін дії, щоб адаптувати UI. Довіряти цим даним може лише сервер.
     */
    fun decode(token: String): TokenClaims? = runCatching {
        val parts = token.split('.')
        require(parts.size == 3)
        val json = String(decoder.decode(parts[1]), Charsets.UTF_8)
        val m = FlatJson.parse(json)
        TokenClaims(
            subject = m.getValue("sub"),
            name = m.getValue("name"),
            role = Role.valueOf(m.getValue("role")),
            issuedAt = m.getValue("iat").toLong(),
            expiresAt = m.getValue("exp").toLong()
        )
    }.getOrNull()

    private fun b64(s: String) = encoder.encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun hmac(data: String, secret: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(data.toByteArray(Charsets.UTF_8))
        }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/** Розбір плаского JSON-об'єкта (рядки й числа) — достатньо для payload токена. */
internal object FlatJson {
    private val pair = Regex("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|-?\\d+(?:\\.\\d+)?)")

    fun parse(json: String): Map<String, String> = pair.findAll(json).associate { m ->
        val str = m.groups[3]?.value
        m.groupValues[1] to (str?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: m.groupValues[2])
    }
}
