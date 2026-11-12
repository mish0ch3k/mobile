package ua.kpi.windfarm3.auth

/** Тестовий обліковий запис (mocked user). */
data class MockUser(val login: String, val password: String, val name: String, val role: Role)

/**
 * «Сервер» автентифікації, що працює всередині застосунку (mocked API).
 * Лише він знає секрет підпису, видає токени й перевіряє доступ до захищених ресурсів.
 * Мережевий шар (OkHttp-інтерсептор MockServerInterceptor) викликає ці методи,
 * тому для Retrofit усе виглядає як звичайний HTTP-обмін.
 */
class MockAuthBackend(
    private val secret: ByteArray = "kpi-windfarm-demo-secret-2026".toByteArray(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    val users = listOf(
        MockUser("admin", "Admin2026!", "Гаврилюк М. (адміністратор)", Role.ADMIN),
        MockUser("operator", "Oper2026!", "Черговий оператор", Role.OPERATOR),
        MockUser("guest", "Guest2026!", "Гість", Role.VIEWER)
    )

    sealed interface LoginResult {
        data class Success(val token: String, val claims: TokenClaims) : LoginResult
        data class Failure(val code: Int, val message: String) : LoginResult
    }

    fun login(login: String, password: String, ttlSeconds: Long = 3600): LoginResult {
        if (login.isBlank() || password.isBlank()) return LoginResult.Failure(400, "Логін і пароль обов'язкові")
        val user = users.firstOrNull { it.login == login.trim() && it.password == password }
            ?: return LoginResult.Failure(401, "Невірний логін або пароль")
        val now = clock()
        val claims = TokenClaims(user.login, user.name, user.role, now, now + ttlSeconds)
        return LoginResult.Success(Jwt.sign(claims, secret), claims)
    }

    sealed interface Access {
        data class Granted(val claims: TokenClaims) : Access
        data class Denied(val code: Int, val message: String) : Access
    }

    /** Перевірка заголовка Authorization: Bearer <token> і права доступу. */
    fun authorize(authorizationHeader: String?, required: Permission): Access {
        val token = authorizationHeader?.removePrefix("Bearer ")?.trim()
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ") || token.isNullOrEmpty())
            return Access.Denied(401, "Потрібна автентифікація: токен відсутній")
        return when (val v = Jwt.verify(token, secret, clock())) {
            is Jwt.Verification.Invalid -> Access.Denied(401, "Недійсний токен: ${v.reason}")
            is Jwt.Verification.Expired -> Access.Denied(401, "Термін дії токена минув — увійдіть повторно")
            is Jwt.Verification.Valid ->
                if (required in v.claims.role.permissions) Access.Granted(v.claims)
                else Access.Denied(403, "Роль «${v.claims.role.title}» не має права «${required.title}»")
        }
    }
}
