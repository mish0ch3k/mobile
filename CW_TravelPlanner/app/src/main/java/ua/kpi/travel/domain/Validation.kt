package ua.kpi.travel.domain

/** Результат валідації форми: поле → повідомлення про помилку. Порожня мапа — дані коректні. */
typealias Errors = Map<String, String>

object TripValidator {
    const val MAX_DAYS = 90
    const val MAX_TITLE = 60

    fun validate(title: String, destination: String, startDay: Long?, endDay: Long?, budgetText: String, travelersText: String): Errors {
        val e = linkedMapOf<String, String>()
        val t = title.trim()
        if (t.isEmpty()) e["title"] = "Вкажіть назву подорожі"
        else if (t.length > MAX_TITLE) e["title"] = "Назва до $MAX_TITLE символів"
        if (destination.isBlank()) e["destination"] = "Вкажіть пункт призначення"
        if (startDay == null) e["start"] = "Оберіть дату початку"
        if (endDay == null) e["end"] = "Оберіть дату завершення"
        if (startDay != null && endDay != null) {
            if (endDay < startDay) e["end"] = "Завершення не може бути раніше початку"
            else if (endDay - startDay + 1 > MAX_DAYS) e["end"] = "Подорож довша за $MAX_DAYS днів"
        }
        val budget = budgetText.replace(',', '.').trim()
        if (budget.isNotEmpty()) {
            val b = budget.toDoubleOrNull()
            if (b == null || b.isNaN()) e["budget"] = "Бюджет має бути числом"
            else if (b < 0) e["budget"] = "Бюджет не може бути від'ємним"
            else if (b > 10_000_000) e["budget"] = "Завеликий бюджет"
        }
        val tr = travelersText.trim().toIntOrNull()
        if (tr == null || tr !in 1..20) e["travelers"] = "Кількість мандрівників від 1 до 20"
        return e
    }
}

object ActivityValidator {
    /** Час у форматі ГГ:ХХ → хвилини від півночі або null. */
    fun parseTime(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").matchEntire(text) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun formatTime(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)

    fun validate(trip: Trip, title: String, day: Long?, timeText: String, durationText: String, costText: String): Errors {
        val e = linkedMapOf<String, String>()
        if (title.isBlank()) e["title"] = "Вкажіть назву"
        if (day == null || day < trip.startDay || day > trip.endDay) e["day"] = "День має бути в межах подорожі"
        val start = parseTime(timeText)
        if (start == null) e["time"] = "Час у форматі ГГ:ХХ"
        val dur = durationText.trim().toIntOrNull()
        if (dur == null || dur !in 5..720) e["duration"] = "Тривалість від 5 до 720 хв"
        else if (start != null && start + dur > 24 * 60) e["duration"] = "Активність має завершитися до півночі"
        val cost = costText.replace(',', '.').trim()
        if (cost.isNotEmpty() && (cost.toDoubleOrNull() ?: -1.0) < 0) e["cost"] = "Вартість — невід'ємне число"
        return e
    }
}

object ExpenseValidator {
    fun validate(title: String, amountText: String, currency: String): Errors {
        val e = linkedMapOf<String, String>()
        if (title.isBlank()) e["title"] = "Вкажіть, за що витрата"
        val a = amountText.replace(',', '.').trim().toDoubleOrNull()
        if (a == null || a <= 0) e["amount"] = "Сума має бути більшою за нуль"
        if (!Regex("^[A-Z]{3}$").matches(currency)) e["currency"] = "Код валюти з 3 літер (UAH, EUR…)"
        return e
    }
}
