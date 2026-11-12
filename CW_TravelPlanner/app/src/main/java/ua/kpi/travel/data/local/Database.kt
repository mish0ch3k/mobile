package ua.kpi.travel.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import ua.kpi.travel.domain.Activity
import ua.kpi.travel.domain.ActivityCategory
import ua.kpi.travel.domain.Expense
import ua.kpi.travel.domain.ExpenseCategory
import ua.kpi.travel.domain.PackingCategory
import ua.kpi.travel.domain.PackingItem
import ua.kpi.travel.domain.Trip
import ua.kpi.travel.domain.TripType

/** Кешовані курси валют: працюють офлайн. */
@androidx.room.Entity(tableName = "rates")
data class RateEntity(@androidx.room.PrimaryKey val code: String, val perUah: Double, val updatedAt: Long)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY startDay")
    fun observeAll(): Flow<List<Trip>>

    @Query("SELECT * FROM trips WHERE id = :id")
    fun observe(id: Long): Flow<Trip?>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): Trip?

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Delete
    suspend fun delete(trip: Trip)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE tripId = :tripId ORDER BY day, startMinute")
    fun observe(tripId: Long): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): Activity?

    /** Активності, що випали за межі нових дат подорожі (після редагування дат). */
    @Query("DELETE FROM activities WHERE tripId = :tripId AND (day < :startDay OR day > :endDay)")
    suspend fun deleteOutside(tripId: Long, startDay: Long, endDay: Long): Int

    @Insert
    suspend fun insert(a: Activity): Long

    @Update
    suspend fun update(a: Activity)

    @Delete
    suspend fun delete(a: Activity)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY day DESC, id DESC")
    fun observe(tripId: Long): Flow<List<Expense>>

    @Insert
    suspend fun insert(e: Expense): Long

    @Delete
    suspend fun delete(e: Expense)
}

@Dao
interface PackingDao {
    @Query("SELECT * FROM packing WHERE tripId = :tripId ORDER BY packed, category, name")
    fun observe(tripId: Long): Flow<List<PackingItem>>

    @Query("SELECT * FROM packing WHERE tripId = :tripId")
    suspend fun list(tripId: Long): List<PackingItem>

    @Insert
    suspend fun insertAll(items: List<PackingItem>)

    @Update
    suspend fun update(item: PackingItem)

    @Delete
    suspend fun delete(item: PackingItem)
}

@Dao
interface RateDao {
    @Query("SELECT * FROM rates")
    suspend fun all(): List<RateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rates: List<RateEntity>)
}

class Converters {
    @TypeConverter fun tripType(v: TripType): String = v.name
    @TypeConverter fun tripType(v: String): TripType = runCatching { TripType.valueOf(v) }.getOrDefault(TripType.CITY)
    @TypeConverter fun actCat(v: ActivityCategory): String = v.name
    @TypeConverter fun actCat(v: String): ActivityCategory = runCatching { ActivityCategory.valueOf(v) }.getOrDefault(ActivityCategory.OTHER)
    @TypeConverter fun expCat(v: ExpenseCategory): String = v.name
    @TypeConverter fun expCat(v: String): ExpenseCategory = runCatching { ExpenseCategory.valueOf(v) }.getOrDefault(ExpenseCategory.OTHER)
    @TypeConverter fun packCat(v: PackingCategory): String = v.name
    @TypeConverter fun packCat(v: String): PackingCategory = runCatching { PackingCategory.valueOf(v) }.getOrDefault(PackingCategory.OTHER)
}

@Database(entities = [Trip::class, Activity::class, Expense::class, PackingItem::class, RateEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class TravelDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun activities(): ActivityDao
    abstract fun expenses(): ExpenseDao
    abstract fun packing(): PackingDao
    abstract fun rates(): RateDao

    companion object {
        @Volatile private var instance: TravelDatabase? = null
        fun get(context: Context): TravelDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TravelDatabase::class.java, "travel.db")
                .build().also { instance = it }
        }
    }
}
