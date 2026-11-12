package ua.kpi.windfarm.rgr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ua.kpi.windfarm.rgr.data.AlertRepository
import ua.kpi.windfarm.rgr.data.ForecastRepository
import ua.kpi.windfarm.rgr.data.MeasurementRepository
import ua.kpi.windfarm.rgr.data.RefreshResult
import ua.kpi.windfarm.rgr.data.StationRepository
import ua.kpi.windfarm.rgr.data.local.AlertDao
import ua.kpi.windfarm.rgr.data.local.AlertEntity
import ua.kpi.windfarm.rgr.data.local.ForecastDao
import ua.kpi.windfarm.rgr.data.local.ForecastEntity
import ua.kpi.windfarm.rgr.data.local.MeasurementDao
import ua.kpi.windfarm.rgr.data.local.MeasurementEntity
import ua.kpi.windfarm.rgr.data.local.StationDao
import ua.kpi.windfarm.rgr.data.local.StationDayStat
import ua.kpi.windfarm.rgr.data.local.StationEntity
import ua.kpi.windfarm.rgr.data.remote.ForecastResponse
import ua.kpi.windfarm.rgr.data.remote.HourlyDto
import ua.kpi.windfarm.rgr.data.remote.NetworkError
import ua.kpi.windfarm.rgr.data.remote.OpenMeteoApi
import ua.kpi.windfarm.rgr.domain.AlertEvent
import ua.kpi.windfarm.rgr.domain.AlertType
import ua.kpi.windfarm.rgr.domain.TurbineState
import ua.kpi.windfarm.rgr.ui.AnalyticsViewModel
import ua.kpi.windfarm.rgr.ui.CalculatorViewModel
import ua.kpi.windfarm.rgr.ui.DashboardViewModel
import ua.kpi.windfarm.rgr.ui.Selection

// ============================ ПІДРОБКИ (fakes) ============================
// Реалізують ті самі інтерфейси DAO, що й Room, але зберігають дані в пам'яті.

class FakeStationDao(list: List<StationEntity>) : StationDao {
    val data = MutableStateFlow(list)
    override fun observeAll(): Flow<List<StationEntity>> = data
    override suspend fun get(id: Long) = data.value.firstOrNull { it.id == id }
    override suspend fun upsert(s: List<StationEntity>) { data.value = (data.value.filterNot { o -> s.any { it.id == o.id } } + s).sortedBy { it.name } }
    override suspend fun update(s: StationEntity) = upsert(listOf(s))
}

class FakeMeasurementDao : MeasurementDao {
    val data = MutableStateFlow<List<MeasurementEntity>>(emptyList())
    private var nextId = 1L
    override suspend fun insert(m: MeasurementEntity): Long { val id = nextId++; data.value = data.value + m.copy(id = id); return id }
    override suspend fun insertAll(m: List<MeasurementEntity>) { m.forEach { insert(it) } }
    override fun observeLatest(stationId: Long, limit: Int) = data.map { l -> l.filter { it.stationId == stationId }.sortedByDescending { it.timestamp }.take(limit) }
    override fun observeSince(stationId: Long, from: Long) = data.map { l -> l.filter { it.stationId == stationId && it.timestamp >= from }.sortedBy { it.timestamp } }
    override fun observeFiltered(stationId: Long, minWind: Double, from: Long) =
        data.map { l -> l.filter { it.stationId == stationId && it.windMs >= minWind && it.timestamp >= from }.sortedByDescending { it.timestamp } }
    override fun observeDaily(stationId: Long, from: Long) = data.map { l ->
        l.filter { it.stationId == stationId && it.timestamp >= from }.groupBy { "2026-11-%02d".format(it.timestamp / 86_400_000 + 1) }.map { (d, r) ->
            StationDayStat(d, r.sumOf { it.windMs * it.intervalH } / r.sumOf { it.intervalH }, r.maxOf { it.windMs }, r.sumOf { it.powerMw * it.intervalH }, r.size)
        }
    }
    override suspend fun count() = data.value.size
    override suspend fun deleteOlderThan(before: Long): Int { val n = data.value.count { it.timestamp < before }; data.value = data.value.filter { it.timestamp >= before }; return n }
    override suspend fun deleteTelemetryOlderThan(before: Long): Int {
        val n = data.value.count { it.source == "telemetry" && it.timestamp < before }
        data.value = data.value.filterNot { it.source == "telemetry" && it.timestamp < before }; return n
    }
}

class FakeForecastDao : ForecastDao() {
    val data = MutableStateFlow<List<ForecastEntity>>(emptyList())
    override fun observe(stationId: Long, from: Long) = data.map { l -> l.filter { it.stationId == stationId && it.timeMs >= from } }
    override suspend fun lastFetch(stationId: Long) = data.value.filter { it.stationId == stationId }.maxOfOrNull { it.fetchedAt }
    override suspend fun clear(stationId: Long) { data.value = data.value.filterNot { it.stationId == stationId } }
    override suspend fun insert(rows: List<ForecastEntity>) { data.value = data.value + rows }
}

class FakeAlertDao : AlertDao {
    val data = MutableStateFlow<List<AlertEntity>>(emptyList())
    private var nextId = 1L
    override fun observeAll() = data
    override fun observeActiveCount() = data.map { l -> l.count { !it.acknowledged } }
    override suspend fun findRecent(stationId: Long, type: String, since: Long) = data.value.firstOrNull { it.stationId == stationId && it.type == type && it.timestamp >= since }
    override suspend fun insert(a: AlertEntity): Long { val id = nextId++; data.value = data.value + a.copy(id = id); return id }
    override suspend fun acknowledge(id: Long) { data.value = data.value.map { if (it.id == id) it.copy(acknowledged = true) else it } }
    override suspend fun acknowledgeAll() { data.value = data.value.map { it.copy(acknowledged = true) } }
    override suspend fun deleteAcknowledged() { data.value = data.value.filterNot { it.acknowledged } }
}

/** Підроблений API: повертає заданий прогноз або кидає заданий виняток; рахує запити. */
class FakeApi(var response: ForecastResponse? = null, var error: Exception? = null) : OpenMeteoApi {
    var calls = 0
    override suspend fun forecast(latitude: Double, longitude: Double, hourly: String, current: String, days: Int, unit: String, timeFormat: String, timezone: String): ForecastResponse {
        calls++
        error?.let { throw it }
        return response!!
    }
}

private val station = StationEntity(1, "Тестова ВЕС", 46.0, 35.0, turbines = 10, availability = 1.0)

private fun response(winds: List<Double?>) = ForecastResponse(46.0, 35.0,
    HourlyDto(time = winds.indices.map { 1_800_000_000L + it * 3600L }, wind100m = winds, gusts = null,
        temperature = winds.map { 15.0 }, pressure = winds.map { 1013.25 }, humidity = null), null)

// ============================ РЕПОЗИТОРІЇ ============================

class ForecastRepositoryTest {
    private var now = 1_800_000_000_000L
    private val dao = FakeForecastDao()
    private val api = FakeApi(response(listOf(5.0, 9.0, 13.0)))
    private val repo = ForecastRepository(api, dao, ttlMs = 3_600_000, clock = { now })

    @Test fun refreshStoresAndRespectsTtl() = runTest {
        assertEquals(RefreshResult.Updated(3), repo.refresh(station))
        assertEquals(3, dao.data.value.size)
        now += 10 * 60_000                                        // через 10 хв — кеш свіжий, запиту немає
        assertEquals(RefreshResult.Fresh, repo.refresh(station))
        assertEquals(1, api.calls)
        assertTrue(repo.refresh(station, force = true) is RefreshResult.Updated)   // примусово — новий запит
        assertEquals(3, dao.data.value.size)                     // старий прогноз замінено, а не продубльовано
    }

    @Test fun offlineKeepsCacheAndReportsError() = runTest {
        repo.refresh(station)
        api.error = java.io.IOException("no route")
        now += 2 * 3_600_000                                       // кеш застарів
        val r = repo.refresh(station) as RefreshResult.Failed
        assertTrue(r.error is NetworkError.NoConnection)
        assertTrue(r.hasCache)
        assertEquals(3, dao.data.value.size)                     // дані не втрачено
    }

    @Test fun parseSkipsNullsAndImpossibleValues() {
        val rows = ForecastRepository.parse(1, response(listOf(4.0, null, 80.0, 7.0)), 0)
        assertEquals(listOf(4.0, 7.0), rows.map { it.windMs })
        assertEquals(1_800_000_000_000L, rows.first().timeMs)    // секунди → мілісекунди
        assertEquals(4.0 * 1.4, rows.first().gustMs, 1e-9)       // поривів немає — оцінка ×1,4
    }

    @Test(expected = NetworkError.BadData::class)
    fun parseRejectsMismatchedArrays() {
        val bad = response(listOf(4.0, 5.0)).let { it.copy(hourly = it.hourly!!.copy(temperature = listOf(15.0))) }
        ForecastRepository.parse(1, bad, 0)
    }

    @Test fun observeConvertsToDomain() = runTest {
        repo.refresh(station)
        val h = repo.observe(1).first()
        assertEquals(listOf(5.0, 9.0, 13.0), h.map { it.windMs })
    }
}

class AlertRepositoryTest {
    @Test fun deduplicatesWithinWindow() = runTest {
        val dao = FakeAlertDao()
        val repo = AlertRepository(dao)
        val e = AlertEvent(AlertType.STORM, "26 м/с", 26.0, 0)
        assertTrue(repo.raise(1, e, dedupMs = 60_000, now = 1_000_000) != null)
        assertNull(repo.raise(1, e, dedupMs = 60_000, now = 1_030_000))        // повтор через 30 с — ігнорується
        assertTrue(repo.raise(2, e, dedupMs = 60_000, now = 1_030_000) != null) // інша станція — нова тривога
        assertTrue(repo.raise(1, e, dedupMs = 60_000, now = 1_100_000) != null) // минуло вікно
        assertEquals(3, repo.activeCount.first())
        repo.acknowledgeAll(); repo.clearAcknowledged()
        assertTrue(dao.data.value.isEmpty())
    }
}

class MeasurementRepositoryTest {
    @Test fun cleanupKeepsHistoryLongerThanTelemetry() = runTest {
        val dao = FakeMeasurementDao()
        val repo = MeasurementRepository(dao)
        val day = 86_400_000L
        val now = 100 * day
        fun m(t: Long, src: String) = MeasurementEntity(stationId = 1, timestamp = t, windMs = 8.0, windDirDeg = 0.0, temperatureC = 10.0,
            pressureHpa = 1013.0, humidity = 70.0, powerMw = 5.0, intervalH = 1.0, source = src)
        repo.addAll(listOf(m(now - 3 * day, "telemetry"), m(now - 3 * day, "history"), m(now - 40 * day, "history"), m(now - day / 2, "telemetry")))
        assertEquals(2, repo.cleanup(now))
        assertEquals(listOf("history", "telemetry"), dao.data.value.map { it.source })
    }
}

// ============================ VIEWMODEL ============================

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()); Selection.stationId.value = 1 }
    @After fun tearDown() = Dispatchers.resetMain()

    private fun m(t: Long, wind: Double, power: Double) = MeasurementEntity(stationId = 1, timestamp = t, windMs = wind, windDirDeg = 200.0,
        temperatureC = 15.0, pressureHpa = 1013.25, humidity = 70.0, powerMw = power, intervalH = 1.0)

    @Test fun dashboardFollowsNewMeasurements() = runTest {
        val mDao = FakeMeasurementDao()
        val vm = DashboardViewModel(StationRepository(FakeStationDao(listOf(station))), MeasurementRepository(mDao))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }  // активна підписка (WhileSubscribed)
        mDao.insert(m(1, 8.0, 8.0)); mDao.insert(m(2, 14.0, 20.0))
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(14.0, s.latest!!.windMs, 0.0)
        assertEquals(listOf(8.0, 14.0), s.history)                  // старіші — першими
        assertEquals(TurbineState.RATED, s.state)
        assertEquals(100.0, s.loadPercent, 1e-9)                   // 20 МВт із 20 МВт
        job.cancel()
    }

    @Test fun dashboardSwitchesStation() = runTest {
        val second = station.copy(id = 2, name = "Друга")
        val mDao = FakeMeasurementDao()
        mDao.insert(m(1, 5.0, 1.0)); mDao.insert(m(2, 6.0, 2.0).copy(stationId = 2))
        val vm = DashboardViewModel(StationRepository(FakeStationDao(listOf(station, second))), MeasurementRepository(mDao))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.select(2); advanceUntilIdle()
        assertEquals("Друга", vm.state.value.station!!.name)
        assertEquals(6.0, vm.state.value.latest!!.windMs, 0.0)
        job.cancel()
    }

    @Test fun calculatorReactsToInput() = runTest {
        val vm = CalculatorViewModel()
        vm.update { it.copy(windMs = 8.0, measuredAtM = 100.0, turbines = 10) }
        advanceUntilIdle()
        assertEquals(8.379, vm.result.value.stationMw, 0.01)
        vm.update { it.copy(cp = 0.65) }                            // > межі Бетца
        advanceUntilIdle()
        assertTrue(vm.result.value.error!!.contains("Cp"))
    }

    @Test fun analyticsAggregates() {
        val rows = listOf(m(1, 8.0, 8.0), m(2, 15.0, 20.0))
        val daily = listOf(StationDayStat("2026-11-10", 11.5, 15.0, 28.0, 2))
        val ui = AnalyticsViewModel.analyze(station, daily, rows, days = 1)
        assertEquals(28.0, ui.energyMwh, 0.0)
        assertEquals(28.0 / (20.0 * 24), ui.capacityFactor, 1e-9)
        assertEquals(11.5, ui.summary.mean, 1e-9)
        assertEquals(1, ui.histogram[4]); assertEquals(1, ui.histogram[7])
    }
}
