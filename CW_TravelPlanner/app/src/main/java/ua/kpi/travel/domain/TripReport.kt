package ua.kpi.travel.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Текстовий звіт про подорож для надсилання через «Поділитися». */
object TripReport {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM, EEEE", Locale.forLanguageTag("uk"))

    fun money(v: Double, currency: String): String = String.format(Locale.US, "%,.2f", v).replace(',', ' ') + " " + currency

    fun build(trip: Trip, activities: List<Activity>, summary: BudgetSummary?, packing: List<PackingItem>): String = buildString {
        appendLine("✈ ${trip.title} — ${trip.destination}")
        appendLine("${trip.start} – ${trip.end} (${trip.days} дн.), мандрівників: ${trip.travelers}")
        if (trip.notes.isNotBlank()) appendLine(trip.notes)
        appendLine()
        ItineraryPlanner.byDay(activities).forEach { (day, list) ->
            appendLine(LocalDate.ofEpochDay(day).format(dateFmt).replaceFirstChar { it.uppercase() })
            list.forEach { a ->
                val mark = if (a.done) "✔" else "•"
                append("  $mark ${ActivityValidator.formatTime(a.startMinute)}–${ActivityValidator.formatTime(a.endMinute)} ${a.title}")
                if (a.place.isNotBlank()) append(" (${a.place})")
                if (a.cost > 0) append(" — ${money(a.cost, trip.currency)}")
                appendLine()
            }
        }
        summary?.let {
            appendLine()
            appendLine("Бюджет: ${money(it.budget, trip.currency)}, витрачено ${money(it.spent, trip.currency)}, залишок ${money(it.remaining, trip.currency)}")
        }
        if (packing.isNotEmpty()) appendLine("Речі: зібрано ${packing.count { it.packed }} з ${packing.size}")
    }
}
