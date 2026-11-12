package ua.kpi.windfarm.s4

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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

/** Заголовок екрана авторизації. */
@Composable
fun AuthHeader(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium)
}

/**
 * Поле введення з повідомленням про помилку.
 * Помилка показується лише після того, як користувач щось ввів або натиснув кнопку (showErrors).
 */
@Composable
fun AuthField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    showErrors: Boolean,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    var visible by remember { mutableStateOf(false) }
    val shownError = if (showErrors || value.isNotEmpty()) error else null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = shownError != null,
        supportingText = { if (shownError != null) Text(shownError) },
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = "Показати пароль")
                }
            }
        } else null,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Повідомлення про результат (успіх / помилка). */
@Composable
fun AuthMessage(text: String?, isError: Boolean) {
    if (text != null) {
        Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
    }
}
