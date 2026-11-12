package ua.kpi.windfarm5.s20

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ua.kpi.windfarm5.domain.TurbineSpec
import ua.kpi.windfarm5.domain.WindPowerCalculator
import ua.kpi.windfarm5.sensors.SensorKind
import ua.kpi.windfarm5.sensors.SensorMath
import ua.kpi.windfarm5.sensors.Sensors
import ua.kpi.windfarm5.sensors.Vec3

/** Оброблений стан руху пристрою для кастомних компонентів. */
data class Motion(
    val gravity: Vec3 = Vec3(0f, 0f, SensorMath.G),
    val pitch: Float = 0f,          // нахил уперед/назад, °
    val roll: Float = 0f,           // нахил вліво/вправо, °
    val inclination: Float = 0f,    // відхилення від вертикалі, °
    val vibration: Float = 0f,      // RMS лінійного прискорення за ~1 с, м/с²
    val gyro: Vec3 = Vec3.ZERO,     // кутова швидкість, рад/с
    val heading: Float? = null      // азимут, ° (null — немає магнітометра)
)

/**
 * Збирає акселерометр, гіроскоп і магнітометр. Акселерометр пропускається через ФНЧ,
 * щоб виділити гравітацію (нахил), а різниця «сирий − гравітація» дає вібрацію.
 * Компоненти отримують уже готовий [Motion] — UI не рахує нічого сам.
 */
class MotionViewModel : ViewModel() {
    private val gravityFilter = SensorMath.LowPass(0.15f)
    private val headingFilter = SensorMath.LowPass(0.2f)
    private val linear = ArrayDeque<Float>()
    /** Гравітація після ФНЧ і RMS вібрації — рахуються лише на нових показах акселерометра. */
    private val accel = MutableStateFlow<Pair<Vec3, Float>?>(null)
    private val gyro = MutableStateFlow(Vec3.ZERO)
    private val magnetic = MutableStateFlow<Vec3?>(null)
    val missing = MutableStateFlow<List<String>>(emptyList())

    init {
        listen(SensorKind.ACCELEROMETER) { a ->
            val grav = gravityFilter.filter(a)
            linear.addLast(SensorMath.linear(a, grav).magnitude)
            while (linear.size > 50) linear.removeFirst() // ~1 с при SENSOR_DELAY_GAME
            accel.value = grav to SensorMath.rms(linear.toList())
        }
        listen(SensorKind.GYROSCOPE) { gyro.value = it }
        listen(SensorKind.MAGNETOMETER) { magnetic.value = headingFilter.filter(it) }
    }

    private fun listen(k: SensorKind, sink: (Vec3) -> Unit) {
        if (!Sensors.repo.isAvailable(k)) { missing.update { it + k.title }; return }
        Sensors.repo.stream(k, rate = android.hardware.SensorManager.SENSOR_DELAY_GAME)
            .onEach { sink(it.vec) }
            .catch { e -> missing.update { l -> l + (e.message ?: k.title) } }
            .launchIn(viewModelScope)
    }

    val motion: StateFlow<Motion> = combine(accel, gyro, magnetic) { a, g, m ->
        if (a == null) return@combine Motion(gyro = g)
        val (grav, rms) = a
        val (pitch, roll) = SensorMath.tilt(grav)
        Motion(grav, pitch, roll, SensorMath.inclination(grav), rms, g, m?.let { SensorMath.heading(grav, it) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), Motion())

    // ----- домашнє: модель турбіни, керована телефоном -----
    val windDirection = MutableStateFlow(240f) // напрям вітру, ° (звідки дме)
    private val spec = TurbineSpec()

    /** «Вітер» = кутова швидкість обертання телефону: покрутіть пристрій — ротор розкрутиться. */
    fun windFrom(m: Motion): Double = (2.0 + m.gyro.magnitude * 6.0).coerceAtMost(30.0)
    fun powerKw(wind: Double, yawError: Float): Double =
        WindPowerCalculator.turbinePowerKw(wind, spec) * Math.cos(Math.toRadians(yawError.toDouble())).coerceAtLeast(0.0).let { it * it * it }
}
