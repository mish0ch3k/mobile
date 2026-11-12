package ua.kpi.windfarm6

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ua.kpi.windfarm6.s25.FakeStationRepository
import ua.kpi.windfarm6.s25.HomeworkTestApp
import ua.kpi.windfarm6.s25.MediumTestApp
import ua.kpi.windfarm6.s25.Station
import ua.kpi.windfarm6.s25.Tags
import ua.kpi.windfarm6.ui.theme.WindFarmTheme

/**
 * UI-тести (Compose Test): запуск — ./gradlew connectedFullDebugAndroidTest на емуляторі.
 * Елементи шукаються за testTag, а не за текстом, щоб тести не ламалися від зміни підписів.
 */
@RunWith(AndroidJUnit4::class)
class CalculatorUiTest {
    @get:Rule val rule = createComposeRule()

    private fun open() = rule.setContent { WindFarmTheme { HomeworkTestApp() } }

    /** Сценарій: введення 8 м/с і 10 турбін → потужність станції 8,38 МВт. */
    @Test fun calculatesStationPower() {
        open()
        rule.onNodeWithTag(Tags.WIND).performTextInput("8")
        rule.onNodeWithTag(Tags.COUNT).performTextClearance()
        rule.onNodeWithTag(Tags.COUNT).performTextInput("10")
        rule.onNodeWithTag(Tags.CALC).performClick()
        rule.onNodeWithTag(Tags.RESULT).assertIsDisplayed()
        rule.onNodeWithText("8.38 МВт").assertIsDisplayed()
        rule.onNodeWithText("Часткове навантаження").assertIsDisplayed()
    }

    /** Сценарій: некоректне введення → повідомлення під полем, результату немає. */
    @Test fun showsValidationError() {
        open()
        rule.onNodeWithTag(Tags.WIND).performTextInput("abc")
        rule.onNodeWithTag(Tags.CALC).performClick()
        rule.onNodeWithTag(Tags.WIND_ERROR, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Потрібне число").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTagCount(Tags.RESULT))
    }

    /** Швидкі кнопки підставляють значення і одразу рахують (шторм → 0 МВт). */
    @Test fun presetStormStopsTurbines() {
        open()
        rule.onNodeWithText("26").performClick()
        rule.onNodeWithText("Аварійна зупинка (буря)").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}

/** Навігація між екранами з підробленим репозиторієм (mocked data). */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loginListDetails() {
        val fake = FakeStationRepository(listOf(Station(7, "Тестова ВЕС", 50.0, 12.0)))
        rule.setContent { WindFarmTheme { MediumTestApp(fake) } }
        rule.onNodeWithTag(Tags.PASSWORD).performTextInput("будь-який")
        rule.onNodeWithTag(Tags.LOGIN).performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("Тестова ВЕС")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Тестова ВЕС").performClick()
        rule.onNodeWithTag(Tags.DETAILS).assertIsDisplayed()
        rule.onNodeWithText("50 МВт").assertIsDisplayed()
        assertEquals(1, fake.loginCalls)
    }

    @Test fun wrongPasswordShowsError() {
        rule.setContent { WindFarmTheme { MediumTestApp(FakeStationRepository(ok = false)) } }
        rule.onNodeWithTag(Tags.PASSWORD).performTextInput("123")
        rule.onNodeWithTag(Tags.LOGIN).performClick()
        rule.waitUntil(3_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag(Tags.LOGIN_ERROR)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Невірний логін або пароль").assertIsDisplayed()
    }

    @Test fun emptyPasswordIsRejectedWithoutCallingServer() {
        val fake = FakeStationRepository()
        rule.setContent { WindFarmTheme { MediumTestApp(fake) } }
        rule.onNodeWithTag(Tags.LOGIN).performClick()
        rule.onNodeWithText("Заповніть логін і пароль").assertIsDisplayed()
        assertEquals(0, fake.loginCalls)
    }
}
