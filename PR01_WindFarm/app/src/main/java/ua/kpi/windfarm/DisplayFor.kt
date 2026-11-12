package ua.kpi.windfarm

/** Рівні відображення: домашнє завдання та три рівні складності. */
enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє завдання"),
    BASIC_LEVEL("Базовий рівень"),
    MIDDLE_LEVEL("Середній рівень"),
    ADVANCED_LEVEL("Просунутий рівень")
}

/** Практичні заняття ПР1. */
enum class Session(val short: String, val title: String) {
    S1("Заняття 1", "Hello World, State, анімація"),
    S2("Заняття 2", "Gradle, винесення логіки, розрахунки"),
    S3("Заняття 3", "Compose: layout, текст, зображення, кнопки"),
    S4("Заняття 4", "Авторизація та реєстрація")
}
