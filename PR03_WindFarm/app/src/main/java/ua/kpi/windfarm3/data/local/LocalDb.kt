package ua.kpi.windfarm3.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.kpi.windfarm3.domain.Station

/** Таблиця станцій — локальне джерело даних (Room). */
@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
    val turbines: Int
) {
    fun toDomain() = Station(id, name, region, latitude, longitude, turbines)

    companion object {
        fun from(s: Station) = StationEntity(s.id, s.name, s.region, s.latitude, s.longitude, s.turbines)
    }
}

/**
 * Кеш відповідей REST API: ключ — координати, значення — прогноз у JSON.
 * Дає змогу показати останні дані без мережі (офлайн-доступ).
 */
@Entity(tableName = "forecast_cache")
data class ForecastCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long
)

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY name")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE name LIKE '%' || :q || '%' OR region LIKE '%' || :q || '%' ORDER BY name")
    fun search(q: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations ORDER BY name")
    suspend fun getAll(): List<StationEntity>

    @Query("SELECT * FROM stations WHERE id = :id")
    suspend fun getById(id: Long): StationEntity?

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(s: StationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<StationEntity>)

    @Update
    suspend fun update(s: StationEntity)

    @Delete
    suspend fun delete(s: StationEntity)
}

@Dao
interface ForecastCacheDao {
    @Query("SELECT * FROM forecast_cache WHERE `key` = :key")
    suspend fun get(key: String): ForecastCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ForecastCacheEntity)

    @Query("DELETE FROM forecast_cache")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM forecast_cache")
    suspend fun count(): Int
}

@Database(entities = [StationEntity::class, ForecastCacheEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stations(): StationDao
    abstract fun cache(): ForecastCacheDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Singleton з подвійною перевіркою: одна БД на весь застосунок. */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "windfarm3.db")
                .build().also { instance = it }
        }
    }
}
