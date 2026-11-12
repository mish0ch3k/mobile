package ua.kpi.windfarm.s4

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ua.kpi.windfarm.logic.AuthValidator

/** Базовий рівень: заголовок, логін, пароль, кнопка; перевірка на непорожність. */
@Composable
fun BasicLoginScreen() {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text("Вхід оператора ВЕС", style = MaterialTheme.typography.headlineSmall)
        androidx.compose.material3.OutlinedTextField(login, { login = it }, label = { Text("Логін") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        androidx.compose.material3.OutlinedTextField(
            password, { password = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = {
            message = if (login.isBlank() || password.isBlank()) "Заповніть усі поля" else "Вітаємо, $login!"
        }, modifier = Modifier.fillMaxWidth()) { Text("Увійти") }
        message?.let { Text(it) }
    }
}

/** Середній рівень: форми входу та реєстрації, перемикання, Compose State, повідомлення про помилки. */
@Composable
fun MediumLoginScreen() {
    var isRegistration by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val errors = buildList {
        add(AuthValidator.login(login)); add(AuthValidator.password(password))
        if (isRegistration) { add(AuthValidator.name(name)); add(AuthValidator.email(email)); add(AuthValidator.confirm(password, confirm)) }
    }
    val valid = errors.all { it == null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (isRegistration) "Реєстрація" else "Вхід", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { isRegistration = !isRegistration; submitted = false; result = null }) {
                Text(if (isRegistration) "Маю акаунт" else "Створити акаунт")
            }
        }
        if (isRegistration) {
            AuthField("Ім'я", name, { name = it }, AuthValidator.name(name), submitted)
            AuthField("E-mail", email, { email = it }, AuthValidator.email(email), submitted, keyboardType = KeyboardType.Email)
        }
        AuthField("Логін", login, { login = it }, AuthValidator.login(login), submitted)
        AuthField("Пароль", password, { password = it }, AuthValidator.password(password), submitted, isPassword = true)
        if (isRegistration) AuthField("Підтвердження пароля", confirm, { confirm = it }, AuthValidator.confirm(password, confirm), submitted, isPassword = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                submitted = true
                result = when {
                    !valid -> "Виправте помилки у формі" to true
                    isRegistration -> if (AuthValidator.register(login, password)) "Акаунт «$login» створено" to false else "Логін зайнятий" to true
                    AuthValidator.authenticate(login, password) -> "Вхід виконано" to false
                    else -> "Невірний логін або пароль" to true
                }
            }, enabled = !submitted || valid) { Text(if (isRegistration) "Зареєструватися" else "Увійти") }
            TextButton(onClick = { login = ""; password = ""; name = ""; email = ""; confirm = ""; submitted = false; result = null }) { Text("Очистити") }
        }
        AuthMessage(result?.first, result?.second ?: false)
    }
}

/**
 * Просунутий рівень: UI розбито на composable (AuthHeader, AuthField, AuthMessage, AuthForm),
 * перевірка даних у AuthValidator, імітація мережевого запиту зі станом завантаження,
 * адаптивна ширина форми.
 */
@Composable
fun AdvancedLoginScreen() = AuthForm(advanced = true)

/** Домашнє завдання: вхід + перехід на реєстрацію, підказки, помилки, стилізація. */
@Composable
fun AuthHomework() = AuthForm(advanced = false)

@Composable
private fun AuthForm(advanced: Boolean) {
    var registration by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    fun validate(): Boolean = listOfNotNull(
        AuthValidator.login(login), AuthValidator.password(password),
        if (registration) AuthValidator.name(name) else null,
        if (registration) AuthValidator.email(email) else null,
        if (registration) AuthValidator.confirm(password, confirm) else null
    ).isEmpty()

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Форма не ширша за 480 dp — читається і на телефоні, і на планшеті
        val formWidth = if (maxWidth > 520.dp) 480.dp else maxWidth
        Column(
            Modifier.widthIn(max = formWidth).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AuthHeader(
                if (registration) "Реєстрація оператора" else "Вхід до системи ВЕС",
                if (registration) "Створіть обліковий запис" else "Демо: operator / wind2026"
            )
            if (registration) {
                AuthField("Ім'я", name, { name = it }, AuthValidator.name(name), submitted)
                AuthField("E-mail", email, { email = it }, AuthValidator.email(email), submitted, keyboardType = KeyboardType.Email)
            }
            AuthField("Логін", login, { login = it }, AuthValidator.login(login), submitted)
            AuthField("Пароль", password, { password = it }, AuthValidator.password(password), submitted, isPassword = true)
            if (registration) AuthField("Підтвердження пароля", confirm, { confirm = it }, AuthValidator.confirm(password, confirm), submitted, isPassword = true)

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(36.dp))
                } else {
                    Button(onClick = {
                        submitted = true
                        if (!validate()) { result = "Виправте помилки у формі" to true; return@Button }
                        if (advanced) {
                            loading = true
                            scope.launch {
                                delay(1200) // імітація запиту до сервера
                                result = authenticateOrRegister(registration, login, password)
                                loading = false
                            }
                        } else {
                            result = authenticateOrRegister(registration, login, password)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (registration) "Зареєструватися" else "Увійти") }
                }
            }
            TextButton(onClick = { registration = !registration; submitted = false; result = null }, enabled = !loading) {
                Text(if (registration) "Вже маєте акаунт? Увійти" else "Немає акаунта? Зареєструватися")
            }
            AuthMessage(result?.first, result?.second ?: false)
        }
    }
}

/** Бізнес-логіка: повертає (повідомлення, чи помилка). */
internal fun authenticateOrRegister(registration: Boolean, login: String, password: String): Pair<String, Boolean> =
    if (registration) {
        if (AuthValidator.register(login, password)) "Акаунт «$login» створено. Тепер увійдіть." to false
        else "Логін «$login» уже зайнятий" to true
    } else {
        if (AuthValidator.authenticate(login, password)) "Вхід виконано. Вітаємо, $login!" to false
        else "Невірний логін або пароль" to true
    }
