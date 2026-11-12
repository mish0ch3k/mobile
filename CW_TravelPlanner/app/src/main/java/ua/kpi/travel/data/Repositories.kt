package ua.kpi.travel.data

import kotlinx.coroutines.flow.Flow
import ua.kpi.travel.data.local.ActivityDao
import ua.kpi.travel.data.local.ExpenseDao
import ua.kpi.travel.data.local.PackingDao
import ua.kpi.travel.data.local.RateDao
import ua.kpi.travel.data.local.RateEntity
import ua.kpi.travel.data.local.TripDao
import ua.kpi.travel.data.remote.DailyDto
import ua.kpi.travel.data.remote.GeoResultDto
import ua.kpi.travel.data.remote.GeocodingApi
import ua.kpi.travel.data.remote.NetworkError
import ua.kpi.travel.data.remote.RatesApi
import ua.kpi.travel.data.remote.WeatherApi
import ua.kpi.travel.data.remote.toNetworkError
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.CurrencyConverter
import ua.kpi.travel.domain.DayWeather
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.PackingItem
import ua.kpi.travel.domain.PackingSuggestion
import ua.kpi.travel.domain.PackingTemplates
import ua.kpi.travel.domain.Place
import ua.kpi.travel.domain.Trip
import java.time.LocalDate

/** Перетворення DTO мережі на доменні моделі; чисті функції — покриваються unit-тестами. */
object Mappers {
    fun place(d: GeoResultDto) = Place(d.name, d.country.orEmpty(), d.region.orEmpty(), d.latitude, d.longitude)

    fun daily(d: DailyDto): List<DayWeather> {
        if (d.tMax.size != d.time.size || d.tMin.size != d.time.size || d.code.size != d.time.size)
            throw NetworkError.BadData("різна довжина масивів прогнозу")
        return d.time.indices.mapNotNull { i ->
            val max = d.tMax[i]; val min = d.tMin[i]; val code = d.code[i]
            if (max == null || min == null || code == null) null
            else DayWeather(LocalDate.parse(d.time[i]), min, max, d.precipitation?.getOrNull(i) ?: 0, code)
        }
    }
}

class TripRepository(
    private val trips: TripDao,
    private val activities: ActivityDao,
    private val expenses: ExpenseDao,
    private val packing: PackingDao
) {
    fun observeTrips(): Flow<List<Trip>> = trips.observeAll()
    fun observeTrip(id: Long): Flow<Trip?> = trips.observe(id)
    fun observeActivities(tripId: Long): Flow<List<Activity>> = activities.observe(tripId)
    fun observeExpenses(tripId: Long): Flow<List<Expense>> = expenses.observe(tripId)
    fun observePacking(tripId: Long): Flow<List<PackingItem>> = packing.observe(tripId)

    suspend fun trip(id: Long): Trip? = trips.get(id)
    suspend fun activity(id: Long): Activity? = activities.get(id)

    /** Збереження подорожі; при зміні дат видаляє активності, що опинилися поза новим діапазоном. */
    suspend fun saveTrip(trip: Trip): Long = if (trip.id == 0L) trips.insert(trip) else {
        trips.update(trip)
        activities.deleteOutside(trip.id, trip.startDay, trip.endDay)
        trip.id
    }

    suspend fun deleteTrip(trip: Trip) = trips.delete(trip)   // активності, витрати й речі — каскадно

    suspend fun saveActivity(a: Activity): Long = if (a.id == 0L) activities.insert(a) else { activities.update(a); a.id }
    suspend fun deleteActivity(a: Activity) = activities.delete(a)

    suspend fun addExpense(e: Expense): Long = expenses.insert(e)
    suspend fun deleteExpense(e: Expense) = expenses.delete(e)

    suspend fun addPacking(tripId: Long, suggestions: List<PackingSuggestion>): Int {
        val fresh = PackingTemplates.merge(packing.list(tripId), suggestions)
        packing.insertAll(fresh.map { PackingItem(tripId = tripId, name = it.name, category = it.category, quantity = it.quantity) })
        return fresh.size
    }
    suspend fun updatePacking(item: PackingItem) = packing.update(item)
    suspend fun deletePacking(item: PackingItem) = packing.delete(item)

    suspend fun isEmpty(): Boolean = trips.count() == 0
}

class WeatherRepository(private val geo: GeocodingApi, private val api: WeatherApi) {
    private val cache = HashMap<Pair<Double, Double>, Pair<Long, List<DayWeather>>>()

    suspend fun search(query: String): Result<List<Place>> = runCatching {
        if (query.trim().length < 2) emptyList() else geo.search(query.trim()).results.orEmpty().map(Mappers::place)
    }.recoverCatching { throw it.toNetworkError() }

    /** Прогноз з кешем на 30 хв, щоб не смикати API при кожному відкритті вкладки. */
    suspend fun forecast(lat: Double, lon: Double, now: Long = System.currentTimeMillis()): Result<List<DayWeather>> = runCatching {
        val key = lat to lon
        cache[key]?.takeIf { now - it.first < 30 * 60_000 }?.second ?: run {
            val daily = api.daily(lat, lon).daily ?: throw NetworkError.BadData("немає блоку daily")
            Mappers.daily(daily).also { cache[key] = now to it }
        }
    }.recoverCatching { throw it.toNetworkError() }
}

/** Курси валют: мережа → кеш у Room → вбудовані резервні курси. */
class RatesRepository(private val api: RatesApi, private val dao: RateDao) {
    @Volatile var converter: CurrencyConverter = CurrencyConverter.OFFLINE
        private set
    @Volatile var updatedAt: Long = 0
        private set

    suspend fun load(): CurrencyConverter {
        val cached = dao.all()
        if (cached.isNotEmpty()) {
            converter = CurrencyConverter("UAH", cached.associate { it.code to it.perUah })
            updatedAt = cached.maxOf { it.updatedAt }
        }
        runCatching { api.latest("UAH") }.onSuccess { r ->
            val rates = r.rates
            if (r.result == "success" && !rates.isNullOrEmpty()) {
                val now = System.currentTimeMillis()
                dao.upsert(rates.map { RateEntity(it.key, it.value, now) })
                converter = CurrencyConverter("UAH", rates)
                updatedAt = now
            }
        }
        return converter
    }
}
