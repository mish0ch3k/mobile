package ua.kpi.windfarm3

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm3.s10.AdvancedRestApp
import ua.kpi.windfarm3.s10.BasicRestApp
import ua.kpi.windfarm3.s10.HomeworkRestApp
import ua.kpi.windfarm3.s10.MediumRestApp
import ua.kpi.windfarm3.s11.AdvancedAuthApp
import ua.kpi.windfarm3.s11.BasicAuthApp
import ua.kpi.windfarm3.s11.HomeworkAuthApp
import ua.kpi.windfarm3.s11.MediumAuthApp
import ua.kpi.windfarm3.s12.AdvancedErrorsApp
import ua.kpi.windfarm3.s12.BasicErrorsApp
import ua.kpi.windfarm3.s12.HomeworkErrorsApp
import ua.kpi.windfarm3.s12.MediumErrorsApp
import ua.kpi.windfarm3.s9.AdvancedRepoApp
import ua.kpi.windfarm3.s9.BasicRepoApp
import ua.kpi.windfarm3.s9.HomeworkRepoApp
import ua.kpi.windfarm3.s9.MediumRepoApp
import ua.kpi.windfarm3.ui.theme.WindFarmTheme

enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє"), BASIC_LEVEL("Базовий"), MIDDLE_LEVEL("Середній"), ADVANCED_LEVEL("Просунутий")
}

enum class Session(val short: String, val title: String) {
    S9("Заняття 9", "Repository і Data Source"),
    S10("Заняття 10", "REST API: Retrofit + Gson"),
    S11("Заняття 11", "Автентифікація токеном"),
    S12("Заняття 12", "Обробка мережевих помилок")
}

/** ПР3, варіант 2 — «Вітрова електростанція з моніторингом швидкості вітру». */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContent {
            WindFarmTheme {
                var session by rememberSaveable { mutableStateOf(Session.S9) }
                var level by rememberSaveable { mutableStateOf(DisplayFor.BASIC_LEVEL) }
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Selector(session, { session = it }, level, { level = it })
                        HorizontalDivider()
                        Box(Modifier.weight(1f)) { key(session, level) { Screen(session, level) } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Selector(session: Session, onSession: (Session) -> Unit, level: DisplayFor, onLevel: (DisplayFor) -> Unit) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("Вітрова ЕС · ${session.short}: ${session.title}", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Session.entries.forEach { FilterChip(it == session, { onSession(it) }, { Text(it.short) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DisplayFor.entries.forEach { FilterChip(it == level, { onLevel(it) }, { Text(it.title) }) }
        }
    }
}

@Composable
private fun Screen(session: Session, level: DisplayFor) {
    when (session) {
        Session.S9 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkRepoApp(); DisplayFor.BASIC_LEVEL -> BasicRepoApp()
            DisplayFor.MIDDLE_LEVEL -> MediumRepoApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedRepoApp()
        }
        Session.S10 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkRestApp(); DisplayFor.BASIC_LEVEL -> BasicRestApp()
            DisplayFor.MIDDLE_LEVEL -> MediumRestApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedRestApp()
        }
        Session.S11 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkAuthApp(); DisplayFor.BASIC_LEVEL -> BasicAuthApp()
            DisplayFor.MIDDLE_LEVEL -> MediumAuthApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedAuthApp()
        }
        Session.S12 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkErrorsApp(); DisplayFor.BASIC_LEVEL -> BasicErrorsApp()
            DisplayFor.MIDDLE_LEVEL -> MediumErrorsApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedErrorsApp()
        }
    }
}
