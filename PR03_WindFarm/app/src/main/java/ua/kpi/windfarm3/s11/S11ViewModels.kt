package ua.kpi.windfarm3.s11

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ua.kpi.windfarm3.auth.AuthRepository
import ua.kpi.windfarm3.auth.MockAuthBackend
import ua.kpi.windfarm3.auth.MockUser
import ua.kpi.windfarm3.auth.Permission
import ua.kpi.windfarm3.auth.PrefsTokenStore
import ua.kpi.windfarm3.auth.SummaryDto
import ua.kpi.windfarm3.auth.TokenClaims
import ua.kpi.windfarm3.auth.TurbineTelemetryDto
import ua.kpi.windfarm3.auth.Jwt
import ua.kpi.windfarm3.errors.ApiHttpException
import ua.kpi.windfarm3.s10.Load
import ua.kpi.windfarm3.s10.loadOf

/** Поля форми входу з базовою перевіркою. */
data class LoginForm(val login: String = "", val password: String = "", val submitted: Boolean = false) {
    val loginError get() = if (login.isBlank()) "Введіть логін" else null
    val passwordError get() = when {
        password.isBlank() -> "Введіть пароль"
        password.length < 6 -> "Щонайменше 6 символів"
        else -> null
    }
    val isValid get() = loginError == null && passwordError == null
    fun fill(u: MockUser) = copy(login = u.login, password = u.password, submitted = false)
}

private fun nowSec() = System.currentTimeMillis() / 1000

// ============================ БАЗОВИЙ ============================
/**
 * Імітаційний (mocked) вхід без мережі: MockAuthBackend генерує тестовий токен,
 * а ViewModel зберігає його у своєму стані (втрачається при закритті екрана).
 */
class BasicAuthViewModel : ViewModel() {
    private val backend = MockAuthBackend()
    val users = backend.users
    val form = MutableStateFlow(LoginForm())
    val token = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)

    fun login() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) { message.value = "Заповніть усі поля" to true; return }
        message.value = when (val r = backend.login(f.login, f.password)) {
            is MockAuthBackend.LoginResult.Success -> { token.value = r.token; "Вхід виконано: ${r.claims.name} (${r.claims.role.title})" to false }
            is MockAuthBackend.LoginResult.Failure -> { token.value = null; r.message to true }
        }
    }

    fun logout() { token.value = null; message.value = "Ви вийшли з системи" to false }
}

// ============================ СЕРЕДНІЙ ============================
/**
 * Токен — у SharedPreferences, до запитів його додає AuthInterceptor.
 * Захищений ресурс доступний лише з дійсним токеном; термін дії перевіряється
 * і на клієнті (таймер), і на «сервері» (відповідь 401).
 */
class MediumAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(PrefsTokenStore(app))
    val users = MockAuthBackend().users
    val form = MutableStateFlow(LoginForm())
    val ttl = MutableStateFlow(30L)
    val session = MutableStateFlow(repo.session())
    val now = MutableStateFlow(nowSec())
    val summary = MutableStateFlow<Load<SummaryDto>>(Load.Idle)
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)
    val loading = MutableStateFlow(false)

    init {
        // Щосекунди оновлюємо «зараз» — UI показує, скільки лишилось до завершення сесії
        viewModelScope.launch {
            while (true) {
                now.value = nowSec()
                val s = session.value
                if (s != null && s.isExpired(now.value) && message.value?.first?.startsWith("Сесія") != true)
                    message.value = "Сесія завершилась: термін дії токена минув. Увійдіть повторно." to true
                delay(1000)
            }
        }
    }

    fun login() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) return
        loading.value = true
        viewModelScope.launch {
            try {
                session.value = repo.login(f.login, f.password, ttl.value)
                summary.value = Load.Idle
                message.value = "Токен отримано і збережено" to false
            } catch (e: Exception) {
                message.value = ua.kpi.windfarm3.errors.ErrorMapper.map(e).userMessage to true
            } finally { loading.value = false }
        }
    }

    fun loadSecure() {
        summary.value = Load.Loading
        viewModelScope.launch { summary.value = loadOf { repo.summary() } }
    }

    fun logout() {
        repo.logout(); session.value = null; summary.value = Load.Idle
        message.value = "Токен видалено зі сховища" to false
    }

    val storedToken: String? get() = repo.store.token
}

// ============================ ПРОСУНУТИЙ ============================
/** Результат перевірки доступу до кожного захищеного ресурсу (для таблиці доступу). */
data class AccessCheck(val title: String, val code: Int, val text: String)

/**
 * Розмежування доступу за роллю з токена: UI показує лише дозволені розділи,
 * а сервер незалежно перевіряє права (403) і дійсність токена (401).
 */
class AdvancedAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(PrefsTokenStore(app))
    val users = MockAuthBackend().users
    val form = MutableStateFlow(LoginForm())
    val session = MutableStateFlow(repo.session()?.takeUnless { it.isExpired(nowSec()) })
    val summary = MutableStateFlow<Load<SummaryDto>>(Load.Idle)
    val telemetry = MutableStateFlow<Load<List<TurbineTelemetryDto>>>(Load.Idle)
    val checks = MutableStateFlow<List<AccessCheck>>(emptyList())
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)

    init { if (session.value != null) loadAll() }

    fun can(p: Permission) = session.value?.role?.permissions?.contains(p) == true

    fun login() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) return
        viewModelScope.launch {
            try {
                session.value = repo.login(f.login, f.password, 3600)
                message.value = null
                checks.value = emptyList()
                loadAll()
            } catch (e: Exception) {
                message.value = ua.kpi.windfarm3.errors.ErrorMapper.map(e).userMessage to true
            }
        }
    }

    fun loadAll() {
        summary.value = Load.Loading
        viewModelScope.launch { summary.value = loadOf { repo.summary() } }
        if (can(Permission.VIEW_TELEMETRY)) {
            telemetry.value = Load.Loading
            viewModelScope.launch { telemetry.value = loadOf { repo.telemetry() } }
        } else telemetry.value = Load.Idle
    }

    fun ackAlarms() { action { repo.ackAlarms().also { loadAll() } } }
    fun control(id: Int, stop: Boolean) { action { repo.control(id, stop).also { loadAll() } } }

    private fun action(block: suspend () -> String) {
        viewModelScope.launch {
            message.value = try { block() to false } catch (e: Exception) { ua.kpi.windfarm3.errors.ErrorMapper.map(e).userMessage to true }
        }
    }

    /** Звертається до всіх ресурсів незалежно від ролі — показує відповіді сервера 200 / 401 / 403. */
    fun checkAccess() { viewModelScope.launch {
        val list = listOf(
            probe("Огляд станції") { repo.summary() },
            probe("Телеметрія") { repo.telemetry() },
            probe("Підтвердження тривог") { repo.ackAlarms() },
            probe("Запуск турбіни ВТ-1") { repo.control(1, false) }
        )
        checks.value = list
    } }

    private suspend fun probe(title: String, call: suspend () -> Any): AccessCheck =
        try { call(); AccessCheck(title, 200, "доступ дозволено") }
        catch (e: ApiHttpException) { AccessCheck(title, e.code, e.serverMessage ?: "відмовлено") }
        catch (e: Exception) { AccessCheck(title, 0, e.message ?: "помилка") }

    /** Демонстрація: токен з підміненою роллю — сервер відхиляє його через невідповідний підпис. */
    fun tamper() {
        repo.tamperToken()
        message.value = "Роль у токені змінено на ADMIN без підпису. Перевірте доступ." to true
        session.value = repo.session()
    }

    /** Демонстрація: запит без токена. */
    fun forgetToken() {
        repo.logout()
        message.value = "Токен видалено, але екран не закрито — запити підуть без заголовка Authorization" to true
    }

    fun logout() {
        repo.logout(); session.value = null; checks.value = emptyList(); summary.value = Load.Idle; telemetry.value = Load.Idle
        message.value = null
    }
}

// ============================ ДОМАШНЄ ============================
/**
 * Повний сценарій: форма входу з перевіркою → POST /api/login → токен у SharedPreferences
 * → захищений ресурс → вихід. Сесія відновлюється після перезапуску, поки токен дійсний.
 */
class HomeworkAuthViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = AuthRepository(PrefsTokenStore(app))
    val users = MockAuthBackend().users
    val form = MutableStateFlow(LoginForm())
    val session = MutableStateFlow(repo.session()?.takeUnless { it.isExpired(nowSec()) })
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val summary = MutableStateFlow<Load<SummaryDto>>(Load.Idle)
    val telemetry = MutableStateFlow<Load<List<TurbineTelemetryDto>>>(Load.Idle)

    init { if (session.value != null) refresh() }

    fun canTelemetry() = session.value?.role?.permissions?.contains(Permission.VIEW_TELEMETRY) == true

    fun login() {
        val f = form.value.copy(submitted = true)
        form.value = f
        if (!f.isValid) return
        loading.value = true
        error.value = null
        viewModelScope.launch {
            try {
                session.value = repo.login(f.login, f.password, 3600)
                form.update { LoginForm() }
                refresh()
            } catch (e: Exception) {
                error.value = ua.kpi.windfarm3.errors.ErrorMapper.map(e).let { if (it is ua.kpi.windfarm3.errors.AppError.Http && it.code == 401) "Невірний логін або пароль" else it.userMessage }
            } finally { loading.value = false }
        }
    }

    fun refresh() {
        summary.value = Load.Loading
        viewModelScope.launch { summary.value = loadOf { repo.summary() } }
        if (canTelemetry()) {
            telemetry.value = Load.Loading
            viewModelScope.launch { telemetry.value = loadOf { repo.telemetry() } }
        }
    }

    fun logout() { repo.logout(); session.value = null; summary.value = Load.Idle; telemetry.value = Load.Idle }

    fun tokenPreview(): String = repo.store.token?.let { it.take(24) + "…" + it.takeLast(10) } ?: "—"
    fun claims(): TokenClaims? = repo.store.token?.let(Jwt::decode)
}
