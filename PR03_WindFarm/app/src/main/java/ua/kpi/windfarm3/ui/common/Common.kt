package ua.kpi.windfarm3.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Форматування числа з фіксованою кількістю знаків. */
fun Double.fmt(d: Int = 1): String = String.format(Locale.US, "%.${d}f", this)

/** Заголовок екрана (перевикористовуваний компонент). */
@Composable
fun ScreenTitle(text: String, subtitle: String? = null) {
    Text(text, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
}

/** Рядок «назва — значення». */
@Composable
fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** Кнопка меню на всю ширину. */
@Composable
fun MenuButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(text) }
}

/**
 * Універсальне поле форми: підказка (helper text), помилка, режим пароля.
 * Помилка показується, коли поле вже редагували або форму намагались надіслати.
 */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    helper: String? = null,
    showError: Boolean = value.isNotEmpty(),
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    var visible by remember { mutableStateOf(false) }
    val shown = if (showError) error else null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = shown != null,
        supportingText = { (shown ?: helper)?.let { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) {
            { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Показати") } }
        } else null,
        modifier = modifier
    )
}

/** Група чипів для вибору одного значення (переноситься на новий рядок). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Chips(items: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { FilterChip(it == selected, { onSelect(it) }, { Text(label(it)) }) }
    }
}

/** Повідомлення про результат: помилка — червоним, успіх — зеленим. */
@Composable
fun Message(text: String?, isError: Boolean) {
    if (text != null) Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

/** Картка-секція з необов'язковим заголовком. */
@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Рядок «індикатор + текст» під час завантаження. */
@Composable
fun Loading(text: String = "Завантаження…") {
    Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text)
    }
}

/** «щойно», «5 хв тому», «2 год тому». */
fun ageText(fetchedAt: Long, now: Long = System.currentTimeMillis()): String {
    val min = (now - fetchedAt) / 60_000
    return when {
        min < 1 -> "щойно"
        min < 60 -> "$min хв тому"
        else -> "${min / 60} год тому"
    }
}
