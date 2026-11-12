package ua.kpi.windfarm3.s11

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm3.auth.Jwt
import ua.kpi.windfarm3.auth.MockUser
import ua.kpi.windfarm3.auth.Permission
import ua.kpi.windfarm3.auth.SummaryDto
import ua.kpi.windfarm3.auth.TokenClaims
import ua.kpi.windfarm3.auth.TurbineTelemetryDto
import ua.kpi.windfarm3.s10.Load
import ua.kpi.windfarm3.ui.common.Chips
import ua.kpi.windfarm3.ui.common.FormField
import ua.kpi.windfarm3.ui.common.InfoRow
import ua.kpi.windfarm3.ui.common.Loading
import ua.kpi.windfarm3.ui.common.Message
import ua.kpi.windfarm3.ui.common.ScreenTitle
import ua.kpi.windfarm3.ui.common.SectionCard
import ua.kpi.windfarm3.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Форма входу: поля, підказка з тестовими акаунтами, кнопка. */
@Composable
private fun LoginFields(form: LoginForm, onChange: (LoginForm) -> Unit, users: List<MockUser>, onLogin: () -> Unit, loading: Boolean = false) {
    FormField("Логін", form.login, { onChange(form.copy(login = it)) }, form.loginError, showError = form.submitted)
    FormField("Пароль", form.password, { onChange(form.copy(password = it)) }, form.passwordError, showError = form.submitted, isPassword = true)
    Text("Тестові акаунти:", style = MaterialTheme.typography.labelMedium)
    Chips(users, null, { "${it.login} · ${it.role.title}" }) { onChange(form.fill(it)) }
    if (loading) Loading("Вхід…") else Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Увійти") }
}

private fun time(sec: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(sec * 1000))

/** Вміст токена: роль, термін дії, сам токен. */
@Composable
private fun ClaimsCard(c: TokenClaims, token: String?) {
    SectionCard("Дані з токена (JWT)") {
        InfoRow("Користувач", c.name)
        InfoRow("Роль", c.role.title)
        InfoRow("Видано / дійсний до", "${time(c.issuedAt)} / ${time(c.expiresAt)}")
        if (token != null) Text(token.chunked(48).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SummaryCard(state: Load<SummaryDto>) {
    when (state) {
        Load.Idle -> {}
        Load.Loading -> Loading("GET /api/station/summary…")
        is Load.Error -> Message("${state.error.title}: ${state.error.userMessage}", true)
        is Load.Success -> SectionCard("Захищений ресурс: огляд станції") {
            val s = state.data
            InfoRow("Станція", s.station ?: "—")
            InfoRow("Турбін", "${s.turbines ?: 0}")
            InfoRow("Потужність", "${(s.powerMw ?: 0.0).fmt(2)} МВт")
            InfoRow("Середній вітер", "${(s.windMs ?: 0.0).fmt()} м/с")
            InfoRow("Активних тривог", "${s.alarms ?: 0}")
        }
    }
}

@Composable
private fun TelemetryCard(state: Load<List<TurbineTelemetryDto>>, onControl: ((Int, Boolean) -> Unit)?) {
    when (state) {
        Load.Idle -> {}
        Load.Loading -> Loading("GET /api/telemetry…")
        is Load.Error -> Message("${state.error.title}: ${state.error.userMessage}", true)
        is Load.Success -> SectionCard("Телеметрія турбін") {
            state.data.forEach { t ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ВТ-${t.id}: ${(t.wind ?: 0.0).fmt()} м/с · ${(t.powerKw ?: 0.0).fmt(0)} кВт · ${if (t.running == true) "працює" else "зупинена"}",
                            style = MaterialTheme.typography.bodyMedium)
                        t.alarm?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (onControl != null && t.id != null) TextButton(onClick = { onControl(t.id, t.running == true) }) {
                        Text(if (t.running == true) "Стоп" else "Пуск")
                    }
                }
            }
        }
    }
}

@Composable
private fun Screen(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), content = content)

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicAuthApp(vm: BasicAuthViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Вхід (mocked token)", "Токен генерується локально і зберігається у ViewModel")
        if (token == null) LoginFields(form, { vm.form.value = it }, vm.users, vm::login)
        Message(msg?.first, msg?.second ?: false)
        token?.let { t ->
            Jwt.decode(t)?.let { ClaimsCard(it, t) }
            OutlinedButton(onClick = vm::logout) { Text("Вийти") }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumAuthApp(vm: MediumAuthViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val ttl by vm.ttl.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    Screen {
        ScreenTitle("Токен у сховищі + Interceptor", "Authorization: Bearer <token> додається автоматично")
        val s = session
        if (s == null || s.isExpired(now)) {
            Text("Термін дії токена:", style = MaterialTheme.typography.labelMedium)
            Chips(listOf(30L, 300L, 3600L), ttl, { if (it < 60) "$it с" else "${it / 60} хв" }) { vm.ttl.value = it }
            LoginFields(form, { vm.form.value = it }, vm.users, vm::login, loading)
        } else {
            ClaimsCard(s, vm.storedToken)
            val left = s.secondsLeft(now)
            Text("До завершення сесії: $left с", color = if (left < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(progress = { (left.toFloat() / (s.expiresAt - s.issuedAt)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = vm::loadSecure) { Icon(Icons.Filled.Lock, null); Text(" Захищений ресурс") }
                OutlinedButton(onClick = vm::logout) { Text("Вийти") }
            }
        }
        Message(msg?.first, msg?.second ?: false)
        SummaryCard(summary)
        if (s != null && s.isExpired(now)) OutlinedButton(onClick = vm::loadSecure) { Text("Спробувати запит із простроченим токеном") }
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedAuthApp(vm: AdvancedAuthViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    Screen {
        val s = session
        if (s == null) {
            ScreenTitle("Вхід з ролями", "Адміністратор · Оператор · Спостерігач")
            LoginFields(form, { vm.form.value = it }, vm.users, vm::login)
            Message(msg?.first, msg?.second ?: false)
            return@Screen
        }
        ScreenTitle("Пульт ВЕС", "${s.name} · роль: ${s.role.title}")
        // Перелік прав ролі — наочно показує, чому частина розділів прихована
        Chips(Permission.entries, null, { (if (it in s.role.permissions) "✓ " else "✕ ") + it.title }) {}
        Message(msg?.first, msg?.second ?: false)
        SummaryCard(summary)
        if (vm.can(Permission.VIEW_TELEMETRY))
            TelemetryCard(telemetry, if (vm.can(Permission.CONTROL_TURBINES)) vm::control else null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.can(Permission.ACK_ALARMS)) Button(onClick = vm::ackAlarms) { Text("Підтвердити тривоги") }
            OutlinedButton(onClick = vm::loadAll) { Text("Оновити") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Перевірка захисту API", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = vm::checkAccess, label = { Text("Перевірити доступ") })
            AssistChip(onClick = vm::tamper, label = { Text("Підробити роль") })
            AssistChip(onClick = vm::forgetToken, label = { Text("Без токена") })
        }
        checks.forEach { c ->
            InfoRow(c.title, if (c.code == 200) "200 · ${c.text}" else "${c.code} · ${c.text}")
        }
        OutlinedButton(onClick = vm::logout, modifier = Modifier.padding(top = 8.dp)) { Text("Вийти") }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkAuthApp(vm: HomeworkAuthViewModel = viewModel()) {
    val form by vm.form.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val telemetry by vm.telemetry.collectAsStateWithLifecycle()
    Screen {
        val s = session
        if (s == null) {
            ScreenTitle("Система моніторингу ВЕС", "Увійдіть, щоб переглянути дані станції")
            LoginFields(form, { vm.form.value = it }, vm.users, vm::login, loading)
            Message(error, true)
        } else {
            ScreenTitle("Вітаємо, ${s.name}", "Роль: ${s.role.title} · токен ${vm.tokenPreview()}")
            SummaryCard(summary)
            if (vm.canTelemetry()) TelemetryCard(telemetry, null)
            else Message("Телеметрія турбін доступна лише операторам і адміністраторам", true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::refresh) { Text("Оновити") }
                OutlinedButton(onClick = vm::logout) { Text("Вийти") }
            }
        }
    }
}
