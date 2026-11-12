package ua.kpi.windfarm2.domain

/** Бізнес-логіка перевірки форм (заняття 6). Повертає текст помилки або null. */
object FormValidator {
    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** «Зареєстровані» e-mail — для перевірки унікальності. */
    private val takenEmails = mutableSetOf("admin@windfarm.ua", "operator@windfarm.ua")

    fun required(value: String, field: String): String? = if (value.isBlank()) "Поле «$field» обов'язкове" else null

    fun stationName(value: String): String? = when {
        value.isBlank() -> "Введіть назву станції"
        value.trim().length < 3 -> "Назва — щонайменше 3 символи"
        else -> null
    }

    /** Встановлена потужність станції, МВт: 0.1…1000. */
    fun capacity(value: String): String? {
        val v = value.replace(',', '.').toDoubleOrNull() ?: return if (value.isBlank()) "Введіть потужність" else "Потрібне число"
        return if (v < 0.1 || v > 1000) "Допустимо 0.1…1000 МВт" else null
    }

    fun email(value: String): String? = when {
        value.isBlank() -> "Введіть e-mail"
        !EMAIL.matches(value.trim()) -> "Формат: name@domain.ua"
        else -> null
    }

    fun uniqueEmail(value: String): String? =
        email(value) ?: if (value.trim().lowercase() in takenEmails) "Цей e-mail уже зареєстровано" else null

    fun register(email: String): Boolean = takenEmails.add(email.trim().lowercase())

    fun passwordShort(value: String): String? = when {
        value.isBlank() -> "Введіть пароль"
        value.length < 8 -> "Щонайменше 8 символів"
        else -> null
    }

    /** Складність пароля 0…4: довжина ≥ 8, великі й малі літери, цифри, спецсимволи. */
    fun passwordStrength(value: String): Int = listOf(
        value.length >= 8,
        value.any { it.isUpperCase() } && value.any { it.isLowerCase() },
        value.any { it.isDigit() },
        value.any { !it.isLetterOrDigit() }
    ).count { it }

    fun strongPassword(value: String): String? = passwordShort(value)
        ?: if (passwordStrength(value) < 3) "Додайте великі літери, цифри або спецсимволи" else null
}
