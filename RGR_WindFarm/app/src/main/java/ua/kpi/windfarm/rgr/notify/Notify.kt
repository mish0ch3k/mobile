package ua.kpi.windfarm.rgr.notify

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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ua.kpi.windfarm.rgr.MainActivity
import ua.kpi.windfarm.rgr.R
import ua.kpi.windfarm.rgr.WindApp
import ua.kpi.windfarm.rgr.data.local.AlertEntity
import ua.kpi.windfarm.rgr.data.spec
import ua.kpi.windfarm.rgr.domain.AlertRules
import ua.kpi.windfarm.rgr.domain.AlertType
import java.util.concurrent.TimeUnit

/** Сповіщення про тривоги: два канали — критичні (шторм, обмерзання) і інформаційні. */
class AlertNotifier(private val context: Context) {
    companion object {
        const val CRITICAL = "critical"
        const val INFO = "info"
        const val EXTRA_ALERTS = "open_alerts"
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CRITICAL, "Критичні тривоги", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Шторм, сильні пориви, обмерзання, несправність датчиків"
        })
        nm.createNotificationChannel(NotificationChannel(INFO, "Інформація станції", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Знижений виробіток, вікна для ТО"
        })
    }

    fun canPost(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    @SuppressLint("MissingPermission") // дозвіл перевіряється в canPost()
    fun show(a: AlertEntity, station: String) {
        if (!canPost()) return
        val type = runCatching { AlertType.valueOf(a.type) }.getOrDefault(AlertType.SENSOR)
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra(EXTRA_ALERTS, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, if (type.critical) CRITICAL else INFO)
            .setSmallIcon(R.drawable.ic_stat_wind)
            .setContentTitle("$station: ${type.title}")
            .setContentText(a.message)
            .setPriority(if (type.critical) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(a.id.toInt(), n)
    }
}

/**
 * Фонова робота (раз на годину, навіть коли застосунок закрито): оновлює прогноз
 * для всіх станцій і створює тривоги, якщо у прогнозі шторм, пориви чи тривалий штиль.
 */
class ForecastCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as WindApp).container
        var failed = false
        c.stations.stations.first().forEach { s ->
            if (c.forecast.refresh(s) is ua.kpi.windfarm.rgr.data.RefreshResult.Failed) failed = true
            val hours = c.forecast.observe(s.id).first()
            AlertRules.checkForecast(hours, s.spec(), c.telemetry.thresholds.value).forEach { e ->
                c.alerts.raise(s.id, e, dedupMs = 6 * 3600_000L)?.let { c.notifier.show(it, s.name) }
            }
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "forecast-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ForecastCheckWorker>(1, TimeUnit.HOURS)
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build())
    }
}
