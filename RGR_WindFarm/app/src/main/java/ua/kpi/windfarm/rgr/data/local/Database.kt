package ua.kpi.windfarm.rgr.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ============================ ENTITY ============================

/** Вітрова станція: координати (для прогнозу), кількість і параметри турбін. */
@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val turbines: Int,
    val ratedKw: Double = 2000.0,
    val rotorRadiusM: Double = 45.0,
    val cp: Double = 0.42,
    val availability: Double = 0.97   // частка справних турбін
)

/** Вимір метеостанції станції (анемометр на гондолі + термометр/барометр) і фактична потужність. */
@Entity(
    tableName = "measurements",
    foreignKeys = [ForeignKey(entity = StationEntity::class, parentColumns = ["id"], childColumns = ["stationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("stationId", "timestamp")]
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val timestamp: Long,
    val windMs: Double,
    val windDirDeg: Double,
    val temperatureC: Double,
    val pressureHpa: Double,
    val humidity: Double,
    val powerMw: Double,
    val intervalH: Double,            // тривалість, яку представляє вимір (для обліку енергії P·Δt)
    val source: String = "telemetry"
)

/** Кеш погодинного прогнозу (ключ — станція + година). */
@Entity(tableName = "forecast", primaryKeys = ["stationId", "timeMs"])
data class ForecastEntity(
    val stationId: Long,
    val timeMs: Long,
    val windMs: Double,        // на висоті 100 м
    val gustMs: Double,
    val temperatureC: Double,
    val pressureHpa: Double,
    val fetchedAt: Long
)

/** Подія тривоги з ознакою підтвердження оператором. */
@Entity(tableName = "alerts", indices = [Index("stationId"), Index("acknowledged")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: Long,
    val type: String,
    val message: String,
    val value: Double,
    val timestamp: Long,
    val acknowledged: Boolean = false
)

/** Агрегат для статистики (результат SQL-запиту з GROUP BY). */
data class StationDayStat(val day: String, val avgWind: Double, val maxWind: Double, val energyMwh: Double, val samples: Int)

// ============================ DAO ============================

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY name")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun get(id: Long): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: List<StationEntity>)

    @Update
    suspend fun update(s: StationEntity)
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(m: MeasurementEntity): Long

    @Query("SELECT * FROM measurements WHERE stationId = :stationId ORDER BY timestamp DESC LIMIT :limit")
    fun observeLatest(stationId: Long, limit: Int): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE stationId = :stationId AND timestamp >= :from ORDER BY timestamp")
    fun observeSince(stationId: Long, from: Long): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements WHERE stationId = :stationId AND windMs >= :minWind AND timestamp >= :from ORDER BY timestamp DESC")
    fun observeFiltered(stationId: Long, minWind: Double, from: Long): Flow<List<MeasurementEntity>>

    /**
     * Добова статистика засобами SQL: середній і максимальний вітер, енергія = Σ P·Δt
     * (Δt зберігається у кожному вимірі, тож погодинна історія й 5-секундна телеметрія рахуються коректно).
     */
    @Query("""SELECT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS day,
              SUM(windMs * intervalH) / SUM(intervalH) AS avgWind, MAX(windMs) AS maxWind, SUM(powerMw * intervalH) AS energyMwh, COUNT(*) AS samples
              FROM measurements WHERE stationId = :stationId AND timestamp >= :from GROUP BY day ORDER BY day""")
    fun observeDaily(stationId: Long, from: Long): Flow<List<StationDayStat>>

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("DELETE FROM measurements WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int

    /** Детальна телеметрія (раз на 10 с) потрібна лише за останню добу — далі достатньо добової статистики. */
    @Query("DELETE FROM measurements WHERE source = 'telemetry' AND timestamp < :before")
    suspend fun deleteTelemetryOlderThan(before: Long): Int

    @Insert
    suspend fun insertAll(m: List<MeasurementEntity>)
}

@Dao
abstract class ForecastDao {
    @Query("SELECT * FROM forecast WHERE stationId = :stationId AND timeMs >= :from ORDER BY timeMs")
    abstract fun observe(stationId: Long, from: Long): Flow<List<ForecastEntity>>

    @Query("SELECT MAX(fetchedAt) FROM forecast WHERE stationId = :stationId")
    abstract suspend fun lastFetch(stationId: Long): Long?

    @Query("DELETE FROM forecast WHERE stationId = :stationId")
    abstract suspend fun clear(stationId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(rows: List<ForecastEntity>)

    /** Заміна прогнозу станції однією транзакцією — UI не побачить напівоновлених даних. */
    @Transaction
    open suspend fun replace(stationId: Long, rows: List<ForecastEntity>) { clear(stationId); insert(rows) }
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY acknowledged, timestamp DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE acknowledged = 0")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM alerts WHERE stationId = :stationId AND type = :type AND timestamp >= :since LIMIT 1")
    suspend fun findRecent(stationId: Long, type: String, since: Long): AlertEntity?

    @Insert
    suspend fun insert(a: AlertEntity): Long

    @Query("UPDATE alerts SET acknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    @Query("UPDATE alerts SET acknowledged = 1")
    suspend fun acknowledgeAll()

    @Query("DELETE FROM alerts WHERE acknowledged = 1")
    suspend fun deleteAcknowledged()
}

// ============================ DATABASE ============================

@Database(entities = [StationEntity::class, MeasurementEntity::class, ForecastEntity::class, AlertEntity::class], version = 1, exportSchema = true)
abstract class WindDatabase : RoomDatabase() {
    abstract fun stations(): StationDao
    abstract fun measurements(): MeasurementDao
    abstract fun forecast(): ForecastDao
    abstract fun alerts(): AlertDao

    companion object {
        /** Станції за замовчуванням: ВЕС півдня України; координати наближені, параметри турбін умовні. */
        val DEFAULT_STATIONS = listOf(
            StationEntity(1, "Приазовська ВЕС", 46.73, 35.85, 100),
            StationEntity(2, "Тилігульська ВЕС", 46.90, 31.10, 19, ratedKw = 6000.0, rotorRadiusM = 81.0),
            StationEntity(3, "Ботієвська ВЕС", 46.70, 35.40, 65, ratedKw = 3075.0, rotorRadiusM = 56.0),
            StationEntity(4, "Сиваська ВЕС", 46.15, 34.35, 50)
        )

        @Volatile private var instance: WindDatabase? = null

        fun get(context: Context): WindDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WindDatabase::class.java, "windfarm-rgr.db")
                .addCallback(object : Callback() {
                    // Первинне наповнення довідника станцій під час створення БД
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        DEFAULT_STATIONS.forEach { s ->
                            db.execSQL("INSERT INTO stations (id, name, latitude, longitude, turbines, ratedKw, rotorRadiusM, cp, availability) VALUES (?,?,?,?,?,?,?,?,?)",
                                arrayOf<Any>(s.id, s.name, s.latitude, s.longitude, s.turbines, s.ratedKw, s.rotorRadiusM, s.cp, s.availability))
                        }
                    }
                })
                .build().also { instance = it }
        }
    }
}
