package ua.kpi.windfarm2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm2.s5.AdvancedNavApp
import ua.kpi.windfarm2.s5.BasicNavApp
import ua.kpi.windfarm2.s5.HomeworkNavApp
import ua.kpi.windfarm2.s5.MediumNavApp
import ua.kpi.windfarm2.s6.AdvancedFormApp
import ua.kpi.windfarm2.s6.BasicFormApp
import ua.kpi.windfarm2.s6.HomeworkFormApp
import ua.kpi.windfarm2.s6.MediumFormApp
import ua.kpi.windfarm2.s7.AdvancedMvvmApp
import ua.kpi.windfarm2.s7.BasicMvvmApp
import ua.kpi.windfarm2.s7.HomeworkMvvmApp
import ua.kpi.windfarm2.s7.MediumMvvmApp
import ua.kpi.windfarm2.s8.AdvancedRoomApp
import ua.kpi.windfarm2.s8.BasicRoomApp
import ua.kpi.windfarm2.s8.HomeworkRoomApp
import ua.kpi.windfarm2.s8.MediumRoomApp
import ua.kpi.windfarm2.ui.theme.WindFarmTheme

/** Рівні відображення. */
enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє"), BASIC_LEVEL("Базовий"), MIDDLE_LEVEL("Середній"), ADVANCED_LEVEL("Просунутий")
}

/** Практичні заняття ПР2. */
enum class Session(val short: String, val title: String) {
    S5("Заняття 5", "Головне меню та навігація"),
    S6("Заняття 6", "Форми введення даних"),
    S7("Заняття 7", "Архітектура MVVM"),
    S8("Заняття 8", "Локальне сховище Room")
}

/**
 * ПР2, варіант 2 — «Вітрова електростанція з моніторингом швидкості вітру».
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WindFarmTheme {
                var session by rememberSaveable { mutableStateOf(Session.S5) }
                var level by rememberSaveable { mutableStateOf(DisplayFor.HOMEWORK) }
                Surface(Modifier.fillMaxSize()) {
                    Column {
                        Selector(session, { session = it }, level, { level = it })
                        HorizontalDivider()
                        // key(...) — при зміні заняття/рівня стан екрана створюється заново
                        Box(Modifier.weight(1f)) {
                            androidx.compose.runtime.key(session, level) { Screen(session, level) }
                        }
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
        // FlowRow переносить чипи на новий рядок — на вузькому екрані видно всі варіанти
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
        Session.S5 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkNavApp(); DisplayFor.BASIC_LEVEL -> BasicNavApp()
            DisplayFor.MIDDLE_LEVEL -> MediumNavApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedNavApp()
        }
        Session.S6 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkFormApp(); DisplayFor.BASIC_LEVEL -> BasicFormApp()
            DisplayFor.MIDDLE_LEVEL -> MediumFormApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedFormApp()
        }
        Session.S7 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkMvvmApp(); DisplayFor.BASIC_LEVEL -> BasicMvvmApp()
            DisplayFor.MIDDLE_LEVEL -> MediumMvvmApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedMvvmApp()
        }
        Session.S8 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkRoomApp(); DisplayFor.BASIC_LEVEL -> BasicRoomApp()
            DisplayFor.MIDDLE_LEVEL -> MediumRoomApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedRoomApp()
        }
    }
}
