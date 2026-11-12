package ua.kpi.windfarm5.s18

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import ua.kpi.windfarm5.notify.Alert
import ua.kpi.windfarm5.notify.AlertCategory
import ua.kpi.windfarm5.notify.AlertState
import ua.kpi.windfarm5.notify.Notifier
import ua.kpi.windfarm5.ui.common.Chips
import ua.kpi.windfarm5.ui.common.InfoRow
import ua.kpi.windfarm5.ui.common.Message
import ua.kpi.windfarm5.ui.common.ScreenTitle
import ua.kpi.windfarm5.ui.common.SectionCard
import ua.kpi.windfarm5.ui.common.fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

/**
 * Дозвіл на сповіщення (Android 13+). Без нього NotificationManager мовчки нічого не покаже,
 * тож екран пояснює це і дає кнопку запиту дозволу або переходу в налаштування.
 */
@Composable
fun NotificationPermissionBar() {
    val context = LocalContext.current
    val notifier = remember { Notifier(context) }
    var allowed by remember { mutableStateOf(notifier.canPost()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = notifier.canPost() }
    if (allowed) return
    SectionCard {
        Message("Сповіщення вимкнені для застосунку", isError = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (Build.VERSION.SDK_INT >= 33) Button({ launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Дозволити") }
            OutlinedButton({
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Налаштування") }
        }
    }
}

/** Рядок події: категорія, час, стан. */
@Composable
private fun AlertRow(a: Alert, highlighted: Boolean = false, actions: (@Composable () -> Unit)? = null) {
    val bg = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(bg, MaterialTheme.shapes.small).padding(10.dp)) {
        Text(a.title, style = MaterialTheme.typography.titleSmall,
            color = if (a.state == AlertState.ACTIVE && a.category.importance == AlertCategory.Importance.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(a.text, style = MaterialTheme.typography.bodySmall)
        Text("${a.category.title} · ${timeFmt.format(Date(a.createdAt))} · ${a.state.title}" +
            (a.snoozedUntil?.let { " до ${timeFmt.format(Date(it))}" } ?: ""), style = MaterialTheme.typography.labelSmall)
        actions?.invoke()
    }
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicNotifyApp(vm: BasicNotifyViewModel = viewModel()) {
    val left by vm.secondsLeft.collectAsStateWithLifecycle()
    val sent by vm.sent.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Локальні сповіщення", "NotificationManager · канали Android 8+")
        NotificationPermissionBar()
        Button(vm::notifyNow, Modifier.fillMaxWidth()) { Text("Сповістити про виробіток") }
        if (left == null) OutlinedButton({ vm.startTimer(10) }, Modifier.fillMaxWidth()) { Text("Таймер огляду: 10 с") }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Залишилось: $left с", style = MaterialTheme.typography.headlineSmall)
            TextButton(vm::cancelTimer) { Text("Скасувати") }
        }
        InfoRow("Надіслано сповіщень", "$sent")
        Text("Після завершення таймера сповіщення з’явиться в шторці, навіть якщо згорнути застосунок.", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumNotifyApp(vm: MediumNotifyViewModel = viewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val planned by vm.planned.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notifier = remember { Notifier(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } } // для зворотного відліку

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Канали сповіщень")
            NotificationPermissionBar()
            SectionCard("Категорії подій") {
                AlertCategory.entries.forEach { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.bodyMedium)
                            Text("Важливість: ${c.importance.name.lowercase()}" + if (!notifier.channelEnabled(c)) " · канал вимкнено в Android" else "",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(c in enabled, { vm.toggle(c) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(vm::planScenario) { Text("Запланувати 4 події") }
                OutlinedButton(vm::cancelAll) { Text("Скасувати") }
            }
            val pending = planned.filter { it.at > now }
            if (pending.isNotEmpty()) SectionCard("Очікують (WorkManager)") {
                pending.forEach { InfoRow(it.category.title, "через ${(it.at - now + 999) / 1000} с") }
            }
            Text("Журнал", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Chips(listOf<AlertCategory?>(null) + AlertCategory.entries, filter, { it?.title ?: "Усі" }) { vm.filter.value = it }
            if (log.isEmpty()) Text("Подій ще немає", style = MaterialTheme.typography.bodySmall)
        }
        items(log, key = { it.id }) { AlertRow(it) }
        item { if (log.isNotEmpty()) TextButton(vm::clearLog) { Text("Очистити журнал") } }
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedNotifyApp(vm: AdvancedNotifyViewModel = viewModel()) {
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val vibration by vm.vibration.collectAsStateWithLifecycle()
    val wind by vm.wind.collectAsStateWithLifecycle()
    val limit by vm.vibrationLimit.collectAsStateWithLifecycle()
    val monitoring by vm.monitoring.collectAsStateWithLifecycle()
    val opened by Notifier.openedAlert.collectAsStateWithLifecycle()
    val active = alerts.count { it.state == AlertState.ACTIVE }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Інтерактивні сповіщення", "Дії прямо зі шторки синхронізуються з екраном")
            NotificationPermissionBar()
            SectionCard("Дані в реальному часі") {
                InfoRow("Вітер (модель анемометра)", "${wind.fmt()} м/с")
                InfoRow("Потужність турбіни", "${vm.powerKw().fmt(0)} кВт")
                InfoRow("Вібрація (акселерометр)", vibration?.let { "${it.toDouble().fmt()} м/с²" } ?: "немає сенсора")
                Text("Поріг вібрації: ${limit.toDouble().fmt()} м/с²", style = MaterialTheme.typography.bodySmall)
                Slider(limit, { vm.vibrationLimit.value = it }, valueRange = 10f..30f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(monitoring, { vm.monitoring.value = it }); Text("Автоматичні тривоги")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(vm::simulateStorm) { Text("Шторм") }
                OutlinedButton(vm::simulateVibration) { Text("Вібрація") }
            }
            Text("Події: ${alerts.size}, активних: $active", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        }
        items(alerts, key = { it.id }) { a ->
            AlertRow(a, highlighted = a.id == opened) {
                if (a.state == AlertState.ACTIVE) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ vm.confirm(a) }) { Text("Підтвердити") }
                    TextButton({ vm.snooze(a) }) { Text("Відкласти 1 хв") }
                }
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkNotifyApp(vm: HomeworkNotifyViewModel = viewModel()) {
    val enabled by vm.enabled.collectAsStateWithLifecycle()
    val minutes by vm.reminderMin.collectAsStateWithLifecycle()
    val pressure by vm.pressure.collectAsStateWithLifecycle()
    val drop by vm.pressureDrop.collectAsStateWithLifecycle()
    val temperature by vm.temperature.collectAsStateWithLifecycle()
    val last by vm.lastEvent.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenTitle("Сповіщення ВЕС")
        NotificationPermissionBar()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(enabled, vm::setEnabled); Text(if (enabled) "Сповіщення увімкнено" else "Сповіщення вимкнено")
        }
        SectionCard("Сенсори (події)") {
            InfoRow("Тиск", pressure?.let { "${it.toDouble().fmt()} гПа" } ?: "немає барометра")
            InfoRow("Падіння тиску", "${drop.toDouble().fmt()} гПа (тривога від 3)")
            InfoRow("Температура", temperature?.let { "${it.toDouble().fmt()} °C" } ?: "немає термометра")
            TextButton(vm::resetReference) { Text("Зафіксувати поточний тиск як базовий") }
        }
        SectionCard("За часом (WorkManager)") {
            Text("Нагадування про ТО через ${minutes.toInt()} хв")
            Slider(minutes, { vm.reminderMin.value = it }, valueRange = 1f..60f, steps = 58)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(vm::scheduleMaintenance, enabled = enabled) { Text("Запланувати") }
                OutlinedButton(vm::sensorReport, enabled = enabled) { Text("Звіт сенсорів") }
            }
        }
        InfoRow("Активних подій", "$active")
        Message(last, isError = false)
    }
}
