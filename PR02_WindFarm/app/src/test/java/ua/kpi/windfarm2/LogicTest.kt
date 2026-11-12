package ua.kpi.windfarm2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.kpi.windfarm2.data.AdvancedWindRepository
import ua.kpi.windfarm2.data.MeasurementValidator
import ua.kpi.windfarm2.data.SortOrder
import ua.kpi.windfarm2.data.WindMeasurement
import ua.kpi.windfarm2.domain.FormValidator
import ua.kpi.windfarm2.domain.TurbineStatus
import ua.kpi.windfarm2.s7.viewmodel.AdvancedWindViewModel
import ua.kpi.windfarm2.s7.viewmodel.MediumWindViewModel

class FormValidatorTest {
    @Test fun stationAndCapacity() {
        assertNull(FormValidator.stationName("Ботієвська ВЕС"))
        assertNotNull(FormValidator.stationName("ВЕ"))
        assertNull(FormValidator.capacity("200"))
        assertNotNull(FormValidator.capacity("abc"))
        assertNotNull(FormValidator.capacity("5000"))
    }

    @Test fun emailUniqueAndPasswordStrength() {
        assertNotNull(FormValidator.uniqueEmail("admin@windfarm.ua"))
        assertNull(FormValidator.uniqueEmail("new.user@windfarm.ua"))
        assertEquals(4, FormValidator.passwordStrength("Wind#2026"))
        assertNotNull(FormValidator.strongPassword("password"))
        assertNull(FormValidator.strongPassword("Wind2026x"))
    }
}

class ViewModelLogicTest {
    @Test fun speedValidation() {
        assertNull(MediumWindViewModel.validateSpeed("8,5"))
        assertNotNull(MediumWindViewModel.validateSpeed("-1"))
        assertNotNull(MediumWindViewModel.validateSpeed("x"))
    }

    @Test fun farmCalculation() {
        val r = MediumWindViewModel.calculate(15.0, 10)
        assertEquals(2000.0, r.turbineKw, 1e-9)
        assertEquals(20.0, r.farmMw, 1e-9)
        assertEquals(TurbineStatus.RATED, r.status)
    }

    @Test fun snapshotSummary() {
        val s = AdvancedWindViewModel.summarize(listOf(1.0, 15.0, 26.0))
        assertEquals(2, s.stopped)        // штиль і буря
        assertEquals(2.0, s.totalMw, 1e-9)
    }
}

class RepositoryLogicTest {
    private val data = listOf(
        WindMeasurement(1, "ВТ-02", 9.0, 10.0, timestamp = 3),
        WindMeasurement(2, "ВТ-01", 26.0, 10.0, timestamp = 1),
        WindMeasurement(3, "ВТ-03", 4.0, 10.0, timestamp = 2)
    )

    @Test fun filterAndSort() {
        assertEquals(listOf(2L, 1L, 3L), AdvancedWindRepository.filterAndSort(data, 0.0, SortOrder.SPEED_DESC).map { it.id })
        assertEquals(listOf(1L, 2L), AdvancedWindRepository.filterAndSort(data, 5.0, SortOrder.NEWEST).map { it.id })
        assertEquals("ВТ-01", AdvancedWindRepository.filterAndSort(data, 0.0, SortOrder.TURBINE).first().turbine)
    }

    @Test fun stats() {
        val s = AdvancedWindRepository.stats(data)
        assertEquals(3, s.count); assertEquals(26.0, s.maxSpeed, 1e-9); assertEquals(1, s.storms)
        assertEquals(0, AdvancedWindRepository.stats(emptyList()).count)
    }

    @Test fun validator() {
        assertNull(MeasurementValidator.speed("12.5")); assertNotNull(MeasurementValidator.speed("70"))
        assertTrue(MeasurementValidator.temperature("x") != null)
    }
}
