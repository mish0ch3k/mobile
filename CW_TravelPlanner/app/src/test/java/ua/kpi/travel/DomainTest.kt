package ua.kpi.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityValidator
import ua.kpi.travel.domain.BudgetCalculator
import ua.kpi.travel.domain.BudgetState
import ua.kpi.travel.domain.CurrencyConverter
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.ExpenseCategory
import ua.kpi.travel.domain.ExpenseValidator
import ua.kpi.travel.domain.ItineraryPlanner
import ua.kpi.travel.domain.PackingCategory
import ua.kpi.travel.domain.PackingItem
import ua.kpi.travel.domain.PackingTemplates
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripStatus
import ua.kpi.travel.domain.TripTimeline
import ua.kpi.travel.domain.TripType
import ua.kpi.travel.domain.TripValidator
import java.time.LocalDate

/** Модульні тести бізнес-логіки. Номери TC-xx відповідають тест-кейсам пояснювальної записки. */
class ValidationTest {
    private val d = LocalDate.of(2026, 12, 1).toEpochDay()

    @Test fun tc01_validTripHasNoErrors() =
        assertTrue(TripValidator.validate("Львів", "Львів, Україна", d, d + 2, "12000", "2").isEmpty())

    @Test fun tc02_emptyTitleAndDestination() {
        val e = TripValidator.validate("  ", "", d, d, "", "1")
        assertTrue("title" in e && "destination" in e)
    }

    @Test fun tc03_endBeforeStart() = assertEquals("Завершення не може бути раніше початку",
        TripValidator.validate("A", "B", d + 3, d, "", "1")["end"])

    @Test fun tc04_tripLongerThan90Days() = assertTrue("end" in TripValidator.validate("A", "B", d, d + 90, "", "1"))

    @Test fun tc05_exactly90DaysAllowed() = assertTrue(TripValidator.validate("A", "B", d, d + 89, "", "1").isEmpty())

    @Test fun tc06_budgetNegativeOrText() {
        assertTrue("budget" in TripValidator.validate("A", "B", d, d, "-5", "1"))
        assertTrue("budget" in TripValidator.validate("A", "B", d, d, "abc", "1"))
        assertTrue(TripValidator.validate("A", "B", d, d, "1500,50", "1").isEmpty())   // кома як десятковий роздільник
    }

    @Test fun tc07_travelersBounds() {
        assertTrue("travelers" in TripValidator.validate("A", "B", d, d, "", "0"))
        assertTrue("travelers" in TripValidator.validate("A", "B", d, d, "", "21"))
        assertTrue(TripValidator.validate("A", "B", d, d, "", "20").isEmpty())
    }

    @Test fun tc08_parseTime() {
        assertEquals(9 * 60 + 5, ActivityValidator.parseTime("9:05"))
        assertEquals(23 * 60 + 59, ActivityValidator.parseTime("23.59"))
        assertNull(ActivityValidator.parseTime("24:00"))
        assertNull(ActivityValidator.parseTime("12:60"))
        assertNull(ActivityValidator.parseTime("noon"))
        assertEquals("07:30", ActivityValidator.formatTime(450))
    }

    @Test fun tc09_activityOutsideTripOrPastMidnight() {
        val t = trip()
        assertTrue("day" in ActivityValidator.validate(t, "X", t.endDay + 1, "10:00", "60", ""))
        assertTrue("duration" in ActivityValidator.validate(t, "X", t.startDay, "23:30", "60", ""))
        assertTrue(ActivityValidator.validate(t, "X", t.startDay, "23:00", "60", "").isEmpty())
    }

    @Test fun tc10_expenseValidation() {
        assertTrue("amount" in ExpenseValidator.validate("Таксі", "0", "UAH"))
        assertTrue("currency" in ExpenseValidator.validate("Таксі", "100", "uah"))
        assertTrue(ExpenseValidator.validate("Таксі", "100", "EUR").isEmpty())
    }
}

class ItineraryTest {
    private fun a(id: Long, day: Long, start: Int, dur: Int) = Activity(id, 1, day, start, dur, "A$id")

    @Test fun tc11_conflictsDetected() {
        val list = listOf(a(1, 0, 600, 120), a(2, 0, 660, 60), a(3, 0, 720, 30), a(4, 1, 600, 120))
        val c = ItineraryPlanner.conflicts(list)
        assertEquals(1, c.size)                     // 1 і 2 перетинаються; 3 стартує рівно в кінці 1 — не конфлікт
        assertEquals(60, c[0].overlapMinutes)
    }

    @Test fun tc12_noConflictsAcrossDays() = assertTrue(ItineraryPlanner.conflicts(listOf(a(1, 0, 600, 120), a(2, 1, 600, 120))).isEmpty())

    @Test fun tc13_freeSlots() {
        val slots = ItineraryPlanner.freeSlots(listOf(a(1, 0, 600, 120), a(2, 0, 900, 60)))
        assertEquals(listOf(480 to 600, 720 to 900, 960 to 1320), slots.map { it.startMinute to it.endMinute })
    }

    @Test fun tc14_freeSlotsIgnoreShortGaps() =
        assertEquals(2, ItineraryPlanner.freeSlots(listOf(a(1, 0, 600, 60), a(2, 0, 680, 60))).size)   // 20-хв проміжок відкинуто

    @Test fun tc15_nextActivity() {
        val list = listOf(a(1, 0, 600, 60).copy(done = true), a(2, 0, 700, 60), a(3, 1, 500, 60))
        assertEquals(2L, ItineraryPlanner.next(list, 0, 650)?.id)
        assertEquals(3L, ItineraryPlanner.next(list, 0, 701)?.id)
    }

    @Test fun tc16_statusAndSorting() {
        val today = LocalDate.of(2026, 12, 10)
        val past = trip(start = today.minusDays(10)); val now = trip(start = today.minusDays(1)); val future = trip(start = today.plusDays(5))
        assertEquals(TripStatus.FINISHED, TripTimeline.status(past, today))
        assertEquals(TripStatus.ONGOING, TripTimeline.status(now, today))
        assertEquals(TripStatus.PLANNED, TripTimeline.status(future, today))
        assertEquals(listOf(now, future, past), TripTimeline.sortForList(listOf(past, future, now), today))
        assertEquals(3, TripTimeline.days(now).size)
    }
}

class BudgetTest {
    private val fx = CurrencyConverter("UAH", mapOf("EUR" to 0.02, "USD" to 0.025))

    @Test fun tc17_converter() {
        assertEquals(50.0, fx.convert(1.0, "EUR", "UAH")!!, 1e-9)
        assertEquals(1.25, fx.convert(1.0, "EUR", "USD")!!, 1e-9)    // 1 EUR = 50 UAH = 1,25 USD
        assertNull(fx.convert(1.0, "JPY", "UAH"))
        assertEquals(10.0, fx.convert(10.0, "XXX", "XXX")!!, 1e-9)
    }

    @Test fun tc18_summaryStates() {
        val t = trip(budget = 1000.0)
        val e = { amount: Double, cur: String -> Expense(0, 1, "x", amount, cur, ExpenseCategory.FOOD, t.startDay) }
        assertEquals(BudgetState.OK, BudgetCalculator.summarize(t, listOf(e(500.0, "UAH")), emptyList(), fx).state)
        assertEquals(BudgetState.WARNING, BudgetCalculator.summarize(t, listOf(e(800.0, "UAH")), emptyList(), fx).state)
        assertEquals(BudgetState.OVER, BudgetCalculator.summarize(t, listOf(e(10.0, "EUR"), e(600.0, "UAH")), emptyList(), fx).state)
        assertEquals(BudgetState.NO_BUDGET, BudgetCalculator.summarize(trip(budget = 0.0), emptyList(), emptyList(), fx).state)
    }

    @Test fun tc19_unknownCurrencyNotCounted() {
        val t = trip(budget = 1000.0)
        val s = BudgetCalculator.summarize(t, listOf(Expense(0, 1, "x", 100.0, "JPY", ExpenseCategory.FOOD, t.startDay)), emptyList(), fx)
        assertEquals(0.0, s.spent, 1e-9); assertEquals(1, s.unconverted)
    }

    @Test fun tc20_plannedAndDaily() {
        val t = trip(budget = 900.0)
        val acts = listOf(Activity(1, 1, t.startDay, 600, 60, "a", cost = 100.0), Activity(2, 1, t.startDay, 700, 60, "b", cost = 50.0, done = true))
        val s = BudgetCalculator.summarize(t, emptyList(), acts, fx)
        assertEquals(100.0, s.planned, 1e-9)
        assertEquals(300.0, BudgetCalculator.dailyAllowance(s, t, t.startDay - 5)!!, 1e-9)      // 900 / 3 дні
        assertEquals(450.0, BudgetCalculator.dailyAllowance(s, t, t.startDay + 1)!!, 1e-9)      // 900 / 2 дні, що лишилися
        assertNull(BudgetCalculator.dailyAllowance(s, t, t.endDay + 1))
        assertEquals(33.33, BudgetCalculator.perPerson(100.0, 3), 1e-9)
    }

    @Test fun tc21_byCategory() {
        val t = trip(budget = 0.0)
        val s = BudgetCalculator.summarize(t, listOf(
            Expense(0, 1, "a", 100.0, "UAH", ExpenseCategory.FOOD, 0), Expense(0, 1, "b", 2.0, "EUR", ExpenseCategory.FOOD, 0),
            Expense(0, 1, "c", 300.0, "UAH", ExpenseCategory.STAY, 0)), emptyList(), fx)
        assertEquals(200.0, s.byCategory[ExpenseCategory.FOOD]!!, 1e-9)
        assertEquals(300.0, s.byCategory[ExpenseCategory.STAY]!!, 1e-9)
    }
}

class PackingTest {
    @Test fun tc22_templateDependsOnTypeAndWeather() {
        val beach = PackingTemplates.suggest(TripType.BEACH, 5).map { it.name }
        assertTrue(beach.any { it.contains("Сонцезахисний") })
        val cold = PackingTemplates.suggest(TripType.CITY, 3, minTemp = 2.0, rainExpected = true).map { it.name }
        assertTrue("Тепла куртка" in cold && "Парасолька / дощовик" in cold)
        assertFalse(PackingTemplates.suggest(TripType.CITY, 3, minTemp = 20.0).any { it.name == "Тепла куртка" })
    }

    @Test fun tc23_quantityCappedAt7() =
        assertEquals(7, PackingTemplates.suggest(TripType.CITY, 14).first { it.name == "Футболки" }.quantity)

    @Test fun tc24_mergeSkipsExisting() {
        val existing = listOf(PackingItem(1, 1, "футболки", PackingCategory.CLOTHES))
        val merged = PackingTemplates.merge(existing, PackingTemplates.suggest(TripType.CITY, 2))
        assertFalse(merged.any { it.name == "Футболки" })
        assertEquals(0.5, PackingTemplates.progress(listOf(existing[0].copy(packed = true), existing[0].copy(id = 2))), 1e-9)
    }
}

internal fun trip(start: LocalDate = LocalDate.of(2026, 12, 1), budget: Double = 0.0) =
    Trip(id = start.toEpochDay(), title = "T", destination = "D", startDay = start.toEpochDay(), endDay = start.toEpochDay() + 2, budget = budget)
