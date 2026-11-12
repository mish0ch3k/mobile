package ua.kpi.windfarm.rgr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm.rgr.domain.AlertRules
import ua.kpi.windfarm.rgr.domain.AlertThresholds
import ua.kpi.windfarm.rgr.domain.AlertType
import ua.kpi.windfarm.rgr.domain.ForecastCalculator
import ua.kpi.windfarm.rgr.domain.ForecastHour
import ua.kpi.windfarm.rgr.domain.TurbineSpec
import ua.kpi.windfarm.rgr.domain.TurbineState
import ua.kpi.windfarm.rgr.domain.WindPhysics
import ua.kpi.windfarm.rgr.domain.WindStatistics
import kotlin.math.ln
import kotlin.math.pow

/** Тести предметної логіки: фізика турбіни, статистика вітру, прогноз виробітку, правила тривог. */
class DomainTest {
    private val spec = TurbineSpec()

    // ---------- фізика ----------
    @Test fun airDensityStandardAndCold() {
        assertEquals(1.225, WindPhysics.airDensity(15.0, 1013.25), 0.001)
        assertEquals(1.341, WindPhysics.airDensity(-10.0, 1013.25), 0.002)   // холодне повітря щільніше на ~9 %
    }

    @Test fun powerCurveRegions() {
        assertEquals(0.0, WindPhysics.powerKw(2.9, spec), 0.0)              // до ввімкнення
        assertEquals(837.9, WindPhysics.powerKw(8.0, spec), 0.5)            // ½·1,225·π·45²·8³·0,42
        assertEquals(2000.0, WindPhysics.powerKw(15.0, spec), 0.0)          // номінал
        assertEquals(0.0, WindPhysics.powerKw(25.0, spec), 0.0)             // зупинка
        assertEquals(0.0, WindPhysics.powerKw(Double.NaN, spec), 0.0)
    }

    @Test fun cubicLawBelowRated() =
        assertEquals(8.0, WindPhysics.powerKw(8.0, spec) / WindPhysics.powerKw(4.0, spec), 1e-9)

    @Test fun turbineStates() {
        assertEquals(TurbineState.IDLE, WindPhysics.state(1.0))
        assertEquals(TurbineState.PARTIAL, WindPhysics.state(8.0))
        assertEquals(TurbineState.RATED, WindPhysics.state(11.5))           // номінал досягнуто раніше 12 м/с
        assertEquals(TurbineState.STORM, WindPhysics.state(26.0))
    }

    @Test fun windShearToHubHeight() {
        assertEquals(7.0 * 10.0.pow(0.14), WindPhysics.windAtHeight(7.0, 10.0, 100.0), 1e-9)
        assertEquals(7.0, WindPhysics.windAtHeight(7.0, 100.0, 100.0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cpAboveBetzLimitRejected() { TurbineSpec(powerCoefficient = 0.7) }

    @Test fun beaufortAndCapacityFactor() {
        assertEquals(0, WindPhysics.beaufort(0.1)); assertEquals(5, WindPhysics.beaufort(9.0)); assertEquals(12, WindPhysics.beaufort(40.0))
        assertEquals(0.25, WindPhysics.capacityFactor(12_000.0, 2000.0, 24.0), 1e-9)
        assertEquals(0.0, WindPhysics.capacityFactor(1.0, 0.0, 24.0), 0.0)
    }

    // ---------- статистика ----------
    @Test fun gammaFunction() {
        assertEquals(24.0, WindStatistics.gamma(5.0), 1e-6)
        assertEquals(Math.sqrt(Math.PI), WindStatistics.gamma(0.5), 1e-6)
    }

    @Test fun weibullRecoversKnownParameters() {
        // квантилі розподілу Вейбулла k = 2, c = 8: v = c·(−ln(1 − p))^(1/k)
        val sample = (1..999).map { 8.0 * (-ln(1 - it / 1000.0)).pow(0.5) }
        val (k, c) = WindStatistics.weibull(sample)!!
        assertEquals(2.0, k, 0.1); assertEquals(8.0, c, 0.2)
        assertNull(WindStatistics.weibull(listOf(5.0, 5.0, 5.0)))          // нульова дисперсія
    }

    @Test fun histogramAndSummary() {
        val h = WindStatistics.histogram(listOf(0.5, 1.9, 2.0, 7.2, 35.0))
        assertEquals(2, h[0]); assertEquals(1, h[1]); assertEquals(1, h[3]); assertEquals(1, h.last())   // > 30 — в останній інтервал
        val s = WindStatistics.summary(listOf(2.0, 4.0, 6.0))
        assertEquals(4.0, s.mean, 1e-9); assertEquals(6.0, s.max, 0.0)
    }

    @Test fun annualEnergyIsPlausible() {
        val mwh = WindStatistics.annualEnergyMwh(2.0, 8.0, spec)
        val cf = mwh / (2.0 * 8760)
        assertTrue("КВВП $cf", cf in 0.25..0.45)                            // типові значення для середнього вітру ~7 м/с
    }

    // ---------- прогноз ----------
    private fun hours(vararg w: Double) = w.mapIndexed { i, v -> ForecastHour(i * 3_600_000L, v, 15.0, 1013.25) }

    @Test fun forecastEnergy() {
        val f = ForecastCalculator.energy(hours(8.0, 15.0, 26.0, 2.0), turbines = 10)
        assertEquals(8.379 + 20.0, f.totalMwh, 0.01)
        assertEquals(1, f.stormHours); assertEquals(1, f.idleHours)
        assertEquals((8.379 + 20) / (20.0 * 4), f.capacityFactor, 0.001)
    }

    @Test fun availabilityReducesOutput() {
        val full = ForecastCalculator.energy(hours(15.0), 10).totalMwh
        assertEquals(full * 0.9, ForecastCalculator.energy(hours(15.0), 10, availability = 0.9).totalMwh, 1e-9)
    }

    @Test fun dailyChunks() =
        assertEquals(listOf(24 * 20.0, 20.0), ForecastCalculator.energy(hours(*DoubleArray(25) { 15.0 }), 10).daily())

    // ---------- тривоги ----------
    private val t = AlertThresholds()

    @Test fun measurementRules() {
        assertEquals(listOf(AlertType.STORM), AlertRules.checkMeasurement(26.0, 10.0, 60.0, 0.0, 0.0, emptyList(), t, 0).map { it.type })
        assertEquals(listOf(AlertType.ICING), AlertRules.checkMeasurement(8.0, -2.0, 92.0, 800.0, 840.0, emptyList(), t, 0).map { it.type })
        assertEquals(listOf(AlertType.LOW_OUTPUT), AlertRules.checkMeasurement(10.0, 10.0, 60.0, 500.0, 1500.0, emptyList(), t, 0).map { it.type })
        assertTrue(AlertRules.checkMeasurement(10.0, 10.0, 60.0, 1400.0, 1500.0, emptyList(), t, 0).isEmpty())
    }

    @Test fun sensorFaults() {
        assertEquals(AlertType.SENSOR, AlertRules.checkMeasurement(-3.0, 10.0, 60.0, 0.0, 0.0, emptyList(), t, 0).single().type)
        val stuck = List(10) { 7.3 }
        assertTrue(AlertRules.checkMeasurement(7.3, 10.0, 60.0, 1000.0, 1000.0, stuck, t, 0).any { it.type == AlertType.SENSOR })
    }

    @Test fun forecastRulesFindStormAndCalmWindow() {
        val h = hours(10.0, 26.0, 2.0, 1.0, 2.5, 1.0, 0.5, 2.0, 2.9, 12.0)
        val ev = AlertRules.checkForecast(h, spec, t)
        assertEquals(3_600_000L, ev.first { it.type == AlertType.STORM }.timeMs)
        val calm = ev.first { it.type == AlertType.CALM }
        assertEquals(7.0, calm.value, 0.0); assertEquals(2 * 3_600_000L, calm.timeMs)
        assertNull(ev.firstOrNull { it.type == AlertType.GUSTS })           // пориви (= вітер у тесті) < 30 м/с
    }
}
