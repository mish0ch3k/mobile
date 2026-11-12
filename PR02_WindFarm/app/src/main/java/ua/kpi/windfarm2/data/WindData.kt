package ua.kpi.windfarm2.data

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

/** Сутність Room: один вимір швидкості вітру на турбіні. */
@Entity(tableName = "measurements")
data class WindMeasurement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val turbine: String,
    val windSpeed: Double,      // м/с
    val temperature: Double,    // °C
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "local" // local / remote — звідки отримано запис
)

/** DAO — запити до таблиці; Flow робить їх «реактивними». */
@Dao
interface WindDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: WindMeasurement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WindMeasurement>)

    @Update
    suspend fun update(m: WindMeasurement)

    @Delete
    suspend fun delete(m: WindMeasurement)

    @Query("DELETE FROM measurements")
    suspend fun clear()

    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<WindMeasurement>>

    @Query("SELECT * FROM measurements WHERE turbine LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<WindMeasurement>>

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int
}

@Database(entities = [WindMeasurement::class], version = 1, exportSchema = false)
abstract class WindDatabase : RoomDatabase() {
    abstract fun dao(): WindDao

    companion object {
        @Volatile private var instance: WindDatabase? = null

        /** Singleton: одна БД на весь застосунок (потокобезпечна ініціалізація). */
        fun get(context: Context): WindDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WindDatabase::class.java, "wind.db")
                .build().also { instance = it }
        }
    }
}

/** Перевірка введених значень перед записом у БД. */
object MeasurementValidator {
    fun turbine(v: String) = if (v.isBlank()) "Вкажіть турбіну" else null
    fun speed(v: String): String? = range(v, 0.0..60.0, "0…60 м/с")
    fun temperature(v: String): String? = range(v, -50.0..50.0, "−50…50 °C")

    // Не через ?.let { … } ?: — тоді коректне значення (null з let) хибно давало б «Число»
    private fun range(v: String, r: ClosedFloatingPointRange<Double>, msg: String): String? {
        val x = v.trim().replace(',', '.').toDoubleOrNull() ?: return "Число"
        return if (x in r) null else msg
    }
}
