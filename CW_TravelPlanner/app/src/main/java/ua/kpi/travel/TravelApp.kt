package ua.kpi.travel

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import ua.kpi.travel.data.RatesRepository
import ua.kpi.travel.data.TripRepository
import ua.kpi.travel.data.WeatherRepository
import ua.kpi.travel.data.local.TravelDatabase
import ua.kpi.travel.data.remote.ApiFactory
import ua.kpi.travel.notify.ReminderScheduler
import java.io.File

/** Ручний DI: єдині екземпляри БД, API та репозиторіїв. */
class AppContainer(context: Context) {
    private val db = TravelDatabase.get(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val trips = TripRepository(db.trips(), db.activities(), db.expenses(), db.packing())
    val weather = WeatherRepository(ApiFactory.geocoding(), ApiFactory.weather())
    val rates = RatesRepository(ApiFactory.rates(), db.rates())
}

class TravelApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.createChannel(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        container.scope.launch {
            DemoData.seedIfEmpty(container.trips)
            container.rates.load()
        }
    }
}
