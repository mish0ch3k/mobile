package ua.kpi.windfarm.rgr

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ua.kpi.windfarm.rgr.data.AlertRepository
import ua.kpi.windfarm.rgr.data.ForecastRepository
import ua.kpi.windfarm.rgr.data.MeasurementRepository
import ua.kpi.windfarm.rgr.data.StationRepository
import ua.kpi.windfarm.rgr.data.local.WindDatabase
import ua.kpi.windfarm.rgr.data.remote.ApiFactory
import ua.kpi.windfarm.rgr.notify.AlertNotifier
import ua.kpi.windfarm.rgr.notify.ForecastCheckWorker
import ua.kpi.windfarm.rgr.telemetry.TelemetryCollector

/**
 * Контейнер залежностей (ручний DI): єдині екземпляри БД, API і репозиторіїв на весь застосунок.
 * ViewModel отримують репозиторії через конструктор, тож у тестах їх легко підмінити.
 */
class AppContainer(context: Context) {
    private val db = WindDatabase.get(context)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val stations = StationRepository(db.stations())
    val measurements = MeasurementRepository(db.measurements())
    val forecast = ForecastRepository(ApiFactory.openMeteo(), db.forecast())
    val alerts = AlertRepository(db.alerts())
    val notifier = AlertNotifier(context)
    val telemetry = TelemetryCollector(appScope, stations, measurements, forecast, alerts, notifier)
}

class WindApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.telemetry.start()
        ForecastCheckWorker.schedule(this)
    }
}
