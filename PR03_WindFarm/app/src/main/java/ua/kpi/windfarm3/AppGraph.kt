package ua.kpi.windfarm3

import android.content.Context
import ua.kpi.windfarm3.data.FleetRepository
import ua.kpi.windfarm3.data.ForecastCacheDataSource
import ua.kpi.windfarm3.data.GeoRemoteDataSource
import ua.kpi.windfarm3.data.StationLocalDataSource
import ua.kpi.windfarm3.data.StationRepository
import ua.kpi.windfarm3.data.WindRemoteDataSource
import ua.kpi.windfarm3.data.local.AppDatabase
import ua.kpi.windfarm3.data.remote.ApiClient
import ua.kpi.windfarm3.data.remote.ConnectivityInterceptor
import ua.kpi.windfarm3.data.remote.GeocodingApi
import ua.kpi.windfarm3.data.remote.NetworkMonitor
import ua.kpi.windfarm3.data.remote.OpenMeteoApi
import ua.kpi.windfarm3.errors.FileErrorLog
import java.io.File

/**
 * Простий контейнер залежностей (service locator): один екземпляр БД, Retrofit і
 * репозиторіїв на весь застосунок. ViewModel отримують готові репозиторії звідси.
 */
object AppGraph {
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    val db by lazy { AppDatabase.get(app) }
    val network by lazy { NetworkMonitor(app) }

    val openMeteo: OpenMeteoApi by lazy { ApiClient.create(OpenMeteoApi.BASE_URL, ConnectivityInterceptor(network)) }
    val geocoding: GeocodingApi by lazy { ApiClient.create(GeocodingApi.BASE_URL, ConnectivityInterceptor(network)) }

    val stationLocal by lazy { StationLocalDataSource(db.stations()) }
    val windRemote by lazy { WindRemoteDataSource(openMeteo) }
    val geoRemote by lazy { GeoRemoteDataSource(geocoding) }
    val forecastCache by lazy { ForecastCacheDataSource(db.cache()) }

    val stationRepository by lazy { StationRepository(stationLocal, windRemote, forecastCache) }
    val fleetRepository by lazy { FleetRepository(stationRepository, stationLocal) }

    /** Журнал помилок мережі у файлі застосунку. */
    val errorLog by lazy { FileErrorLog(File(app.filesDir, "network_errors.log")) }
}
