package ua.kpi.windfarm5.notify

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorSample

/**
 * Категорії подій — кожна має власний канал сповіщень (NotificationChannel)
 * з окремою важливістю та звуком, які користувач може змінити в налаштуваннях Android.
 */
enum class AlertCategory(val channelId: String, val title: String, val description: String, val importance: Importance) {
    STORM("storm", "Штормові попередження", "Вітер вище швидкості вимкнення турбін", Importance.HIGH),
    SENSOR("sensor", "Показники сенсорів", "Вихід показів сенсорів за порогові значення", Importance.HIGH),
    MAINTENANCE("maintenance", "Технічне обслуговування", "Нагадування про роботи на турбінах", Importance.DEFAULT),
    REPORT("report", "Звіти виробітку", "Підсумки виробітку станції", Importance.LOW);

    enum class Importance { LOW, DEFAULT, HIGH }
}

enum class AlertState(val title: String) { ACTIVE("Активне"), CONFIRMED("Підтверджено"), SNOOZED("Відкладено") }

/** Подія, що показується і в застосунку, і як сповіщення. [id] = id сповіщення. */
data class Alert(
    val id: Int,
    val category: AlertCategory,
    val title: String,
    val text: String,
    val createdAt: Long,
    val state: AlertState = AlertState.ACTIVE,
    val snoozedUntil: Long? = null,
    val value: Float? = null
)

/**
 * Сховище подій — єдине джерело правди для UI і для обробника дій сповіщень.
 * Натискання «Підтвердити» в шторці змінює стан тут, і екран застосунку оновлюється сам.
 */
object AlertStore {
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    private var nextId = 1000

    @Synchronized fun newId(): Int = nextId++

    fun add(a: Alert) = _alerts.update { listOf(a) + it.filterNot { x -> x.id == a.id } }

    fun confirm(id: Int) = _alerts.update { l -> l.map { if (it.id == id) it.copy(state = AlertState.CONFIRMED, snoozedUntil = null) else it } }

    fun snooze(id: Int, until: Long) = _alerts.update { l -> l.map { if (it.id == id) it.copy(state = AlertState.SNOOZED, snoozedUntil = until) else it } }

    /** Повернути відкладені події, час яких настав. Повертає ті, що знову стали активними. */
    fun wake(now: Long): List<Alert> {
        val due = _alerts.value.filter { it.state == AlertState.SNOOZED && (it.snoozedUntil ?: Long.MAX_VALUE) <= now }
        if (due.isNotEmpty()) _alerts.update { l -> l.map { a -> if (due.any { it.id == a.id }) a.copy(state = AlertState.ACTIVE, snoozedUntil = null) else a } }
        return due.map { it.copy(state = AlertState.ACTIVE, snoozedUntil = null) }
    }

    fun get(id: Int): Alert? = _alerts.value.firstOrNull { it.id == id }
    fun clear() { _alerts.value = emptyList() }
    fun active(): List<Alert> = _alerts.value.filter { it.state == AlertState.ACTIVE }
}

/** Поріг сповіщення для сенсора: значення поза [min, max] — порушення. */
data class ThresholdRule(val kind: SensorKind, val min: Float? = null, val max: Float? = null, val enabled: Boolean = true) {
    fun violation(s: SensorSample): String? {
        if (!enabled || s.kind != kind) return null
        val v = s.scalar
        return when {
            max != null && v > max -> "${kind.title}: ${"%.1f".format(v)} ${kind.unit} > ${"%.1f".format(max)}"
            min != null && v < min -> "${kind.title}: ${"%.1f".format(v)} ${kind.unit} < ${"%.1f".format(min)}"
            else -> null
        }
    }
}

/**
 * Захист від «зливи» сповіщень: повторне порушення того самого правила
 * не частіше ніж раз на [cooldownMs].
 */
class AlertThrottle(private val cooldownMs: Long = 30_000) {
    private val last = HashMap<SensorKind, Long>()
    @Synchronized fun allow(kind: SensorKind, now: Long): Boolean {
        val prev = last[kind]
        if (prev != null && now - prev < cooldownMs) return false
        last[kind] = now
        return true
    }
}
