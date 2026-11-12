package ua.kpi.windfarm.logic

/**
 * Бізнес-логіка перевірки даних авторизації / реєстрації (заняття 4).
 * Повертає текст помилки або null, якщо поле коректне.
 */
object AuthValidator {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val LOGIN = Regex("^[A-Za-z0-9_.]{3,20}$")

    fun login(value: String): String? = when {
        value.isBlank() -> "Введіть логін"
        !LOGIN.matches(value) -> "Логін: 3–20 латинських літер, цифр, «_» або «.»"
        else -> null
    }

    fun password(value: String): String? = when {
        value.isBlank() -> "Введіть пароль"
        value.length < 8 -> "Пароль має містити щонайменше 8 символів"
        value.none { it.isDigit() } -> "Пароль має містити цифру"
        value.none { it.isLetter() } -> "Пароль має містити літеру"
        else -> null
    }

    fun name(value: String): String? = when {
        value.isBlank() -> "Введіть ім'я"
        value.trim().length < 2 -> "Ім'я занадто коротке"
        else -> null
    }

    fun email(value: String): String? = when {
        value.isBlank() -> "Введіть e-mail"
        !EMAIL.matches(value.trim()) -> "Некоректний e-mail"
        else -> null
    }

    fun confirm(password: String, confirm: String): String? =
        if (password != confirm) "Паролі не збігаються" else null

    /** Демонстраційна «база» облікових записів операторів ВЕС. */
    private val accounts = mutableMapOf("operator" to "wind2026")

    fun authenticate(login: String, password: String): Boolean = accounts[login] == password

    fun register(login: String, password: String): Boolean {
        if (accounts.containsKey(login)) return false
        accounts[login] = password
        return true
    }
}
