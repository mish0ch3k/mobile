package ua.kpi.windfarm5.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import ua.kpi.windfarm5.MainActivity
import ua.kpi.windfarm5.R
import java.util.concurrent.TimeUnit

/**
 * Робота з локальними сповіщеннями: канали, побудова сповіщень з діями,
 * планування через WorkManager (переживає закриття застосунку).
 */
class Notifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    /** Канали створюються один раз; повторний виклик безпечний (Android ігнорує наявні). */
    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        AlertCategory.entries.forEach { c ->
            val importance = when (c.importance) {
                AlertCategory.Importance.LOW -> NotificationManager.IMPORTANCE_LOW
                AlertCategory.Importance.DEFAULT -> NotificationManager.IMPORTANCE_DEFAULT
                AlertCategory.Importance.HIGH -> NotificationManager.IMPORTANCE_HIGH
            }
            manager.createNotificationChannel(NotificationChannel(c.channelId, c.title, importance).apply {
                description = c.description
                enableVibration(c.importance == AlertCategory.Importance.HIGH)
            })
        }
    }

    /** Android 13+ вимагає дозволу POST_NOTIFICATIONS; користувач може також вимкнути сповіщення застосунку. */
    fun canPost(): Boolean =
        nm.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun channelEnabled(c: AlertCategory): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(c.channelId)?.importance != NotificationManager.IMPORTANCE_NONE

    /**
     * Показ сповіщення. [interactive] додає дії «Підтвердити», «Відкласти», «Деталі»,
     * які обробляє AlertActionReceiver без відкриття застосунку.
     */
    @SuppressLint("MissingPermission")
    fun show(a: Alert, interactive: Boolean = false) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(context, a.id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_ALERT_ID, a.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(context, a.category.channelId)
            .setSmallIcon(R.drawable.ic_stat_wind)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(a.createdAt)
            .setShowWhen(true)
            .setGroup(GROUP)
            .setPriority(if (a.category.importance == AlertCategory.Importance.HIGH) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (a.category == AlertCategory.STORM || a.category == AlertCategory.SENSOR) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
        if (interactive) {
            b.addAction(0, "Підтвердити", actionIntent(a.id, ACTION_CONFIRM))
            b.addAction(0, "Відкласти 1 хв", actionIntent(a.id, ACTION_SNOOZE))
            b.addAction(0, "Деталі", open)
        }
        nm.notify(a.id, b.build()) // дозвіл перевірено в canPost()
    }

    fun cancel(id: Int) = nm.cancel(id)

    private fun actionIntent(id: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, id * 10 + action.length,
            Intent(context, AlertActionReceiver::class.java).setAction(action).putExtra(EXTRA_ALERT_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Планування сповіщення через [delaySec] с — WorkManager виконає роботу навіть після закриття застосунку. */
    fun schedule(category: AlertCategory, title: String, text: String, delaySec: Long, interactive: Boolean = false, uniqueName: String? = null) {
        val req = OneTimeWorkRequestBuilder<NotifyWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_CATEGORY to category.name, KEY_TITLE to title, KEY_TEXT to text, KEY_INTERACTIVE to interactive))
            .addTag(TAG_SCHEDULED).addTag(category.name)
            .build()
        val wm = WorkManager.getInstance(context)
        if (uniqueName != null) wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, req) else wm.enqueue(req)
    }

    fun cancelScheduled(category: AlertCategory? = null) =
        WorkManager.getInstance(context).cancelAllWorkByTag(category?.name ?: TAG_SCHEDULED)

    /** Створює подію, кладе її у сховище і показує сповіщення. */
    fun raise(category: AlertCategory, title: String, text: String, interactive: Boolean = false, value: Float? = null): Alert {
        val a = Alert(AlertStore.newId(), category, title, text, System.currentTimeMillis(), value = value)
        AlertStore.add(a)
        show(a, interactive)
        return a
    }

    companion object {
        const val EXTRA_ALERT_ID = "alert_id"
        const val ACTION_CONFIRM = "ua.kpi.windfarm5.CONFIRM"
        const val ACTION_SNOOZE = "ua.kpi.windfarm5.SNOOZE"
        const val GROUP = "windfarm_alerts"
        const val TAG_SCHEDULED = "scheduled"
        const val KEY_CATEGORY = "category"
        const val KEY_TITLE = "title"
        const val KEY_TEXT = "text"
        const val KEY_INTERACTIVE = "interactive"
        const val KEY_ALERT_ID = "alert_id"
        const val SNOOZE_SEC = 60L

        /** Подія, відкрита з сповіщення кнопкою «Деталі» (екран заняття 18 показує її). */
        val openedAlert = MutableStateFlow<Int?>(null)
    }
}

/** Фонова робота: створює подію та показує сповіщення в запланований час. */
class NotifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val n = Notifier(applicationContext)
        val snoozedId = inputData.getInt(Notifier.KEY_ALERT_ID, -1)
        if (snoozedId >= 0) {
            // Повернення відкладеної події
            AlertStore.wake(System.currentTimeMillis()).forEach { n.show(it, interactive = true) }
            return Result.success()
        }
        val category = runCatching { AlertCategory.valueOf(inputData.getString(Notifier.KEY_CATEGORY) ?: "") }.getOrNull() ?: return Result.failure()
        n.raise(category, inputData.getString(Notifier.KEY_TITLE).orEmpty(), inputData.getString(Notifier.KEY_TEXT).orEmpty(),
            interactive = inputData.getBoolean(Notifier.KEY_INTERACTIVE, false))
        return Result.success()
    }
}

/**
 * Обробник дій зі сповіщення. Змінює стан у AlertStore — екран застосунку,
 * підписаний на цей StateFlow, оновлюється автоматично.
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Notifier.EXTRA_ALERT_ID, -1)
        if (id < 0) return
        val n = Notifier(context)
        when (intent.action) {
            Notifier.ACTION_CONFIRM -> { AlertStore.confirm(id); n.cancel(id) }
            Notifier.ACTION_SNOOZE -> {
                AlertStore.snooze(id, System.currentTimeMillis() + Notifier.SNOOZE_SEC * 1000)
                n.cancel(id)
                WorkManager.getInstance(context).enqueue(
                    OneTimeWorkRequestBuilder<NotifyWorker>()
                        .setInitialDelay(Notifier.SNOOZE_SEC, TimeUnit.SECONDS)
                        .setInputData(workDataOf(Notifier.KEY_ALERT_ID to id))
                        .build()
                )
            }
        }
    }
}
