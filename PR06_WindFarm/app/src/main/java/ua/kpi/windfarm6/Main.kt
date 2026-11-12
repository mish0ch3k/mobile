package ua.kpi.windfarm6

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.kpi.windfarm6.monitor.ThemeMode
import ua.kpi.windfarm6.monitor.ThemePrefs
import ua.kpi.windfarm6.s21.AdvancedAnimApp
import ua.kpi.windfarm6.s21.BasicAnimApp
import ua.kpi.windfarm6.s21.HomeworkAnimApp
import ua.kpi.windfarm6.s21.MediumAnimApp
import ua.kpi.windfarm6.s22.AdvancedFileApp
import ua.kpi.windfarm6.s22.BasicCsvApp
import ua.kpi.windfarm6.s22.HomeworkFileApp
import ua.kpi.windfarm6.s22.MediumJsonApp
import ua.kpi.windfarm6.s23.AdvancedMediaApp
import ua.kpi.windfarm6.s23.BasicPhotoApp
import ua.kpi.windfarm6.s23.HomeworkMediaApp
import ua.kpi.windfarm6.s23.MediumDocsApp
import ua.kpi.windfarm6.s24.AdvancedPdfApp
import ua.kpi.windfarm6.s24.BasicPdfApp
import ua.kpi.windfarm6.s24.HomeworkPdfApp
import ua.kpi.windfarm6.s24.MediumPdfApp
import ua.kpi.windfarm6.s25.AdvancedTestApp
import ua.kpi.windfarm6.s25.BasicTestApp
import ua.kpi.windfarm6.s25.HomeworkTestApp
import ua.kpi.windfarm6.s25.MediumTestApp
import ua.kpi.windfarm6.s26.AdvancedMonitorApp
import ua.kpi.windfarm6.s26.BasicMonitorApp
import ua.kpi.windfarm6.s26.HomeworkMonitorApp
import ua.kpi.windfarm6.s26.MediumMonitorApp
import ua.kpi.windfarm6.s27.AdvancedReleaseApp
import ua.kpi.windfarm6.s27.BasicReleaseApp
import ua.kpi.windfarm6.s27.HomeworkReleaseApp
import ua.kpi.windfarm6.s27.MediumReleaseApp
import ua.kpi.windfarm6.ui.theme.WindFarmTheme

enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє"), BASIC_LEVEL("Базовий"), MIDDLE_LEVEL("Середній"), ADVANCED_LEVEL("Просунутий")
}

enum class Session(val short: String, val title: String) {
    S21("21", "Анімація даних"),
    S22("22", "CSV і JSON"),
    S23("23", "Фото й документи"),
    S24("24", "PDF-звіти"),
    S25("25", "Тестування"),
    S26("26", "Ресурси й теми"),
    S27("27", "Підготовка до релізу")
}

/** ПР6, варіант 2 — «Вітрова електростанція з моніторингом швидкості вітру». */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemePrefs.init(this) // збережений вибір теми (заняття 26)
        setContent {
            val mode by ThemePrefs.mode.collectAsStateWithLifecycle()
            val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
            WindFarmTheme(darkTheme = dark) {
                var session by rememberSaveable { mutableStateOf(Session.S21) }
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
        Text("Вітрова ЕС · Заняття ${session.short}: ${session.title}", style = MaterialTheme.typography.titleSmall)
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
        Session.S21 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkAnimApp(); DisplayFor.BASIC_LEVEL -> BasicAnimApp()
            DisplayFor.MIDDLE_LEVEL -> MediumAnimApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedAnimApp()
        }
        Session.S22 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkFileApp(); DisplayFor.BASIC_LEVEL -> BasicCsvApp()
            DisplayFor.MIDDLE_LEVEL -> MediumJsonApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedFileApp()
        }
        Session.S23 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkMediaApp(); DisplayFor.BASIC_LEVEL -> BasicPhotoApp()
            DisplayFor.MIDDLE_LEVEL -> MediumDocsApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedMediaApp()
        }
        Session.S24 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkPdfApp(); DisplayFor.BASIC_LEVEL -> BasicPdfApp()
            DisplayFor.MIDDLE_LEVEL -> MediumPdfApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedPdfApp()
        }
        Session.S25 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkTestApp(); DisplayFor.BASIC_LEVEL -> BasicTestApp()
            DisplayFor.MIDDLE_LEVEL -> MediumTestApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedTestApp()
        }
        Session.S26 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkMonitorApp(); DisplayFor.BASIC_LEVEL -> BasicMonitorApp()
            DisplayFor.MIDDLE_LEVEL -> MediumMonitorApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedMonitorApp()
        }
        Session.S27 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkReleaseApp(); DisplayFor.BASIC_LEVEL -> BasicReleaseApp()
            DisplayFor.MIDDLE_LEVEL -> MediumReleaseApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedReleaseApp()
        }
    }
}
