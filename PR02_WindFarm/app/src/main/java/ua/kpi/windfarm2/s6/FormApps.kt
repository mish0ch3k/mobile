package ua.kpi.windfarm2.s6

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.kpi.windfarm2.domain.FormValidator
import ua.kpi.windfarm2.ui.common.FormField
import ua.kpi.windfarm2.ui.common.ScreenTitle

// ========================= БАЗОВИЙ =========================
/** Два поля + перевірка на непорожність, помилка під полем. */
@Composable
fun BasicFormApp() {
    var name by remember { mutableStateOf("") }
    var power by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenTitle("Нова вітрова станція")
        FormField("Назва станції", name, { name = it }, FormValidator.required(name, "Назва"), showError = submitted)
        FormField("Потужність, МВт", power, { power = it }, FormValidator.required(power, "Потужність"), showError = submitted, keyboardType = KeyboardType.Decimal)
        Button(onClick = {
            submitted = true
            done = if (name.isNotBlank() && power.isNotBlank()) "Станцію «$name» ($power МВт) додано" else null
        }, modifier = Modifier.fillMaxWidth()) { Text("Підтвердити") }
        done?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

// ========================= СЕРЕДНІЙ =========================
/** Додаткові поля, формат e-mail, мінімальна довжина пароля, helper text, групування в Row. */
@Composable
fun MediumFormApp() {
    var name by remember { mutableStateOf("") }
    var power by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    val errors = listOf(FormValidator.stationName(name), FormValidator.capacity(power), FormValidator.email(email), FormValidator.passwordShort(password))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        ScreenTitle("Реєстрація станції", "Заповніть дані станції та відповідальної особи")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormField("Назва", name, { name = it }, errors[0], helper = "≥ 3 символи", showError = submitted || name.isNotEmpty(), modifier = Modifier.weight(1f))
            FormField("МВт", power, { power = it }, errors[1], helper = "0.1…1000", showError = submitted || power.isNotEmpty(), keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(0.6f))
        }
        FormField("E-mail відповідального", email, { email = it }, errors[2], helper = "Наприклад, op@windfarm.ua", showError = submitted || email.isNotEmpty(), keyboardType = KeyboardType.Email)
        FormField("Пароль", password, { password = it }, errors[3], helper = "Щонайменше 8 символів", showError = submitted || password.isNotEmpty(), isPassword = true)
        Button(onClick = {
            submitted = true
            done = if (errors.all { it == null }) "Станцію «${name.trim()}» зареєстровано" else null
        }, modifier = Modifier.fillMaxWidth()) { Text("Зареєструвати") }
        done?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
    }
}

// ========================= ПРОСУНУТИЙ =========================
/** Стан форми — окремий клас, щоб composable-функції лише відображали його. */
private class StationFormState {
    var name by mutableStateOf("")
    var power by mutableStateOf("")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var submitted by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var result by mutableStateOf<Pair<String, Boolean>?>(null)

    fun nameError() = FormValidator.stationName(name)
    fun powerError() = FormValidator.capacity(power)
    fun emailError() = FormValidator.uniqueEmail(email)
    fun passwordError() = FormValidator.strongPassword(password)
    fun isValid() = listOf(nameError(), powerError(), emailError(), passwordError()).all { it == null }
}

/**
 * Комплексна перевірка (унікальність e-mail, складність пароля), індикатор завантаження,
 * адаптивна ширина форми, UI розбито на окремі composable.
 */
@Composable
fun AdvancedFormApp() {
    val s = remember { StationFormState() }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val twoColumns = maxWidth >= 600.dp
        Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {
            FormHeader()
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { StationFields(s) }
                    Column(Modifier.weight(1f)) { AccountFields(s) }
                }
            } else {
                StationFields(s); AccountFields(s)
            }
            SubmitArea(s) {
                s.submitted = true
                if (!s.isValid()) { s.result = "Виправте помилки у формі" to true; return@SubmitArea }
                s.loading = true
                scope.launch {
                    delay(1500) // імітація збереження на сервері
                    s.result = if (FormValidator.register(s.email)) "Станцію «${s.name.trim()}» зареєстровано" to false
                    else "E-mail щойно зайняли" to true
                    s.loading = false
                }
            }
        }
    }
}

/** Заголовок форми. */
@Composable
private fun FormHeader() = ScreenTitle("Реєстрація вітрової станції", "Усі поля обов'язкові")

/** Поля параметрів станції. */
@Composable
private fun StationFields(s: StationFormState) {
    FormField("Назва станції", s.name, { s.name = it }, s.nameError(), "≥ 3 символи", s.submitted || s.name.isNotEmpty())
    FormField("Встановлена потужність, МВт", s.power, { s.power = it }, s.powerError(), "0.1…1000 МВт", s.submitted || s.power.isNotEmpty(), KeyboardType.Decimal)
}

/** Поля облікового запису з індикатором складності пароля. */
@Composable
private fun AccountFields(s: StationFormState) {
    FormField("E-mail", s.email, { s.email = it }, s.emailError(), "Має бути унікальним", s.submitted || s.email.isNotEmpty(), KeyboardType.Email)
    FormField("Пароль", s.password, { s.password = it }, s.passwordError(), "≥ 8 символів, A–z, 0–9, !@#", s.submitted || s.password.isNotEmpty(), isPassword = true)
    PasswordStrength(FormValidator.passwordStrength(s.password))
}

@Composable
private fun PasswordStrength(level: Int) {
    val (label, color) = when (level) {
        0, 1 -> "слабкий" to Color(0xFFC62828)
        2 -> "середній" to Color(0xFFF9A825)
        3 -> "добрий" to Color(0xFF558B2F)
        else -> "надійний" to Color(0xFF2E7D32)
    }
    LinearProgressIndicator(progress = { level / 4f }, color = color, modifier = Modifier.fillMaxWidth())
    Text("Складність пароля: $label", style = MaterialTheme.typography.labelSmall, color = color)
}

/** Кнопка або індикатор завантаження + повідомлення. */
@Composable
private fun SubmitArea(s: StationFormState, onSubmit: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        if (s.loading) CircularProgressIndicator(Modifier.size(36.dp))
        else Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) { Text("Зареєструвати") }
    }
    s.result?.let { (text, isError) ->
        Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
    }
}

// ========================= ДОМАШНЄ =========================
/** Ім'я та e-mail оператора, перевірка на непорожні поля, Column + State. */
@Composable
fun HomeworkFormApp() {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenTitle("Підписка на сповіщення про бурю", "Оператор отримає лист, коли вітер > 25 м/с")
        FormField("Ім'я", name, { name = it; ok = false }, FormValidator.required(name, "Ім'я"), "Як до вас звертатися", submitted)
        FormField("E-mail", email, { email = it; ok = false }, FormValidator.required(email, "E-mail"), "Куди надсилати сповіщення", submitted, KeyboardType.Email)
        Button(onClick = { submitted = true; ok = name.isNotBlank() && email.isNotBlank() }, modifier = Modifier.fillMaxWidth()) { Text("Підписатися") }
        if (ok) Text("Готово, ${name.trim()}! Сповіщення надходитимуть на ${email.trim()}", color = MaterialTheme.colorScheme.tertiary)
    }
}
