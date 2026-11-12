package ua.kpi.windfarm5.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Доступ до сенсорів телефону через SensorManager.
 * Кожен сенсор подається як Flow: підписка на SensorEventListener реєструється,
 * коли потік починають збирати, і знімається, коли збирач зупиняється (awaitClose),
 * тож сенсори не працюють і не витрачають батарею, коли екран не відкритий.
 */
class SensorRepository(context: Context) {
    private val sm: SensorManager = context.applicationContext.getSystemService(SensorManager::class.java)

    fun sensor(kind: SensorKind): Sensor? = sm.getDefaultSensor(kind.androidType)
    fun isAvailable(kind: SensorKind) = sensor(kind) != null
    fun available(): List<SensorKind> = SensorKind.entries.filter(::isAvailable)

    /** Назва апаратного сенсора і його максимальний діапазон — для довідки в UI. */
    fun describe(kind: SensorKind): String? = sensor(kind)?.let { "${it.name} · ${it.vendor} · до ${"%.0f".format(it.maximumRange)} ${kind.unit}" }

    /**
     * Потік показів. Некоректні значення (NaN, поза фізичним діапазоном) відкидаються.
     * conflate(): якщо UI не встигає, береться лише найсвіжіший показ — без накопичення черги.
     */
    fun stream(kind: SensorKind, rate: Int = SensorManager.SENSOR_DELAY_UI): Flow<SensorSample> = callbackFlow {
        val sensor = sm.getDefaultSensor(kind.androidType)
        if (sensor == null) {
            close(IllegalStateException("Сенсор «${kind.title}» відсутній на цьому пристрої"))
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val values = e.values.copyOf() // масив перевикористовується системою — копіюємо
                if (kind.isValid(values)) trySend(SensorSample(kind, values, System.currentTimeMillis(), e.accuracy))
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, rate)
        awaitClose { sm.unregisterListener(listener) }
    }.conflate()
}

/** Глобальний доступ до репозиторію сенсорів (ініціалізується в MainActivity). */
object Sensors {
    lateinit var repo: SensorRepository
        private set

    fun init(context: Context) { if (!::repo.isInitialized) repo = SensorRepository(context) }
}
