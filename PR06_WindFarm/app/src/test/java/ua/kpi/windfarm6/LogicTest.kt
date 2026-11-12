package ua.kpi.windfarm6

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm6.charts.AxisScale
import ua.kpi.windfarm6.data.CsvCodec
import ua.kpi.windfarm6.data.Dates
import ua.kpi.windfarm6.data.EnergyRecord
import ua.kpi.windfarm6.data.JsonCodec
import ua.kpi.windfarm6.data.RecordValidator
import ua.kpi.windfarm6.data.SampleData
import ua.kpi.windfarm6.data.StationStatus
import ua.kpi.windfarm6.data.StationType
import ua.kpi.windfarm6.domain.TurbineStatus
import ua.kpi.windfarm6.media.FileSignature
import ua.kpi.windfarm6.media.MediaType
import ua.kpi.windfarm6.s25.CalcResult
import ua.kpi.windfarm6.s25.LoadState
import ua.kpi.windfarm6.s25.Source
import ua.kpi.windfarm6.s25.Station
import ua.kpi.windfarm6.s25.StationCalculator
import ua.kpi.windfarm6.s25.StationLoader

/**
 * Юніт-тести бізнес-логіки ПР6. Тестові дані підібрано так, щоб перевірити
 * звичайний випадок, межі діапазонів і некоректне введення.
 */
class LogicTest {
    private fun rec(id: Long = 1, station: String = "ВЕС, «Північ»", date: String = "2026-11-01", energy: Double = 1234.5, wind: Double = 9.2, note: String = "") =
        EnergyRecord(id, station, StationType.ONSHORE, date, energy, wind, StationStatus.NORMAL, note)

    // ---------- CSV ----------
    @Test fun csvRoundTripKeepsCommasAndQuotes() {
        val src = listOf(rec(note = "огляд \"плановий\", без зауважень"), rec(2, station = "Офшор", energy = 0.0, wind = 0.0))
        val back = CsvCodec.decode(CsvCodec.encode(src))
        assertTrue(back.errors.isEmpty())
        assertEquals(src, back.records)
    }

    @Test fun csvSkipsBadLinesButKeepsGoodOnes() {
        val text = CsvCodec.encode(listOf(rec())) + "2,X,ONSHORE,вчора,abc,7\nкороткий\n3,Y,UNKNOWN_TYPE,2026-11-02,1,1\n"
        val r = CsvCodec.decode(text)
        assertEquals(1, r.records.size)
        assertEquals(3, r.errors.size)
        assertTrue(r.errors[1].startsWith("рядок 4"))
    }

    @Test fun csvUnknownHeaderIsReported() {
        val r = CsvCodec.decode("a,b,c\n1,2,3")
        assertTrue(r.records.isEmpty()); assertEquals(1, r.errors.size)
    }

    @Test fun csvEmptyFile() = assertTrue(CsvCodec.decode("").records.isEmpty())

    // ---------- JSON ----------
    @Test fun jsonRoundTrip() {
        val src = SampleData.generate(3, until = "2026-11-10")
        assertEquals(src, JsonCodec.decode(JsonCodec.encode(src)).records)
    }

    @Test fun jsonToleratesUnknownFieldsAndReportsCorruption() {
        val withExtra = """{"version":2,"records":[{"id":1,"station":"A","type":"offshore","date":"2026-11-01","energy_mwh":5.0,"avg_wind_ms":7.0,"new_field":true}]}"""
        assertEquals(1, JsonCodec.decode(withExtra).records.size)
        val broken = JsonCodec.decode("""{"records":[{"id":1,""")
        assertTrue(broken.records.isEmpty()); assertTrue(broken.errors.first().contains("JSON"))
    }

    // ---------- дати і валідація ----------
    @Test fun datesArithmeticAcrossMonthsAndLeapYears() {
        assertEquals("2026-03-01", Dates.plusDays("2026-02-28", 1))
        assertEquals("2028-02-29", Dates.plusDays("2028-02-28", 1))
        assertEquals("2025-12-31", Dates.plusDays("2026-01-01", -1))
        assertNull(Dates.parse("2026-02-30"))
        assertEquals("05.11", Dates.short("2026-11-05"))
    }

    @Test fun validatorBoundaries() {
        assertTrue(RecordValidator.validate(rec(energy = 0.0, wind = 0.0)).isEmpty())      // нижня межа допустима
        assertTrue(RecordValidator.validate(rec(wind = 60.0)).isEmpty())                   // верхня межа допустима
        assertEquals(1, RecordValidator.validate(rec(wind = 60.1)).size)
        assertEquals(1, RecordValidator.validate(rec(energy = Double.NaN)).size)
        assertEquals(1, RecordValidator.validate(rec(station = " ")).size)
        assertEquals(1, RecordValidator.validate(rec(date = "01.11.2026")).size)
    }

    @Test fun sampleDataIsValidAndDeterministic() {
        val a = SampleData.generate(30, "2026-11-10")
        assertEquals(150, a.size)
        assertTrue(a.all { RecordValidator.validate(it).isEmpty() })
        assertEquals(a, SampleData.generate(30, "2026-11-10"))
    }

    // ---------- файли ----------
    @Test fun fileSignatures() {
        assertEquals(MediaType.PNG, FileSignature.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals(MediaType.DOCX, FileSignature.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) { listOf("[Content_Types].xml", "word/document.xml") })
        assertEquals(MediaType.UNKNOWN, FileSignature.detect(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) { listOf("xl/workbook.xml") }) // XLSX — не DOCX
        assertEquals(1, FileSignature.sampleSize(800, 600, 1600))
        assertEquals(4, FileSignature.sampleSize(8000, 6000, 1600))
    }

    @Test fun axisTicks() = assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), AxisScale.ticks(0.0, 7.0, 4))

    // ---------- калькулятор (домашнє) ----------
    @Test fun calculatorValidInput() {
        val r = StationCalculator.calculate("8,0", "10") as CalcResult.Ok // кома як десятковий роздільник
        assertEquals(8.38, r.stationMw, 0.01)
        assertEquals(r.stationMw * 24, r.dailyMwh, 1e-9)
        assertEquals(TurbineStatus.PARTIAL_LOAD, r.status)
    }

    @Test fun calculatorInvalidInputPerField() {
        val r = StationCalculator.calculate("-1", "abc") as CalcResult.Invalid
        assertEquals("Швидкість не може бути від'ємною", r.windError)
        assertEquals("Ціле число", r.countError)
        assertEquals("Введіть швидкість вітру", (StationCalculator.calculate("", "5") as CalcResult.Invalid).windError)
        assertEquals("Від 1 до 500", StationCalculator.parseCount("501").second)
    }

    @Test fun calculatorStormAndCalm() {
        assertEquals(0.0, (StationCalculator.calculate("26", "10") as CalcResult.Ok).stationMw, 0.0)
        assertEquals(TurbineStatus.IDLE, (StationCalculator.calculate("2", "1") as CalcResult.Ok).status)
    }

    // ---------- асинхронне завантаження (віртуальний час) ----------
    private val good = listOf(Station(1, "A", 10.0, 9.0))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun loaderRetriesWithBackoff() = runTest {
        var calls = 0
        val loader = StationLoader({ calls++; if (calls < 3) throw java.io.IOException("503") else good }, StandardTestDispatcher(testScheduler))
        val r = loader.load { testScheduler.currentTime } as LoadState.Success
        assertEquals(3, r.attempts)
        assertEquals(900, r.ms) // паузи 300 + 600 мс
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun loaderGivesUpAfterRetries() = runTest {
        val r = StationLoader({ throw java.io.IOException("503") }, StandardTestDispatcher(testScheduler)).load { testScheduler.currentTime }
        assertTrue(r is LoadState.Error && r.attempts == 3)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun loaderTimesOut() = runTest {
        val slow: Source = { delay(10_000); good }
        val r = StationLoader(slow, StandardTestDispatcher(testScheduler), timeoutMs = 2_000).load { testScheduler.currentTime }
        assertTrue(r is LoadState.Error && (r as LoadState.Error).message.contains("час"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun loaderRejectsBadDataWithoutRetry() = runTest {
        var calls = 0
        val r = StationLoader({ calls++; listOf(Station(1, "A", 0.0, 1.0)) }, StandardTestDispatcher(testScheduler)).load { testScheduler.currentTime }
        assertTrue(r is LoadState.Error); assertEquals(1, calls)
    }
}
