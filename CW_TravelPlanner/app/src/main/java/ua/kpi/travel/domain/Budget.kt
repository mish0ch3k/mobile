package ua.kpi.travel.domain

import kotlin.math.roundToLong

/**
 * Конвертер валют за курсами відносно базової валюти: rates[X] — скільки одиниць X за 1 одиницю бази.
 * Якщо курсу немає — повертає null, щоб UI міг показати попередження, а не вигадане число.
 */
class CurrencyConverter(private val base: String, rates: Map<String, Double>) {
    private val rates: Map<String, Double> = rates + (base to 1.0)

    fun knows(code: String): Boolean = rates[code]?.let { it > 0 } == true

    fun convert(amount: Double, from: String, to: String): Double? {
        if (from == to) return amount
        val f = rates[from] ?: return null
        val t = rates[to] ?: return null
        if (f <= 0 || t <= 0) return null
        return amount / f * t
    }

    companion object {
        /** Резервні курси (до UAH), якщо мережа недоступна; оновлюються з API за першої можливості. */
        val OFFLINE = CurrencyConverter("UAH", mapOf("UAH" to 1.0, "EUR" to 1 / 48.5, "USD" to 1 / 41.6, "PLN" to 1 / 11.3, "GBP" to 1 / 55.6, "CZK" to 1 / 1.93))
    }
}

enum class BudgetState(val title: String) { OK("У межах бюджету"), WARNING("Понад 80 % бюджету"), OVER("Бюджет перевищено"), NO_BUDGET("Бюджет не задано") }

data class BudgetSummary(
    val budget: Double,
    val spent: Double,
    val planned: Double,                       // вартість ще не виконаних активностей
    val byCategory: Map<ExpenseCategory, Double>,
    val unconverted: Int,                      // витрати у валюті без відомого курсу
    val state: BudgetState
) {
    val remaining: Double get() = budget - spent
    val forecast: Double get() = spent + planned
    val usedShare: Double get() = if (budget > 0) spent / budget else 0.0
}

object BudgetCalculator {
    fun summarize(trip: Trip, expenses: List<Expense>, activities: List<Activity>, fx: CurrencyConverter): BudgetSummary {
        var spent = 0.0
        var unconverted = 0
        val byCat = linkedMapOf<ExpenseCategory, Double>()
        expenses.forEach { e ->
            val v = fx.convert(e.amount, e.currency, trip.currency)
            if (v == null) unconverted++ else {
                spent += v
                byCat[e.category] = (byCat[e.category] ?: 0.0) + v
            }
        }
        val planned = activities.filter { !it.done }.sumOf { it.cost }
        val state = when {
            trip.budget <= 0 -> BudgetState.NO_BUDGET
            spent > trip.budget -> BudgetState.OVER
            spent >= 0.8 * trip.budget -> BudgetState.WARNING
            else -> BudgetState.OK
        }
        return BudgetSummary(trip.budget, round2(spent), round2(planned), byCat.mapValues { round2(it.value) }, unconverted, state)
    }

    /** Скільки можна витрачати на день до кінця подорожі (з урахуванням сьогоднішнього дня). */
    fun dailyAllowance(summary: BudgetSummary, trip: Trip, todayEpoch: Long): Double? {
        if (summary.budget <= 0) return null
        val daysLeft = when {
            todayEpoch < trip.startDay -> trip.days
            todayEpoch > trip.endDay -> return null
            else -> (trip.endDay - todayEpoch + 1).toInt()
        }
        return round2(maxOf(0.0, summary.remaining) / daysLeft)
    }

    /** Частка витрат на кожного мандрівника. */
    fun perPerson(total: Double, travelers: Int): Double = round2(total / maxOf(1, travelers))

    fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}
