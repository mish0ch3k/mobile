package ua.kpi.windfarm5

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.kpi.windfarm5.charts.AxisScale
import ua.kpi.windfarm5.charts.ChartData
import ua.kpi.windfarm5.charts.Viewport
import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.TurbineStatus
import ua.kpi.windfarm5.domain.WindPowerCalculator
import ua.kpi.windfarm5.notify.Alert
import ua.kpi.windfarm5.notify.AlertCategory
import ua.kpi.windfarm5.notify.AlertState
import ua.kpi.windfarm5.notify.AlertStore
import ua.kpi.windfarm5.notify.AlertThrottle
import ua.kpi.windfarm5.notify.ThresholdRule
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorMath
import ua.kpi.windfarm5.sensors.SensorSample
import ua.kpi.windfarm5.sensors.Vec3

class LogicTest {
    private val spec = TurbineSpec()

    // ---------- заняття 17: сенсори ----------
    @Test fun sensorValidation() {
        assertTrue(SensorKind.ACCELEROMETER.isValid(floatArrayOf(0.1f, 9.7f, 0.3f)))
        assertFalse(SensorKind.ACCELEROMETER.isValid(floatArrayOf(Float.NaN, 0f, 0f)))
        assertFalse(SensorKind.PRESSURE.isValid(floatArrayOf(5000f)))
        assertFalse(SensorKind.LIGHT.isValid(floatArrayOf()))
    }

    @Test fun scalarIsMagnitudeForVectors() {
        val s = SensorSample(SensorKind.ACCELEROMETER, floatArrayOf(3f, 4f, 0f), 0)
        assertEquals(5f, s.scalar, 1e-5f)
        assertEquals(1013f, SensorSample(SensorKind.PRESSURE, floatArrayOf(1013f), 0).scalar, 1e-5f)
    }

    @Test fun airDensityStandardAtmosphere() {
        assertEquals(1.225, SensorMath.airDensity(1013.25f, 15f), 0.002)
        assertTrue(SensorMath.airDensity(1013.25f, -10f) > SensorMath.airDensity(1013.25f, 30f)) // холодне повітря щільніше
    }

    @Test fun lowPassConvergesToConstant() {
        val f = SensorMath.LowPass(0.2f)
        repeat(100) { f.filter(Vec3(0f, 0f, 9.8f)) }
        assertEquals(9.8f, f.filter(Vec3(0f, 0f, 9.8f)).z, 1e-3f)
    }

    @Test fun tiltOfFlatAndTiltedDevice() {
        val (p, r) = SensorMath.tilt(Vec3(0f, 0f, 9.81f))
        assertEquals(0f, p, 0.01f); assertEquals(0f, r, 0.01f)
        assertEquals(90f, SensorMath.inclination(Vec3(9.81f, 0f, 0f)), 0.01f)
        assertEquals(45f, SensorMath.tilt(Vec3(0f, 9.81f, 9.81f)).second, 0.01f)
    }

    @Test fun headingNorthAndEast() {
        // телефон лежить екраном догори, магнітне поле Землі спрямоване на північ і вниз
        val g = Vec3(0f, 0f, 9.81f)
        assertEquals(0f, SensorMath.heading(g, Vec3(0f, 20f, -40f))!!, 1f)
        val east = SensorMath.heading(g, Vec3(-20f, 0f, -40f))!!
        assertEquals(90f, east, 1f)
        assertNull(SensorMath.heading(Vec3.ZERO, Vec3(0f, 20f, -40f)))
    }

    @Test fun angleDiffShortestPath() {
        assertEquals(20f, SensorMath.angleDiff(350f, 10f), 1e-4f)
        assertEquals(-20f, SensorMath.angleDiff(10f, 350f), 1e-4f)
        assertEquals(180f, SensorMath.angleDiff(0f, 180f), 1e-4f)
    }

    @Test fun rmsAndIcing() {
        assertEquals(5f, SensorMath.rms(listOf(3f, 4f, 5f, 4f, 3f, 5f, 4f, 3f).map { 5f }), 1e-5f)
        assertEquals(0f, SensorMath.rms(emptyList()), 0f)
        assertTrue(SensorMath.icingRisk(-2f, 92f))
        assertFalse(SensorMath.icingRisk(-2f, 40f))
        assertFalse(SensorMath.icingRisk(10f, 95f))
    }

    // ---------- заняття 18: сповіщення ----------
    @Before fun resetStore() = AlertStore.clear()

    @Test fun thresholdRule() {
        val rule = ThresholdRule(SensorKind.TEMPERATURE, min = -5f, max = 35f)
        assertNotNull(rule.violation(SensorSample(SensorKind.TEMPERATURE, floatArrayOf(40f), 0)))
        assertNotNull(rule.violation(SensorSample(SensorKind.TEMPERATURE, floatArrayOf(-8f), 0)))
        assertNull(rule.violation(SensorSample(SensorKind.TEMPERATURE, floatArrayOf(20f), 0)))
        assertNull(rule.copy(enabled = false).violation(SensorSample(SensorKind.TEMPERATURE, floatArrayOf(40f), 0)))
        assertNull(rule.violation(SensorSample(SensorKind.PRESSURE, floatArrayOf(40f), 0))) // інший сенсор
    }

    @Test fun throttleBlocksRepeats() {
        val t = AlertThrottle(10_000)
        assertTrue(t.allow(SensorKind.ACCELEROMETER, 0))
        assertFalse(t.allow(SensorKind.ACCELEROMETER, 5_000))
        assertTrue(t.allow(SensorKind.GYROSCOPE, 5_000))
        assertTrue(t.allow(SensorKind.ACCELEROMETER, 10_001))
    }

    @Test fun alertLifecycleConfirmSnoozeWake() {
        val a = Alert(AlertStore.newId(), AlertCategory.STORM, "Шторм", "27 м/с", 0)
        AlertStore.add(a)
        AlertStore.snooze(a.id, until = 1_000)
        assertEquals(AlertState.SNOOZED, AlertStore.get(a.id)!!.state)
        assertTrue(AlertStore.wake(500).isEmpty())          // ще рано
        assertEquals(1, AlertStore.wake(1_000).size)        // час настав
        assertEquals(AlertState.ACTIVE, AlertStore.get(a.id)!!.state)
        AlertStore.confirm(a.id)
        assertEquals(AlertState.CONFIRMED, AlertStore.get(a.id)!!.state)
        assertTrue(AlertStore.active().isEmpty())
    }

    // ---------- заняття 19: графіки ----------
    @Test fun niceAxisTicks() {
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), AxisScale.ticks(0.0, 113.0, 4)) // крок 28,25 → «круглий» 50
        assertEquals(2.0, AxisScale.niceStep(7.0, 4), 1e-9)
    }

    @Test fun viewportZoomAndPan() {
        val v = Viewport.all(168).zoom(2f)
        assertEquals(84, v.size)
        assertEquals(42, v.start) // центр лишився на місці
        assertEquals(0, v.pan(-100).start)
        assertEquals(168, v.pan(500).end)
        assertEquals(6, Viewport.all(168).zoom(1000f).size) // мінімальне вікно
        assertEquals(168, v.zoom(0.01f).size)
    }

    @Test fun seriesStatsAndAggregation() {
        val s = ChartData.stats(listOf(1.0, 5.0, 3.0))
        assertEquals(9.0, s.total, 1e-9); assertEquals(3.0, s.average, 1e-9); assertEquals(1, s.peakIndex)
        assertEquals(listOf(1.0, 1.5, 2.5), ChartData.movingAverage(listOf(1.0, 2.0, 3.0), 2))
        val week = ChartData.generation(168)
        assertEquals(7, ChartData.aggregate(week, 24).size)
        assertTrue(week.all { it.energyMwh in 0.0..120.0 }) // 60 турбін × 2 МВт × 1 год
    }

    // ---------- предметна галузь ----------
    @Test fun turbinePowerCurve() {
        assertEquals(0.0, WindPowerCalculator.turbinePowerKw(2.0, spec), 0.0)
        assertEquals(838.0, WindPowerCalculator.turbinePowerKw(8.0, spec), 1.0)
        assertEquals(2000.0, WindPowerCalculator.turbinePowerKw(15.0, spec), 0.0)
        assertEquals(TurbineStatus.RATED, WindPowerCalculator.status(11.8, spec)) // номінал досягнуто раніше 12 м/с
        assertEquals(TurbineStatus.STORM_STOP, WindPowerCalculator.status(26.0, spec))
    }
}
