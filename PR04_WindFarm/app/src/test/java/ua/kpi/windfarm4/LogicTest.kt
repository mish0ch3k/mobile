package ua.kpi.windfarm4

import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm4.domain.EnergyCatalog
import ua.kpi.windfarm4.domain.Geo
import ua.kpi.windfarm4.domain.LiveCatalog
import ua.kpi.windfarm4.domain.ObjectFilter
import ua.kpi.windfarm4.domain.ObjectQuery
import ua.kpi.windfarm4.domain.ObjectStatus
import ua.kpi.windfarm4.domain.ObjectType
import ua.kpi.windfarm4.domain.SortKey
import ua.kpi.windfarm4.domain.UserLocation
import ua.kpi.windfarm4.flows.SensorException
import ua.kpi.windfarm4.flows.SensorReading
import ua.kpi.windfarm4.flows.StationSnapshot
import ua.kpi.windfarm4.flows.WindFlows
import ua.kpi.windfarm4.flows.WindFlows.retrySensor
import ua.kpi.windfarm4.flows.WindFlows.toPower
import ua.kpi.windfarm4.flows.WindFlows.validOnly
import kotlin.random.Random

/** Заняття 13: потоки даних. */
class FlowTest {
    @Test fun coldFlowIsRepeatable() = runBlocking {
        val f = WindFlows.anemometer("X", periodMs = 1, seed = 5).take(5).map { it.speed }
        assertEquals(f.toList(), f.toList()) // кожен збирач отримує власну, однакову послідовність
    }

    @Test fun filterRemovesGlitchesAndMapComputesPower() = runBlocking {
        val samples = WindFlows.anemometer("G", periodMs = 1, seed = 3, glitchProbability = 0.5).take(40).validOnly().toPower().toList()
        assertTrue(samples.isNotEmpty() && samples.size < 40)
        assertTrue(samples.all { it.reading.speed in 0.0..60.0 && it.powerKw in 0.0..2000.0 })
    }

    @Test fun errorIsCaught() = runBlocking {
        var error: String? = null
        val n = WindFlows.anemometer("E", periodMs = 1, failEvery = 4).catch { error = it.message }.toList().size
        assertEquals(3, n)
        assertTrue(error!!.contains("E"))
    }

    @Test fun retryRestartsCollection() = runBlocking {
        var attempts = 0
        var failures = 0
        val src = flow {
            attempts++
            emit(1)
            if (attempts < 3) throw SensorException("збій")
            emit(2)
        }
        val res = src.retrySensor(maxRetries = 3, baseDelayMs = 1) { _, _ -> failures++ }.toList()
        assertEquals(listOf(1, 1, 1, 2), res)
        assertEquals(2, failures)
    }

    @Test fun zipPairsByIndex() = runBlocking {
        val a = WindFlows.anemometer("A", periodMs = 1, seed = 1).take(3)
        val b = WindFlows.anemometer("B", periodMs = 5, seed = 2).take(10)
        assertEquals(listOf(1 to 1, 2 to 2, 3 to 3), a.zip(b) { x, y -> x.seq to y.seq }.toList())
    }

    @Test fun snapshotDetectsStaleSensors() {
        val s = StationSnapshot().withReading(SensorReading("T1", 1, 1000, 10.0, 0.0)).withReading(SensorReading("T2", 1, 5000, 12.0, 0.0))
        assertEquals(listOf("T1", "T3"), s.stale(now = 6000, staleMs = 3000, all = listOf("T1", "T2", "T3")))
        assertEquals(s.totalPowerKw(exclude = listOf("T1")), s.totalPowerKw() - s.totalPowerKw(exclude = listOf("T2")), 1e-9)
    }
}

/** Заняття 14–16: фільтрація, сортування, геообчислення. */
class ObjectFilterTest {
    private val all = EnergyCatalog.initial(now = 1_000_000_000L)

    @Test fun searchIsCaseInsensitiveAndMultiWord() {
        // слова шукаються також в описі: підстанція, що підключає ВЕС «Тилігул», теж знайдеться
        assertEquals(setOf("ВЕС «Тилігул»", "ПС 110 кВ «Очаківська»"), ObjectFilter.apply(all, ObjectQuery(text = "ВЕС тилігул")).map { it.name }.toSet())
        assertEquals(listOf("ВЕС «Тилігул»"), ObjectFilter.apply(all, ObjectQuery(text = "тилігул", types = setOf(ObjectType.WIND_FARM))).map { it.name })
        assertTrue(ObjectFilter.apply(all, ObjectQuery(text = "неіснуюче")).isEmpty())
    }

    @Test fun combinedFilters() {
        val r = ObjectFilter.apply(all, ObjectQuery(types = setOf(ObjectType.WIND_FARM), minCapacity = 50.0))
        assertTrue(r.isNotEmpty() && r.all { it.type == ObjectType.WIND_FARM && it.capacityMw >= 50.0 })
        val working = ObjectFilter.apply(all, ObjectQuery(statuses = setOf(ObjectStatus.WORKING), regions = setOf("Одеська")))
        assertTrue(working.all { it.status == ObjectStatus.WORKING && it.region == "Одеська" })
    }

    @Test fun sortingWithSecondaryKey() {
        val byCap = ObjectFilter.apply(all, ObjectQuery(sort = SortKey.CAPACITY, descending = true))
        assertEquals(250.0, byCap.first().capacityMw, 1e-9)
        val byType = ObjectFilter.apply(all, ObjectQuery(sort = SortKey.TYPE, thenBy = SortKey.CAPACITY))
        val farms = byType.takeWhile { it.type == ObjectType.WIND_FARM }
        assertEquals(farms.sortedBy { it.capacityMw }, farms)
    }

    @Test fun distanceAndSortByDistance() {
        // Київ — Одеса ≈ 440 км
        assertEquals(440.0, Geo.distanceKm(50.45, 30.52, 46.48, 30.73), 15.0)
        val odesa = UserLocation(46.48, 30.73)
        val nearest = ObjectFilter.apply(all, ObjectQuery(sort = SortKey.DISTANCE), odesa).first()
        assertEquals("Накопичувач «Одеса-BESS»", nearest.name)
    }

    @Test fun liveStepUpdatesOnlyMeasuringObjects() {
        val next = LiveCatalog.step(all, Random(1), now = 2_000_000_000L)
        val changed = next.zip(all).filter { (a, b) -> a != b }.map { it.first }
        assertTrue(changed.isNotEmpty())
        assertTrue(changed.none { it.type == ObjectType.SUBSTATION || it.type == ObjectType.STORAGE })
        assertTrue(changed.all { it.updatedAt == 2_000_000_000L })
    }

    @Test fun selectionSummary() {
        val s = ObjectFilter.summarize(all.filter { it.type == ObjectType.WIND_FARM })
        assertEquals(8, s.count)
        assertEquals(676.0, s.capacityMw, 1e-9)
    }
}
