package ua.kpi.windfarm3.errors

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTP-відповідь з кодом помилки (4xx / 5xx), отримана від сервера. */
class ApiHttpException(val code: Int, val serverMessage: String?) :
    IOException("HTTP $code${serverMessage?.let { ": $it" } ?: ""}")

/** Відсутнє підключення до мережі (перевірено до виконання запиту). */
class NoConnectionException : IOException("Немає підключення до інтернету")

/** Сервер повернув JSON, який не пройшов перевірку. */
class InvalidDataException(message: String) : IOException(message)

/**
 * Єдина модель помилки застосунку. ViewModel і UI працюють тільки з нею,
 * а не з різнорідними винятками OkHttp / Retrofit / Gson.
 */
sealed class AppError(val title: String, val userMessage: String, val retryable: Boolean) {
    object NoInternet : AppError("Немає мережі", "Перевірте підключення до інтернету та повторіть спробу.", true)
    object Timeout : AppError("Тайм-аут", "Сервер довго не відповідає. Спробуйте ще раз.", true)
    object UnknownHost : AppError("Сервер недоступний", "Не вдалося знайти сервер. Можливо, проблеми з DNS або мережею.", true)
    class Http(val code: Int, message: String, retryable: Boolean) : AppError("HTTP $code", message, retryable)
    class InvalidData(details: String) : AppError("Некоректні дані", "Сервер повернув некоректні дані: $details", false)
    class Unknown(details: String) : AppError("Помилка", "Непередбачена помилка: $details", false)

    override fun toString() = "$title — $userMessage"
}

/** Централізований обробник: виняток → AppError з повідомленням для користувача. */
object ErrorMapper {
    fun map(t: Throwable): AppError = when (t) {
        is NoConnectionException -> AppError.NoInternet
        is SocketTimeoutException -> AppError.Timeout
        is UnknownHostException -> AppError.UnknownHost
        is ApiHttpException -> http(t.code, t.serverMessage)
        is InvalidDataException -> AppError.InvalidData(t.message ?: "")
        is IOException -> AppError.Unknown("мережа: ${t.message}")
        else -> AppError.Unknown(t.message ?: t::class.java.simpleName)
    }

    /** Окреме повідомлення для кожного типового HTTP-коду. */
    fun http(code: Int, serverMessage: String? = null): AppError.Http {
        val details = serverMessage?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        val (msg, retry) = when (code) {
            400 -> "Некоректний запит до сервера$details." to false
            401 -> "Потрібна авторизація: увійдіть у систему повторно." to false
            403 -> "Недостатньо прав для цієї дії$details." to false
            404 -> "Ресурс не знайдено на сервері." to false
            408 -> "Сервер не дочекався запиту. Повторюємо…" to true
            429 -> "Забагато запитів. Повторимо трохи пізніше." to true
            500 -> "Внутрішня помилка сервера." to true
            502, 504 -> "Шлюз сервера не відповідає." to true
            503 -> "Сервіс тимчасово недоступний." to true
            in 400..499 -> "Помилка клієнта (код $code)$details." to false
            in 500..599 -> "Помилка сервера (код $code)." to true
            else -> "Неочікувана відповідь сервера (код $code)." to false
        }
        return AppError.Http(code, msg, retry)
    }
}

/**
 * Повторне виконання запиту при тимчасових помилках з експоненційною затримкою:
 * 0,5 с → 1 с → 2 с… Неповторювані помилки (400, 401, 403, 404, некоректні дані) — одразу.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    private val initialDelayMs: Long = 500,
    private val factor: Double = 2.0,
    private val maxDelayMs: Long = 4000,
    /** Слухач повторних спроб (наприклад, щоб показати номер спроби в UI). */
    private val listener: (suspend (attempt: Int, error: AppError, waitMs: Long) -> Unit)? = null
) {
    init { require(maxAttempts >= 1) }

    fun delayFor(attempt: Int): Long =
        (initialDelayMs * Math.pow(factor, (attempt - 1).toDouble())).toLong().coerceAtMost(maxDelayMs)

    /** onRetry викликається перед кожною повторною спробою — для журналу й UI. */
    suspend fun <T> run(onRetry: suspend (attempt: Int, error: AppError, waitMs: Long) -> Unit = { _, _, _ -> }, block: suspend (attempt: Int) -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block(attempt)
            } catch (c: CancellationException) {
                throw c // скасування корутини не є помилкою мережі
            } catch (t: Throwable) {
                val error = ErrorMapper.map(t)
                if (!error.retryable || attempt >= maxAttempts) throw t
                val wait = delayFor(attempt)
                onRetry(attempt, error, wait)
                listener?.invoke(attempt, error, wait)
                delay(wait)
                attempt++
            }
        }
    }
}
