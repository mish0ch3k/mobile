package ua.kpi.windfarm5

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.kpi.windfarm5.notify.Notifier
import ua.kpi.windfarm5.s17.AdvancedSensorApp
import ua.kpi.windfarm5.s17.BasicSensorApp
import ua.kpi.windfarm5.s17.HomeworkSensorApp
import ua.kpi.windfarm5.s17.MediumSensorApp
import ua.kpi.windfarm5.s18.AdvancedNotifyApp
import ua.kpi.windfarm5.s18.BasicNotifyApp
import ua.kpi.windfarm5.s18.HomeworkNotifyApp
import ua.kpi.windfarm5.s18.MediumNotifyApp
import ua.kpi.windfarm5.s19.AdvancedChartApp
import ua.kpi.windfarm5.s19.BasicChartApp
import ua.kpi.windfarm5.s19.HomeworkChartApp
import ua.kpi.windfarm5.s19.MediumChartApp
import ua.kpi.windfarm5.s20.AdvancedComponentApp
import ua.kpi.windfarm5.s20.BasicComponentApp
import ua.kpi.windfarm5.s20.HomeworkComponentApp
import ua.kpi.windfarm5.s20.MediumComponentApp
import ua.kpi.windfarm5.sensors.Sensors
import ua.kpi.windfarm5.ui.theme.WindFarmTheme

enum class DisplayFor(val title: String) {
    HOMEWORK("Домашнє"), BASIC_LEVEL("Базовий"), MIDDLE_LEVEL("Середній"), ADVANCED_LEVEL("Просунутий")
}

enum class Session(val short: String, val title: String) {
    S17("Заняття 17", "Сенсори"),
    S18("Заняття 18", "Локальні сповіщення"),
    S19("Заняття 19", "Графіки та діаграми"),
    S20("Заняття 20", "Кастомні компоненти")
}

/** ПР5, варіант 2 — «Вітрова електростанція з моніторингом швидкості вітру». */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sensors.init(this)
        Notifier(this).createChannels()
        handleIntent(intent)
        setContent {
            WindFarmTheme {
                var session by rememberSaveable { mutableStateOf(Session.S17) }
                var level by rememberSaveable { mutableStateOf(DisplayFor.BASIC_LEVEL) }
                // «Деталі» у сповіщенні відкривають екран інтерактивних сповіщень із виділеною подією
                val opened by Notifier.openedAlert.collectAsStateWithLifecycle()
                LaunchedEffect(opened) { if (opened != null) { session = Session.S18; level = DisplayFor.ADVANCED_LEVEL } }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val id = intent?.getIntExtra(Notifier.EXTRA_ALERT_ID, -1) ?: -1
        if (id >= 0) Notifier.openedAlert.value = id
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
        Session.S17 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkSensorApp(); DisplayFor.BASIC_LEVEL -> BasicSensorApp()
            DisplayFor.MIDDLE_LEVEL -> MediumSensorApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedSensorApp()
        }
        Session.S18 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkNotifyApp(); DisplayFor.BASIC_LEVEL -> BasicNotifyApp()
            DisplayFor.MIDDLE_LEVEL -> MediumNotifyApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedNotifyApp()
        }
        Session.S19 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkChartApp(); DisplayFor.BASIC_LEVEL -> BasicChartApp()
            DisplayFor.MIDDLE_LEVEL -> MediumChartApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedChartApp()
        }
        Session.S20 -> when (level) {
            DisplayFor.HOMEWORK -> HomeworkComponentApp(); DisplayFor.BASIC_LEVEL -> BasicComponentApp()
            DisplayFor.MIDDLE_LEVEL -> MediumComponentApp(); DisplayFor.ADVANCED_LEVEL -> AdvancedComponentApp()
        }
    }
}
