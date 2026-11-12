package ua.kpi.windfarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ua.kpi.windfarm.composables.DisplayModeSelector
import ua.kpi.windfarm.s1.WindHomework1
import ua.kpi.windfarm.s1.WindMonitorLevel1
import ua.kpi.windfarm.s1.WindMonitorLevel2
import ua.kpi.windfarm.s1.WindMonitorLevel3
import ua.kpi.windfarm.s2.AdvancedEnergyApp
import ua.kpi.windfarm.s2.BasicEnergyApp
import ua.kpi.windfarm.s2.MediumEnergyApp
import ua.kpi.windfarm.s2.WindHomework2
import ua.kpi.windfarm.s3.AdvancedWindUi
import ua.kpi.windfarm.s3.BasicWindUi
import ua.kpi.windfarm.s3.MediumWindUi
import ua.kpi.windfarm.s3.WindHomework3
import ua.kpi.windfarm.s4.AdvancedLoginScreen
import ua.kpi.windfarm.s4.AuthHomework
import ua.kpi.windfarm.s4.BasicLoginScreen
import ua.kpi.windfarm.s4.MediumLoginScreen
import ua.kpi.windfarm.ui.theme.WindFarmTheme

/**
 * Точка входу застосунку «Вітрова електростанція з моніторингом швидкості вітру» (варіант 2).
 * Через when відображається екран обраного заняття та рівня.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WindFarmTheme {
                // rememberSaveable — вибір не втрачається при повороті екрана
                var session by rememberSaveable { mutableStateOf(Session.S1) }
                var displayFor by rememberSaveable { mutableStateOf(DisplayFor.HOMEWORK) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        DisplayModeSelector(session, { session = it }, displayFor, { displayFor = it })
                        HorizontalDivider()
                        Box(modifier = Modifier.weight(1f)) {
                            Screen(session, displayFor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(session: Session, displayFor: DisplayFor) {
    when (session) {
        Session.S1 -> when (displayFor) {
            DisplayFor.HOMEWORK -> WindHomework1()
            DisplayFor.BASIC_LEVEL -> WindMonitorLevel1()
            DisplayFor.MIDDLE_LEVEL -> WindMonitorLevel2()
            DisplayFor.ADVANCED_LEVEL -> WindMonitorLevel3()
        }
        Session.S2 -> when (displayFor) {
            DisplayFor.HOMEWORK -> WindHomework2()
            DisplayFor.BASIC_LEVEL -> BasicEnergyApp()
            DisplayFor.MIDDLE_LEVEL -> MediumEnergyApp()
            DisplayFor.ADVANCED_LEVEL -> AdvancedEnergyApp()
        }
        Session.S3 -> when (displayFor) {
            DisplayFor.HOMEWORK -> WindHomework3()
            DisplayFor.BASIC_LEVEL -> BasicWindUi()
            DisplayFor.MIDDLE_LEVEL -> MediumWindUi()
            DisplayFor.ADVANCED_LEVEL -> AdvancedWindUi()
        }
        Session.S4 -> when (displayFor) {
            DisplayFor.HOMEWORK -> AuthHomework()
            DisplayFor.BASIC_LEVEL -> BasicLoginScreen()
            DisplayFor.MIDDLE_LEVEL -> MediumLoginScreen()
            DisplayFor.ADVANCED_LEVEL -> AdvancedLoginScreen()
        }
    }
}
