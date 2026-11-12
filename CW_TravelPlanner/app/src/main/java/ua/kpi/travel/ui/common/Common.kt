package ua.kpi.travel.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ua.kpi.travel.domain.ActivityCategory
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val UK: Locale = Locale.forLanguageTag("uk")
val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", UK)
val longDate: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", UK)

fun LocalDate.short(): String = format(shortDate)
fun LocalDate.long(): String = format(longDate).replaceFirstChar { it.uppercase() }

fun money(v: Double, cur: String): String = String.format(Locale.US, "%,.0f", v).replace(',', ' ') + " " + cur

fun ActivityCategory.icon(): ImageVector = when (this) {
    ActivityCategory.TRANSPORT -> Icons.Filled.DirectionsBus
    ActivityCategory.STAY -> Icons.Filled.Hotel
    ActivityCategory.SIGHTSEEING -> Icons.Filled.Museum
    ActivityCategory.FOOD -> Icons.Filled.Restaurant
    ActivityCategory.ACTIVE -> Icons.Filled.Hiking
    ActivityCategory.MEETING -> Icons.Filled.Groups
    ActivityCategory.OTHER -> Icons.Filled.MoreHoriz
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

@Composable
fun Progress(value: Double, color: Color = MaterialTheme.colorScheme.primary) =
    LinearProgressIndicator(progress = { value.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        color = color, strokeCap = StrokeCap.Round)

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, error: String?, modifier: Modifier = Modifier.fillMaxWidth(),
          number: Boolean = false, singleLine: Boolean = true) =
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, isError = error != null,
        supportingText = error?.let { { Text(it) } }, singleLine = singleLine,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)

@Composable
fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items.size) { i -> val it = items[i]; FilterChip(it == selected, { onSelect(it) }, { Text(label(it)) }) }
    }
}

/** Діалог вибору дати Material 3; дати в epochDay (UTC-опівніч, як повертає DatePicker). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = (initial ?: LocalDate.now().toEpochDay()) * 86_400_000L)
    DatePickerDialog(onDismissRequest = onDismiss,
        confirmButton = { TextButton({ state.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) }; onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Скасувати") } }) { DatePicker(state) }
}

fun epochToDate(day: Long): LocalDate = LocalDate.ofEpochDay(day)
@Suppress("unused") val utc: ZoneOffset = ZoneOffset.UTC
