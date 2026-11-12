package ua.kpi.windfarm5.sensors

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Тривимірний вектор показів (x, y, z). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun normalized(): Vec3 = magnitude.let { if (it == 0f) this else Vec3(x / it, y / it, z / it) }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        fun of(v: FloatArray) = Vec3(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
    }
}

/**
 * Типи сенсорів, з якими працює застосунок, з допустимими діапазонами значень.
 * [androidType] — константа Sensor.TYPE_* (задана числом, щоб модуль не залежав від Android).
 */
enum class SensorKind(val title: String, val unit: String, val androidType: Int, val vector: Boolean, val validRange: ClosedFloatingPointRange<Float>, val description: String) {
    ACCELEROMETER("Акселерометр", "м/с²", 1, true, -80f..80f, "Прискорення по осях X, Y, Z з урахуванням сили тяжіння; для вібрації та нахилу башти."),
    GYROSCOPE("Гіроскоп", "рад/с", 4, true, -35f..35f, "Кутова швидкість обертання пристрою — аналог повороту гондоли турбіни."),
    MAGNETOMETER("Магнітометр", "мкТл", 2, true, -2000f..2000f, "Магнітне поле Землі; разом з акселерометром дає компас для напрямку вітру."),
    PRESSURE("Барометр", "гПа", 6, false, 300f..1100f, "Атмосферний тиск — потрібен для розрахунку густини повітря."),
    TEMPERATURE("Температура повітря", "°C", 13, false, -40f..85f, "Температура навколишнього середовища — впливає на густину повітря."),
    HUMIDITY("Вологість", "%", 12, false, 0f..100f, "Відносна вологість — ризик обмерзання лопатей при морозі."),
    LIGHT("Освітленість", "лк", 5, false, 0f..200_000f, "Рівень освітлення: день / ніч для графіка обслуговування."),
    PROXIMITY("Наближення", "см", 8, false, 0f..100f, "Датчик наближення: чи щось закриває пристрій.");

    /** Перевірка показу: усі компоненти — скінченні числа в межах фізично можливого діапазону. */
    fun isValid(values: FloatArray): Boolean =
        values.isNotEmpty() && values.take(if (vector) 3 else 1).all { it.isFinite() && it in validRange }

    companion object {
        fun byAndroidType(t: Int) = entries.firstOrNull { it.androidType == t }
    }
}

/** Один показ сенсора. */
data class SensorSample(val kind: SensorKind, val values: FloatArray, val timestampMs: Long, val accuracy: Int = 3) {
    val vec: Vec3 get() = Vec3.of(values)
    /** Скалярне значення: модуль вектора або перший компонент. */
    val scalar: Float get() = if (kind.vector) vec.magnitude else values.firstOrNull() ?: 0f

    override fun equals(other: Any?) = other is SensorSample && kind == other.kind && timestampMs == other.timestampMs && values.contentEquals(other.values)
    override fun hashCode() = 31 * (31 * kind.hashCode() + values.contentHashCode()) + timestampMs.hashCode()
}

/** Обробка сирих показів сенсорів. */
object SensorMath {
    const val G = 9.80665f

    /**
     * Фільтр низьких частот (експоненційне згладжування): y = y_prev + α·(x − y_prev).
     * Менше α — плавніше, але з більшою затримкою. Використовується для виділення сили тяжіння.
     */
    class LowPass(private val alpha: Float) {
        init { require(alpha in 0f..1f) }
        private var state: Vec3? = null
        fun filter(v: Vec3): Vec3 = (state?.let { it + (v - it) * alpha } ?: v).also { state = it }
        fun reset() { state = null }
    }

    /** Кути нахилу (pitch — вперед/назад, roll — вліво/вправо), градуси, з вектора гравітації. */
    fun tilt(gravity: Vec3): Pair<Float, Float> {
        val pitch = Math.toDegrees(atan2(-gravity.x.toDouble(), sqrt((gravity.y * gravity.y + gravity.z * gravity.z).toDouble()))).toFloat()
        val roll = Math.toDegrees(atan2(gravity.y.toDouble(), gravity.z.toDouble())).toFloat()
        return pitch to roll
    }

    /** Відхилення від вертикалі (кут між віссю Z пристрою та гравітацією), градуси. */
    fun inclination(gravity: Vec3): Float {
        val m = gravity.magnitude
        if (m == 0f) return 0f
        return Math.toDegrees(kotlin.math.acos((gravity.z / m).toDouble().coerceIn(-1.0, 1.0))).toFloat()
    }

    /** Лінійне прискорення без сили тяжіння (вібрація): accel − gravity. */
    fun linear(accel: Vec3, gravity: Vec3): Vec3 = accel - gravity

    /** Середньоквадратичне значення (RMS) модулів — інтенсивність вібрації за вікно вимірів. */
    fun rms(values: List<Float>): Float = if (values.isEmpty()) 0f else sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()

    /**
     * Азимут (0° — північ, 90° — схід) за векторами гравітації та магнітного поля.
     * Той самий підхід, що й у SensorManager.getRotationMatrix: E = M × g, N = g × E.
     */
    fun heading(gravity: Vec3, magnetic: Vec3): Float? {
        val east = magnetic.cross(gravity)
        if (east.magnitude < 1e-3f || gravity.magnitude < 1e-3f) return null // вільне падіння або поруч магніт
        val e = east.normalized()
        val a = gravity.normalized()
        val n = a.cross(e)
        val deg = Math.toDegrees(atan2(e.y.toDouble(), n.y.toDouble())).toFloat()
        return (deg + 360f) % 360f
    }

    /** Найкоротша різниця кутів (−180…180]: на скільки повернути гондолу до напряму вітру. */
    fun angleDiff(from: Float, to: Float): Float {
        var d = (to - from) % 360f
        if (d > 180f) d -= 360f
        if (d <= -180f) d += 360f
        return d
    }

    /** Густина повітря, кг/м³: ρ = p / (R·T) за тиском у гПа і температурою в °C. */
    fun airDensity(pressureHpa: Float, temperatureC: Float): Double {
        require(pressureHpa > 0) { "Тиск має бути додатним" }
        require(temperatureC > -100 && temperatureC < 100) { "Температура поза межами" }
        return pressureHpa * 100.0 / (287.05 * (temperatureC + 273.15))
    }

    /** Ризик обмерзання лопатей: мінусова температура з високою вологістю. */
    fun icingRisk(temperatureC: Float, humidity: Float?): Boolean = temperatureC in -10f..1f && (humidity ?: 0f) >= 85f

    /** Чи «трясе» пристрій: модуль прискорення помітно відрізняється від g. */
    fun isShaking(accel: Vec3, thresholdMs2: Float = 3f) = abs(accel.magnitude - G) > thresholdMs2
}
