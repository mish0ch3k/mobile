package ua.kpi.travel.domain

import java.time.LocalDate

/** Статус подорожі відносно поточної дати. */
enum class TripStatus(val title: String) { PLANNED("Запланована"), ONGOING("Триває"), FINISHED("Завершена") }

object TripTimeline {
    fun status(trip: Trip, today: LocalDate): TripStatus {
        val t = today.toEpochDay()
        return when {
            t < trip.startDay -> TripStatus.PLANNED
            t > trip.endDay -> TripStatus.FINISHED
            else -> TripStatus.ONGOING
        }
    }

    /** Днів до початку (0 — сьогодні, від'ємне — уже почалася). */
    fun daysUntil(trip: Trip, today: LocalDate): Long = trip.startDay - today.toEpochDay()

    /** Список днів подорожі. */
    fun days(trip: Trip): List<LocalDate> = (trip.startDay..trip.endDay).map { LocalDate.ofEpochDay(it) }

    private fun rank(s: TripStatus) = when (s) { TripStatus.ONGOING -> 0; TripStatus.PLANNED -> 1; TripStatus.FINISHED -> 2 }

    /** Сортування для головного екрана: спершу поточні, далі майбутні за датою, наприкінці завершені (новіші вище). */
    fun sortForList(trips: List<Trip>, today: LocalDate): List<Trip> = trips.sortedWith(
        compareBy<Trip> { rank(status(it, today)) }
            .thenBy { if (status(it, today) == TripStatus.FINISHED) -it.endDay else it.startDay }
    )
}

/** Конфлікт у розкладі: дві активності одного дня перетинаються в часі. */
data class Conflict(val first: Activity, val second: Activity) {
    val overlapMinutes: Int get() = minOf(first.endMinute, second.endMinute) - maxOf(first.startMinute, second.startMinute)
}

data class TimeSlot(val startMinute: Int, val endMinute: Int) {
    val length: Int get() = endMinute - startMinute
}

object ItineraryPlanner {
    fun byDay(activities: List<Activity>): Map<Long, List<Activity>> =
        activities.groupBy { it.day }.mapValues { (_, v) -> v.sortedWith(compareBy({ it.startMinute }, { it.id })) }.toSortedMap()

    /** Усі пари активностей, що перетинаються (впритул — не конфлікт). */
    fun conflicts(activities: List<Activity>): List<Conflict> {
        val result = mutableListOf<Conflict>()
        byDay(activities).values.forEach { day ->
            for (i in day.indices) for (j in i + 1 until day.size) {
                val a = day[i]; val b = day[j]
                if (b.startMinute >= a.endMinute) break   // відсортовано: далі перетинів з a немає
                result += Conflict(a, b)
            }
        }
        return result
    }

    /** Вільні проміжки дня в межах [from, to) тривалістю не менше [minLength] хв. */
    fun freeSlots(dayActivities: List<Activity>, from: Int = 8 * 60, to: Int = 22 * 60, minLength: Int = 30): List<TimeSlot> {
        val slots = mutableListOf<TimeSlot>()
        var cursor = from
        dayActivities.sortedBy { it.startMinute }.forEach { a ->
            if (a.startMinute > cursor) slots += TimeSlot(cursor, minOf(a.startMinute, to))
            cursor = maxOf(cursor, a.endMinute)
        }
        if (cursor < to) slots += TimeSlot(cursor, to)
        return slots.filter { it.length >= minLength }
    }

    /** Найближча невиконана активність після моменту [day]+[minute]. */
    fun next(activities: List<Activity>, day: Long, minute: Int): Activity? = activities
        .filter { !it.done && (it.day > day || (it.day == day && it.startMinute >= minute)) }
        .minWithOrNull(compareBy({ it.day }, { it.startMinute }))

    /** Частка виконаних активностей, 0..1. */
    fun progress(activities: List<Activity>): Double =
        if (activities.isEmpty()) 0.0 else activities.count { it.done }.toDouble() / activities.size
}
