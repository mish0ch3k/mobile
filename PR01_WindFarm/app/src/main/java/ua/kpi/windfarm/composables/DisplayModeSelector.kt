package ua.kpi.windfarm.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm.DisplayFor
import ua.kpi.windfarm.Session

/**
 * Перемикач заняття та рівня складності.
 * Стан зберігається в MainActivity, сюди передаються значення і callbacks (state hoisting).
 */
@Composable
fun DisplayModeSelector(
    session: Session,
    onSessionChange: (Session) -> Unit,
    selected: DisplayFor,
    onSelectedChange: (DisplayFor) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("Вітрова ЕС · ${session.short}: ${session.title}", style = MaterialTheme.typography.titleSmall)
        ChipRow(Session.entries, session, { it.short }, onSessionChange)
        ChipRow(DisplayFor.entries, selected, { it.title }, onSelectedChange)
    }
}

// FlowRow переносить чипи на новий рядок, тож усі варіанти видно на вузькому екрані
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onClick: (T) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onClick(item) }, label = { Text(label(item)) })
        }
    }
}
