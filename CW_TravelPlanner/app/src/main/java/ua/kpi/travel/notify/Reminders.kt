package ua.kpi.travel.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import ua.kpi.travel.MainActivity
import ua.kpi.travel.R
import ua.kpi.travel.TravelApp
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityValidator
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Планування нагадувань про активності маршруту через WorkManager (переживає перезапуск пристрою). */
object ReminderScheduler {
    const val CHANNEL = "reminders"
    const val LEAD_MINUTES = 60L
    const val EXTRA_TRIP = "trip_id"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Нагадування про маршрут", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "За годину до початку запланованої активності"
            })
    }

    /** Затримка до нагадування в мс; null, якщо момент уже минув. Чиста функція — тестується. */
    fun delayMillis(a: Activity, now: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val start = java.time.LocalDate.ofEpochDay(a.day).atStartOfDay().plusMinutes(a.startMinute.toLong())
        val fireAt = start.minusMinutes(LEAD_MINUTES)
        val d = fireAt.atZone(zone).toInstant().toEpochMilli() - now.atZone(zone).toInstant().toEpochMilli()
        return if (d > 0) d else null
    }

    private fun name(id: Long) = "activity-$id"

    fun sync(context: Context, a: Activity) {
        val wm = WorkManager.getInstance(context)
        if (!a.remind || a.done) { wm.cancelUniqueWork(name(a.id)); return }
        val delay = delayMillis(a, LocalDateTime.now()) ?: run { wm.cancelUniqueWork(name(a.id)); return }
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("activity" to a.id))
            .build()
        wm.enqueueUniqueWork(name(a.id), ExistingWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context, activityId: Long) = WorkManager.getInstance(context).cancelUniqueWork(name(activityId))

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission") // перевіряється в canPost()
    fun show(context: Context, a: Activity, tripTitle: String) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, a.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TRIP, a.tripId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "${ActivityValidator.formatTime(a.startMinute)} — ${a.title}" + if (a.place.isNotBlank()) " (${a.place})" else ""
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle("$tripTitle: за годину")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(a.id.toInt(), n)
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("activity", -1)
        val repo = (applicationContext as TravelApp).container.trips
        val a = repo.activity(id) ?: return Result.success()          // активність видалено
        if (a.done || !a.remind) return Result.success()
        val trip = repo.trip(a.tripId) ?: return Result.success()
        ReminderScheduler.show(applicationContext, a, trip.title)
        return Result.success()
    }
}
