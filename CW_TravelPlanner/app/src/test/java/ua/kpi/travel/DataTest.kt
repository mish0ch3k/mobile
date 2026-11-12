package ua.kpi.travel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.travel.data.Mappers
import ua.kpi.travel.data.remote.DailyDto
import ua.kpi.travel.data.remote.GeoResultDto
import ua.kpi.travel.data.remote.NetworkError
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.TripReport
import ua.kpi.travel.domain.WeatherCodes
import ua.kpi.travel.notify.ReminderScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class DataTest {
    @Test fun tc25_mapsDailyForecast() {
        val d = DailyDto(listOf("2026-12-01", "2026-12-02"), listOf(3.0, null), listOf(-1.0, 0.0), listOf(80, 10), listOf(61, 0))
        val r = Mappers.daily(d)
        assertEquals(1, r.size)                         // день з null-значенням пропущено
        assertEquals(LocalDate.of(2026, 12, 1), r[0].day)
        assertEquals("Дощ", r[0].description)
        assertTrue(WeatherCodes.isBad(r[0].code))
    }

    @Test(expected = NetworkError.BadData::class)
    fun tc26_rejectsInconsistentArrays() {
        Mappers.daily(DailyDto(listOf("2026-12-01"), listOf(1.0, 2.0), listOf(0.0), null, listOf(0)))
    }

    @Test fun tc27_mapsPlace() {
        val p = Mappers.place(GeoResultDto("Львів", 49.84, 24.03, "Україна", "Львівська область"))
        assertEquals("Львів, Львівська область, Україна", p.label)
        assertEquals("Краків, Польща", Mappers.place(GeoResultDto("Краків", 50.0, 19.9, "Польща", null)).label)
    }

    @Test fun tc28_reminderDelay() {
        val a = Activity(1, 1, LocalDate.of(2026, 12, 1).toEpochDay(), 10 * 60, 60, "Музей")
        val now = LocalDateTime.of(2026, 12, 1, 8, 0)
        assertEquals(60 * 60_000L, ReminderScheduler.delayMillis(a, now, ZoneOffset.UTC))     // о 9:00 — за годину до 10:00
        assertNull(ReminderScheduler.delayMillis(a, LocalDateTime.of(2026, 12, 1, 9, 30), ZoneOffset.UTC))
    }

    @Test fun tc29_reportContainsItinerary() {
        val t = trip(budget = 1000.0)
        val text = TripReport.build(t, listOf(Activity(1, 1, t.startDay, 600, 90, "Опера", "пр. Свободи", cost = 500.0)), null, emptyList())
        assertTrue(text.contains("10:00–11:30 Опера (пр. Свободи)"))
        assertTrue(text.contains("3 дн."))
    }
}
