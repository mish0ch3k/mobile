package ua.kpi.windfarm6.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** Знімок ресурсів пристрою. Відсотки — 0…100; null — показник недоступний. */
data class ResourceSnapshot(
    val time: Long,
    val cpuPercent: Float?,           // загальне завантаження CPU (/proc/stat), якщо система дозволяє
    val appCpuPercent: Float,         // CPU цього процесу від усіх ядер
    val cores: Int,
    val ramUsedPercent: Float,
    val ramAvailMb: Long,
    val ramTotalMb: Long,
    val lowMemory: Boolean,
    val heapUsedMb: Float,
    val heapMaxMb: Float,
    val batteryPercent: Int,
    val charging: Boolean,
    val batteryTempC: Float,
    val batteryVoltage: Float,
    val rxKbps: Float,
    val txKbps: Float,
    val storageFreeGb: Float,
    val storageTotalGb: Float
) {
    val storageUsedPercent get() = if (storageTotalGb > 0) (1 - storageFreeGb / storageTotalGb) * 100 else 0f
}

/** Чиста арифметика над лічильниками (тестується без Android). */
object ResourceMath {
    /** Завантаження CPU між двома зчитуваннями /proc/stat: 1 − Δidle/Δtotal. */
    fun cpuFromStat(prev: LongArray, cur: LongArray): Float? {
        if (prev.size < 4 || cur.size < 4) return null
        val idle = { a: LongArray -> a[3] + a.getOrElse(4) { 0 } }       // idle + iowait
        val total = { a: LongArray -> a.sum() }
        val dt = total(cur) - total(prev)
        if (dt <= 0) return null
        return ((1 - (idle(cur) - idle(prev)).toFloat() / dt) * 100).coerceIn(0f, 100f)
    }

    /** Рядок «cpu  user nice system idle iowait irq softirq …» → числа. */
    fun parseStatLine(line: String): LongArray? =
        line.trim().split(Regex("\\s+")).takeIf { it.firstOrNull() == "cpu" }?.drop(1)?.mapNotNull { it.toLongOrNull() }?.toLongArray()

    /** CPU процесу: Δ процесорного часу / (Δ реального часу × кількість ядер). */
    fun appCpu(cpuMsDelta: Long, wallMsDelta: Long, cores: Int): Float =
        if (wallMsDelta <= 0 || cores <= 0) 0f else (cpuMsDelta.toFloat() / (wallMsDelta * cores) * 100).coerceIn(0f, 100f)

    fun rate(bytesDelta: Long, ms: Long): Float = if (ms <= 0 || bytesDelta < 0) 0f else bytesDelta / 1024f / (ms / 1000f)

    /** Рівень для кольорової індикації. */
    fun level(percent: Float, warn: Float = 70f, alarm: Float = 90f) = when { percent >= alarm -> 2; percent >= warn -> 1; else -> 0 }
}

/**
 * Зчитування ресурсів. Опитування йде у фоновому потоці (flowOn(Dispatchers.IO)),
 * UI отримує лише готові знімки — інтерфейс не блокується навіть при повільному читанні файлів.
 */
class ResourceMonitor(private val context: Context) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private val cores = Runtime.getRuntime().availableProcessors()
    private var prevStat: LongArray? = null
    private var prevCpuMs = Process.getElapsedCpuTime()
    private var prevWall = SystemClock.elapsedRealtime()
    private var prevRx = TrafficStats.getTotalRxBytes()
    private var prevTx = TrafficStats.getTotalTxBytes()

    /** /proc/stat з Android 8 зазвичай закритий для застосунків — тоді показуємо лише CPU процесу. */
    private fun readStat(): LongArray? = runCatching { File("/proc/stat").useLines { it.firstOrNull() }?.let(ResourceMath::parseStatLine) }.getOrNull()

    fun sample(): ResourceSnapshot {
        val now = SystemClock.elapsedRealtime()
        val stat = readStat()
        val cpu = prevStat?.let { p -> stat?.let { ResourceMath.cpuFromStat(p, it) } }
        prevStat = stat
        val cpuMs = Process.getElapsedCpuTime()
        val appCpu = ResourceMath.appCpu(cpuMs - prevCpuMs, now - prevWall, cores)
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes()
        val rxRate = if (rx == TrafficStats.UNSUPPORTED.toLong()) 0f else ResourceMath.rate(rx - prevRx, now - prevWall)
        val txRate = if (tx == TrafficStats.UNSUPPORTED.toLong()) 0f else ResourceMath.rate(tx - prevTx, now - prevWall)
        prevCpuMs = cpuMs; prevWall = now; prevRx = rx; prevTx = tx

        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) // «липкий» інтент — без реєстрації слухача
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val fs = StatFs(context.filesDir.path)
        return ResourceSnapshot(
            time = System.currentTimeMillis(),
            cpuPercent = cpu, appCpuPercent = appCpu, cores = cores,
            ramUsedPercent = (1 - mi.availMem.toFloat() / mi.totalMem) * 100,
            ramAvailMb = mi.availMem / 1048576, ramTotalMb = mi.totalMem / 1048576, lowMemory = mi.lowMemory,
            heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576f, heapMaxMb = rt.maxMemory() / 1048576f,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempC = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            batteryVoltage = (battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f,
            rxKbps = rxRate, txKbps = txRate,
            storageFreeGb = fs.availableBytes / 1e9f, storageTotalGb = fs.totalBytes / 1e9f
        )
    }

    /** Потік знімків з інтервалом [periodMs]; читання — у пулі IO. */
    fun snapshots(periodMs: () -> Long): Flow<ResourceSnapshot> = flow {
        while (true) { emit(sample()); delay(periodMs()) }
    }.flowOn(Dispatchers.IO)
}

/** Вибір теми: зберігається в SharedPreferences і застосовується до всього застосунку одразу. */
enum class ThemeMode(val title: String) { SYSTEM("Як у системі"), LIGHT("Світла"), DARK("Темна") }

object ThemePrefs {
    private const val KEY = "theme_mode"
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
        _mode.value = runCatching { ThemeMode.valueOf(prefs!!.getString(KEY, ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun set(m: ThemeMode) { _mode.value = m; prefs?.edit()?.putString(KEY, m.name)?.apply() }
}
