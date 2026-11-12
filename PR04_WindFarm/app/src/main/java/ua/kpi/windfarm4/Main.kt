package ua.kpi.windfarm4

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
import ua.kpi.windfarm4.map.initOsm
import ua.kpi.windfarm4.s13.BasicFlowApp
import ua.kpi.windfarm4.s13.MediumFlowApp
import ua.kpi.windfarm4.s13.AdvancedFlowApp
import ua.kpi.windfarm4.s13.HomeworkFlowApp
import ua.kpi.windfarm4.s14.BasicListApp
import ua.kpi.windfarm4.s14.MediumListApp
import ua.kpi.windfarm4.s14.AdvancedListApp
import ua.kpi.windfarm4.s14.HomeworkListApp
import ua.kpi.windfarm4.s15.BasicSearchApp
import ua.kpi.windfarm4.s15.MediumSearchApp
import ua.kpi.windfarm4.s15.AdvancedSearchApp
import ua.kpi.windfarm4.s15.HomeworkSearchApp
import ua.kpi.windfarm4.s16.BasicMapApp
import ua.kpi.windfarm4.s16.MediumMapApp
import ua.kpi.windfarm4.s16.AdvancedMapApp
import ua.kpi.windfarm4.s16.HomeworkMapApp
import ua.kpi.windfarm4.ui.theme.WindFarmTheme

enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє"), BASIC_LEVEL("Базовий"), MIDDLE_LEVEL("Середній"), ADVANCED_LEVEL("Просунутий")
}

enum class Session(val short: String, val title: String) {
    S13("Заняття 13", "Flow і корутини"),
    S14("Заняття 14", "LazyColumn і мапи"),
    S15("Заняття 15", "Фільтрація, сортування, пошук"),
    S16("Заняття 16", "GPS і мапи")
}

/** ПР4, варіант 2 — «Вітрова електростанція з моніторингом швидкості вітру». */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initOsm(this) // налаштування OpenStreetMap (User-Agent, кеш плиток)
        setContent {
            WindFarmTheme {
                var session by rememberSaveable { mutableStateOf(Session.S13) }
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
        Session.S13 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkFlowApp(); DisplayFor.BASIC_LEVEL -> BasicFlowApp()
            DisplayFor.MIDDLE_LEVEL -> MediumFlowApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedFlowApp()
        }
        Session.S14 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkListApp(); DisplayFor.BASIC_LEVEL -> BasicListApp()
            DisplayFor.MIDDLE_LEVEL -> MediumListApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedListApp()
        }
        Session.S15 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkSearchApp(); DisplayFor.BASIC_LEVEL -> BasicSearchApp()
            DisplayFor.MIDDLE_LEVEL -> MediumSearchApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedSearchApp()
        }
        Session.S16 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkMapApp(); DisplayFor.BASIC_LEVEL -> BasicMapApp()
            DisplayFor.MIDDLE_LEVEL -> MediumMapApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedMapApp()
        }
    }
}
