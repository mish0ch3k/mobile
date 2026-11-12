package ua.kpi.travel.domain

/** Пропозиція речі до списку: назва, категорія, кількість. */
data class PackingSuggestion(val name: String, val category: PackingCategory, val quantity: Int = 1)

/**
 * Генерація списку речей за типом подорожі, тривалістю і прогнозом погоди.
 * Кількість змінного одягу залежить від тривалості (не більше ніж на 7 днів — далі прання).
 */
object PackingTemplates {
    fun suggest(type: TripType, days: Int, minTemp: Double? = null, rainExpected: Boolean = false, abroad: Boolean = false): List<PackingSuggestion> {
        val n = days.coerceIn(1, 7)
        val list = mutableListOf(
            PackingSuggestion("Паспорт / ID-картка", PackingCategory.DOCUMENTS),
            PackingSuggestion("Банківська картка й готівка", PackingCategory.DOCUMENTS),
            PackingSuggestion("Квитки / бронювання", PackingCategory.DOCUMENTS),
            PackingSuggestion("Футболки", PackingCategory.CLOTHES, n),
            PackingSuggestion("Білизна й шкарпетки", PackingCategory.CLOTHES, n),
            PackingSuggestion("Зубна щітка й паста", PackingCategory.HYGIENE),
            PackingSuggestion("Телефон і зарядка", PackingCategory.ELECTRONICS),
            PackingSuggestion("Павербанк", PackingCategory.ELECTRONICS),
            PackingSuggestion("Особисті ліки", PackingCategory.HEALTH)
        )
        if (abroad) {
            list += PackingSuggestion("Закордонний паспорт", PackingCategory.DOCUMENTS)
            list += PackingSuggestion("Медична страховка", PackingCategory.DOCUMENTS)
            list += PackingSuggestion("Перехідник для розетки", PackingCategory.ELECTRONICS)
        }
        when (type) {
            TripType.BEACH -> list += listOf(
                PackingSuggestion("Купальник / плавки", PackingCategory.CLOTHES, 2),
                PackingSuggestion("Сонцезахисний крем SPF 50", PackingCategory.HEALTH),
                PackingSuggestion("Сонцезахисні окуляри", PackingCategory.GEAR),
                PackingSuggestion("Пляжний рушник", PackingCategory.GEAR))
            TripType.MOUNTAINS -> list += listOf(
                PackingSuggestion("Трекінгове взуття", PackingCategory.CLOTHES),
                PackingSuggestion("Флісова кофта", PackingCategory.CLOTHES),
                PackingSuggestion("Рюкзак 30–40 л", PackingCategory.GEAR),
                PackingSuggestion("Ліхтарик", PackingCategory.GEAR),
                PackingSuggestion("Аптечка першої допомоги", PackingCategory.HEALTH))
            TripType.BUSINESS -> list += listOf(
                PackingSuggestion("Діловий костюм", PackingCategory.CLOTHES),
                PackingSuggestion("Ноутбук і зарядка", PackingCategory.ELECTRONICS),
                PackingSuggestion("Візитки", PackingCategory.DOCUMENTS))
            TripType.ROAD -> list += listOf(
                PackingSuggestion("Посвідчення водія, техпаспорт", PackingCategory.DOCUMENTS),
                PackingSuggestion("Автомобільна зарядка", PackingCategory.ELECTRONICS),
                PackingSuggestion("Вода й перекус у дорогу", PackingCategory.OTHER))
            TripType.CITY -> list += listOf(
                PackingSuggestion("Зручне взуття для прогулянок", PackingCategory.CLOTHES),
                PackingSuggestion("Невеликий рюкзак", PackingCategory.GEAR))
        }
        if (minTemp != null && minTemp < 10) {
            list += PackingSuggestion("Тепла куртка", PackingCategory.CLOTHES)
            list += PackingSuggestion("Шапка й рукавички", PackingCategory.CLOTHES)
        }
        if (rainExpected) list += PackingSuggestion("Парасолька / дощовик", PackingCategory.GEAR)
        return list.distinctBy { it.name.lowercase() }
    }

    /** Додає лише ті пропозиції, яких ще немає у списку (порівняння без регістру). */
    fun merge(existing: List<PackingItem>, suggestions: List<PackingSuggestion>): List<PackingSuggestion> {
        val names = existing.map { it.name.trim().lowercase() }.toSet()
        return suggestions.filter { it.name.trim().lowercase() !in names }
    }

    fun progress(items: List<PackingItem>): Double = if (items.isEmpty()) 0.0 else items.count { it.packed }.toDouble() / items.size
}
