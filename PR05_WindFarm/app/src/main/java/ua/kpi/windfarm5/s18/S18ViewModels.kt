package ua.kpi.windfarm5.s18

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.WindPowerCalculator
import ua.kpi.windfarm5.notify.Alert
import ua.kpi.windfarm5.notify.AlertCategory
import ua.kpi.windfarm5.notify.AlertState
import ua.kpi.windfarm5.notify.AlertStore
import ua.kpi.windfarm5.notify.AlertThrottle
import ua.kpi.windfarm5.notify.Notifier
import ua.kpi.windfarm5.notify.ThresholdRule
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorSample
import ua.kpi.windfarm5.sensors.Sensors
import kotlin.random.Random

// ============================ БАЗОВИЙ ============================
/** Сповіщення за кнопкою та після завершення таймера (зворотний відлік у корутині). */
class BasicNotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val notifier = Notifier(app).also { it.createChannels() }
    val secondsLeft = MutableStateFlow<Int?>(null)
    val sent = MutableStateFlow(0)
    private var timer: Job? = null

    fun notifyNow() {
        notifier.raise(AlertCategory.REPORT, "Виробіток ВЕС", "За останню годину станція виробила 84,6 МВт·год")
        sent.update { it + 1 }
    }

    fun startTimer(seconds: Int = 10) {
        timer?.cancel()
        timer = viewModelScope.launch {
            for (s in seconds downTo 1) { secondsLeft.value = s; delay(1000) }
            secondsLeft.value = null
            notifier.raise(AlertCategory.MAINTENANCE, "Таймер завершено", "Огляд турбіни ВТ-03 можна починати")
            sent.update { it + 1 }
        }
    }

    fun cancelTimer() { timer?.cancel(); secondsLeft.value = null }
}

// ============================ СЕРЕДНІЙ ============================
/** Запланована подія в журналі екрана. */
data class Planned(val category: AlertCategory, val title: String, val delaySec: Long, val at: Long)

/**
 * Кілька запланованих сповіщень у різних каналах. Користувач вмикає категорії,
 * що його цікавлять; журнал фільтрується за категорією.
 */
class MediumNotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val notifier = Notifier(app).also { it.createChannels() }
    val enabled = MutableStateFlow(AlertCategory.entries.toSet())
    val filter = MutableStateFlow<AlertCategory?>(null)
    val planned = MutableStateFlow<List<Planned>>(emptyList())

    /** Журнал показаних подій з AlertStore з урахуванням фільтра за категорією. */
    val log: StateFlow<List<Alert>> = combine(AlertStore.alerts, filter) { a, f -> a.filter { f == null || it.category == f } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun toggle(c: AlertCategory) = enabled.update { if (c in it) it - c else it + c }

    /** Сценарій дня: штормове попередження, ТО, звіт — з різними затримками. */
    fun planScenario() {
        val now = System.currentTimeMillis()
        val items = listOf(
            Planned(AlertCategory.STORM, "Штормове попередження: пориви до 27 м/с", 5, now + 5_000),
            Planned(AlertCategory.SENSOR, "Температура гондоли ВТ-02: 78 °C", 10, now + 10_000),
            Planned(AlertCategory.MAINTENANCE, "Нагадування: заміна мастила ВТ-05", 15, now + 15_000),
            Planned(AlertCategory.REPORT, "Добовий звіт: 1 254 МВт·год", 20, now + 20_000)
        ).filter { it.category in enabled.value } // вимкнені категорії не плануються
        items.forEach { notifier.schedule(it.category, it.title, "Вітрова ЕС «Приазовська»", it.delaySec) }
        planned.update { it + items }
    }

    fun cancelAll() { notifier.cancelScheduled(); planned.value = emptyList() }
    fun clearLog() = AlertStore.clear()
}

// ============================ ПРОСУНУТИЙ ============================
/**
 * Інтерактивні сповіщення з діями «Підтвердити / Відкласти / Деталі».
 * Події генеруються з акселерометра (вібрація) і зі змодельованого анемометра;
 * дія в шторці змінює AlertStore, тож список на екрані оновлюється автоматично.
 */
class AdvancedNotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val notifier = Notifier(app).also { it.createChannels() }
    private val vibrationThrottle = AlertThrottle(cooldownMs = 20_000)
    private var lastStormAt = 0L
    val alerts: StateFlow<List<Alert>> = AlertStore.alerts
    val vibration = MutableStateFlow<Float?>(null)
    val wind = MutableStateFlow(10.0)
    val vibrationLimit = MutableStateFlow(13f)
    val monitoring = MutableStateFlow(true)
    private val spec = TurbineSpec()

    init {
        // Акселерометр: модуль прискорення понад поріг — «надмірна вібрація башти»
        if (Sensors.repo.isAvailable(SensorKind.ACCELEROMETER)) Sensors.repo.stream(SensorKind.ACCELEROMETER)
            .onEach { s -> vibration.value = s.scalar; checkVibration(s) }
            .catch { }
            .launchIn(viewModelScope)
        // Модель анемометра: випадкові пориви, зрідка шторм
        viewModelScope.launch {
            val rnd = Random(7)
            while (isActive) {
                delay(2000)
                wind.update { (it + rnd.nextDouble(-2.0, 2.4)).coerceIn(2.0, 29.0) }
                if (monitoring.value && wind.value >= spec.cutOutSpeed && System.currentTimeMillis() - lastStormAt > 60_000) {
                    lastStormAt = System.currentTimeMillis()
                    notifier.raise(AlertCategory.STORM, "Аварійна зупинка: вітер ${"%.1f".format(wind.value)} м/с",
                        "Турбіни переведено у флюгерне положення. Підтвердіть подію.", interactive = true, value = wind.value.toFloat())
                }
            }
        }
    }

    private fun checkVibration(s: SensorSample) {
        if (!monitoring.value) return
        val v = ThresholdRule(SensorKind.ACCELEROMETER, max = vibrationLimit.value).violation(s) ?: return
        if (vibrationThrottle.allow(SensorKind.ACCELEROMETER, s.timestampMs))
            notifier.raise(AlertCategory.SENSOR, "Надмірна вібрація башти", v, interactive = true, value = s.scalar)
    }

    fun powerKw() = WindPowerCalculator.turbinePowerKw(wind.value, spec)

    fun simulateStorm() = notifier.raise(AlertCategory.STORM, "Аварійна зупинка: вітер 27.4 м/с",
        "Турбіни переведено у флюгерне положення. Підтвердіть подію.", interactive = true, value = 27.4f)

    fun simulateVibration() = notifier.raise(AlertCategory.SENSOR, "Надмірна вібрація башти",
        "Акселерометр: 16.2 м/с² > ${"%.1f".format(vibrationLimit.value)}", interactive = true, value = 16.2f)

    /** Ті самі дії, що й у шторці, — з екрана застосунку. */
    fun confirm(a: Alert) { AlertStore.confirm(a.id); notifier.cancel(a.id) }
    fun snooze(a: Alert) {
        AlertStore.snooze(a.id, System.currentTimeMillis() + Notifier.SNOOZE_SEC * 1000)
        notifier.cancel(a.id)
        viewModelScope.launch { delay(Notifier.SNOOZE_SEC * 1000); AlertStore.wake(System.currentTimeMillis()).forEach { notifier.show(it, true) } }
    }
}

// ============================ ДОМАШНЄ ============================
/**
 * Сповіщення за часом (WorkManager) і за подіями: падіння тиску (барометр телефону)
 * як ознака наближення шторму, а також періодичний звіт про активність сенсорів.
 */
class HomeworkNotifyViewModel(app: Application) : AndroidViewModel(app) {
    private val notifier = Notifier(app).also { it.createChannels() }
    val enabled = MutableStateFlow(true)
    val reminderMin = MutableStateFlow(1f)
    val pressure = MutableStateFlow<Float?>(null)
    val pressureDrop = MutableStateFlow(0f)
    val temperature = MutableStateFlow<Float?>(null)
    val lastEvent = MutableStateFlow<String?>(null)
    private var reference: Float? = null
    private val throttle = AlertThrottle(30_000)

    init {
        listen(SensorKind.PRESSURE) { s ->
            pressure.value = s.scalar
            val ref = reference ?: s.scalar.also { reference = it }
            pressureDrop.value = ref - s.scalar
            // Падіння тиску на 3+ гПа відносно початкового — ознака наближення циклону
            if (enabled.value && ref - s.scalar >= 3f && throttle.allow(SensorKind.PRESSURE, s.timestampMs)) {
                notifier.raise(AlertCategory.STORM, "Тиск падає: ${"%.1f".format(s.scalar)} гПа",
                    "Падіння на ${"%.1f".format(ref - s.scalar)} гПа — можливе посилення вітру", value = s.scalar)
                lastEvent.value = "Штормове попередження за барометром"
            }
        }
        listen(SensorKind.TEMPERATURE) { s ->
            temperature.value = s.scalar
            if (enabled.value && s.scalar <= 1f && throttle.allow(SensorKind.TEMPERATURE, s.timestampMs)) {
                notifier.raise(AlertCategory.SENSOR, "Ризик обмерзання лопатей", "Температура ${"%.1f".format(s.scalar)} °C", value = s.scalar)
                lastEvent.value = "Попередження про обмерзання"
            }
        }
    }

    private fun listen(k: SensorKind, onSample: (SensorSample) -> Unit) {
        if (Sensors.repo.isAvailable(k)) Sensors.repo.stream(k).onEach { onSample(it) }.catch { }.launchIn(viewModelScope)
    }

    fun resetReference() { reference = pressure.value; pressureDrop.value = 0f }

    fun scheduleMaintenance() {
        if (!enabled.value) return
        val sec = (reminderMin.value * 60).toLong()
        notifier.schedule(AlertCategory.MAINTENANCE, "Планове ТО турбіни ВТ-04", "Час огляду редуктора та гальм", sec, uniqueName = "maintenance")
        lastEvent.value = "ТО заплановано через ${reminderMin.value.toInt()} хв (WorkManager)"
    }

    fun sensorReport() {
        if (!enabled.value) return
        val p = pressure.value?.let { "%.1f гПа".format(it) } ?: "немає барометра"
        val t = temperature.value?.let { "%.1f °C".format(it) } ?: "немає термометра"
        notifier.raise(AlertCategory.REPORT, "Активність сенсорів", "Тиск: $p · Температура: $t")
        lastEvent.value = "Надіслано звіт про сенсори"
    }

    fun setEnabled(on: Boolean) {
        enabled.value = on
        if (!on) notifier.cancelScheduled() // вимкнення скасовує і заплановані сповіщення
    }

    val active: StateFlow<Int> = combine(AlertStore.alerts, enabled) { a, _ -> a.count { it.state == AlertState.ACTIVE } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
}
