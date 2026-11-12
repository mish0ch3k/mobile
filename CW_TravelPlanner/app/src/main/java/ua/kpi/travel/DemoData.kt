package ua.kpi.travel

import ua.kpi.travel.data.TripRepository
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityCategory
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.ExpenseCategory
import ua.kpi.travel.domain.PackingTemplates
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripType
import java.time.LocalDate

/**
 * Демонстраційні подорожі при першому запуску, щоб користувач одразу бачив можливості застосунку:
 * запланована (Львів, з навмисним накладанням двох активностей), майбутнє відрядження і завершений похід.
 */
object DemoData {
    suspend fun seedIfEmpty(repo: TripRepository) {
        if (!repo.isEmpty()) return
        val start = LocalDate.now().plusDays(3).toEpochDay()
        val id = repo.saveTrip(Trip(title = "Вихідні у Львові", destination = "Львів, Україна", latitude = 49.8397, longitude = 24.0297,
            startDay = start, endDay = start + 2, budget = 12000.0, currency = "UAH", type = TripType.CITY, travelers = 2,
            notes = "Інтерсіті з Києва, готель у центрі"))
        fun act(d: Int, h: Int, m: Int, dur: Int, title: String, place: String, lat: Double?, lon: Double?, cat: ActivityCategory, cost: Double) =
            Activity(tripId = id, day = start + d, startMinute = h * 60 + m, durationMin = dur, title = title, place = place,
                latitude = lat, longitude = lon, category = cat, cost = cost)
        listOf(
            act(0, 7, 0, 330, "Інтерсіті Київ — Львів", "Залізничний вокзал", 49.8396, 23.9945, ActivityCategory.TRANSPORT, 0.0),
            act(0, 13, 0, 60, "Заселення в готель", "вул. Галицька, 9", 49.8410, 24.0320, ActivityCategory.STAY, 0.0),
            act(0, 15, 0, 120, "Площа Ринок і Ратуша", "пл. Ринок", 49.8419, 24.0316, ActivityCategory.SIGHTSEEING, 200.0),
            act(0, 16, 30, 60, "Кава і шоколад", "Львівська майстерня шоколаду", 49.8424, 24.0335, ActivityCategory.FOOD, 250.0),
            act(0, 19, 0, 90, "Вечеря", "Криївка", 49.8414, 24.0329, ActivityCategory.FOOD, 900.0),
            act(1, 10, 0, 150, "Личаківський цвинтар", "вул. Пекарська, 54", 49.8331, 24.0560, ActivityCategory.SIGHTSEEING, 300.0),
            act(1, 13, 30, 90, "Обід", "Львівська копальня кави", 49.8418, 24.0311, ActivityCategory.FOOD, 700.0),
            act(1, 16, 0, 120, "Високий замок", "Замкова гора", 49.8484, 24.0397, ActivityCategory.ACTIVE, 0.0),
            act(2, 11, 0, 120, "Опера (екскурсія)", "пр. Свободи, 28", 49.8440, 24.0262, ActivityCategory.SIGHTSEEING, 500.0),
            act(2, 17, 30, 330, "Інтерсіті Львів — Київ", "Залізничний вокзал", 49.8396, 23.9945, ActivityCategory.TRANSPORT, 0.0)
        ).forEach { repo.saveActivity(it) }
        listOf(
            Expense(tripId = id, title = "Квитки Інтерсіті", amount = 2400.0, currency = "UAH", category = ExpenseCategory.TRANSPORT, day = start - 10),
            Expense(tripId = id, title = "Готель, 2 ночі", amount = 4200.0, currency = "UAH", category = ExpenseCategory.STAY, day = start - 9),
            Expense(tripId = id, title = "Страховка", amount = 12.0, currency = "EUR", category = ExpenseCategory.INSURANCE, day = start - 5)
        ).forEach { repo.addExpense(it) }
        repo.addPacking(id, PackingTemplates.suggest(TripType.CITY, 3))
        seedBusiness(repo)
        seedMountains(repo)
    }

    private suspend fun seedBusiness(repo: TripRepository) {
        val start = LocalDate.now().plusDays(25).toEpochDay()
        val id = repo.saveTrip(Trip(title = "Конференція у Варшаві", destination = "Варшава, Польща", latitude = 52.2298, longitude = 21.0118,
            startDay = start, endDay = start + 1, budget = 600.0, currency = "EUR", type = TripType.BUSINESS, travelers = 1,
            notes = "Доповідь у другий день о 10:00"))
        repo.saveActivity(Activity(tripId = id, day = start, startMinute = 9 * 60, durationMin = 480, title = "Конференція, день 1",
            place = "PGE Narodowy", latitude = 52.2395, longitude = 21.0458, category = ActivityCategory.MEETING, cost = 0.0))
        repo.saveActivity(Activity(tripId = id, day = start + 1, startMinute = 10 * 60, durationMin = 45, title = "Моя доповідь",
            place = "PGE Narodowy", latitude = 52.2395, longitude = 21.0458, category = ActivityCategory.MEETING, cost = 0.0))
        repo.addExpense(Expense(tripId = id, title = "Авіаквитки", amount = 180.0, currency = "EUR", category = ExpenseCategory.TRANSPORT, day = start - 20))
        repo.addPacking(id, PackingTemplates.suggest(TripType.BUSINESS, 2, abroad = true))
    }

    private suspend fun seedMountains(repo: TripRepository) {
        val start = LocalDate.now().minusDays(20).toEpochDay()
        val id = repo.saveTrip(Trip(title = "Похід на Говерлу", destination = "Ворохта, Україна", latitude = 48.2833, longitude = 24.5667,
            startDay = start, endDay = start + 3, budget = 8000.0, currency = "UAH", type = TripType.MOUNTAINS, travelers = 3))
        repo.saveActivity(Activity(tripId = id, day = start + 1, startMinute = 6 * 60, durationMin = 600, title = "Сходження на Говерлу",
            place = "Говерла", latitude = 48.1600, longitude = 24.5000, category = ActivityCategory.ACTIVE, cost = 0.0, done = true))
        repo.addExpense(Expense(tripId = id, title = "Потяг до Ворохти", amount = 1950.0, currency = "UAH", category = ExpenseCategory.TRANSPORT, day = start))
        repo.addExpense(Expense(tripId = id, title = "Садиба, 3 ночі", amount = 5400.0, currency = "UAH", category = ExpenseCategory.STAY, day = start))
        repo.addExpense(Expense(tripId = id, title = "Продукти", amount = 1300.0, currency = "UAH", category = ExpenseCategory.FOOD, day = start + 1))
    }
}
