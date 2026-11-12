package ua.kpi.windfarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm.logic.AuthValidator
import ua.kpi.windfarm.logic.TurbineSpec
import ua.kpi.windfarm.logic.TurbineStatus
import ua.kpi.windfarm.logic.WindPowerCalculator
import ua.kpi.windfarm.s3.nextIndex
import ua.kpi.windfarm.s3.prevIndex

class WindPowerCalculatorTest {
    private val spec = TurbineSpec()

    @Test fun airDensityAt15C() = assertEquals(1.225, WindPowerCalculator.airDensity(15.0), 0.001)

    @Test fun noPowerBelowCutInAndAboveCutOut() {
        assertEquals(0.0, WindPowerCalculator.turbinePowerKw(2.9, spec), 1e-9)
        assertEquals(0.0, WindPowerCalculator.turbinePowerKw(25.0, spec), 1e-9)
    }

    @Test fun ratedPowerIsCapped() = assertEquals(2000.0, WindPowerCalculator.turbinePowerKw(15.0, spec), 1e-9)

    @Test fun powerFollowsCubicLaw() {
        // P(8)/P(4) = 2³ = 8 у зоні часткового навантаження
        val ratio = WindPowerCalculator.turbinePowerKw(8.0, spec) / WindPowerCalculator.turbinePowerKw(4.0, spec)
        assertEquals(8.0, ratio, 1e-6)
    }

    @Test fun statuses() {
        assertEquals(TurbineStatus.IDLE, WindPowerCalculator.status(1.0, spec))
        assertEquals(TurbineStatus.PARTIAL_LOAD, WindPowerCalculator.status(8.0, spec))
        assertEquals(TurbineStatus.RATED, WindPowerCalculator.status(13.0, spec))
        assertEquals(TurbineStatus.STORM_STOP, WindPowerCalculator.status(26.0, spec))
    }

    @Test fun beaufortScale() {
        assertEquals(0, WindPowerCalculator.beaufort(0.1))
        assertEquals(5, WindPowerCalculator.beaufort(9.0))
        assertEquals(12, WindPowerCalculator.beaufort(40.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cpAboveBetzLimitRejected() { TurbineSpec(powerCoefficient = 0.7) }

    @Test fun indexWrapsAround() {
        assertEquals(0, nextIndex(4, 5)); assertEquals(4, prevIndex(0, 5))
    }
}

class AuthValidatorTest {
    @Test fun validData() {
        assertNull(AuthValidator.login("operator"))
        assertNull(AuthValidator.password("wind2026"))
        assertNull(AuthValidator.email("op@wind.ua"))
    }

    @Test fun invalidData() {
        assertNotNull(AuthValidator.login(""))
        assertNotNull(AuthValidator.login("ab"))
        assertNotNull(AuthValidator.password("short1"))
        assertNotNull(AuthValidator.password("onlyletters"))
        assertNotNull(AuthValidator.email("bad@"))
        assertNotNull(AuthValidator.confirm("a", "b"))
    }

    @Test fun authAndRegister() {
        assertTrue(AuthValidator.authenticate("operator", "wind2026"))
        assertFalse(AuthValidator.authenticate("operator", "wrong"))
        assertTrue(AuthValidator.register("newuser", "pass12345"))
        assertFalse(AuthValidator.register("newuser", "pass12345"))
    }
}
