package ua.kpi.windfarm3.errors

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Запис журналу помилок. */
data class LogEntry(val time: Long, val source: String, val title: String, val message: String) {
    fun format(): String = "${FORMAT.get()!!.format(Date(time))} | $source | $title | $message"

    companion object {
        // SimpleDateFormat не потокобезпечний, тож окремий екземпляр на потік
        private val FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

        fun parse(line: String): LogEntry? {
            val p = line.split(" | ", limit = 4)
            if (p.size != 4) return null
            val t = runCatching { FORMAT.get()!!.parse(p[0])?.time }.getOrNull() ?: return null
            return LogEntry(t, p[1], p[2], p[3])
        }
    }
}

/** Журнал помилок мережевих запитів. */
interface ErrorLog {
    fun log(source: String, error: AppError) = write(source, error.title, error.userMessage)
    fun write(source: String, title: String, message: String)
    fun entries(): List<LogEntry>
    fun clear()
}

/** Журнал у пам'яті (для середнього рівня та тестів). */
class MemoryErrorLog(private val capacity: Int = 100) : ErrorLog {
    private val items = ArrayDeque<LogEntry>()

    @Synchronized override fun write(source: String, title: String, message: String) {
        items.addFirst(LogEntry(System.currentTimeMillis(), source, title, message))
        while (items.size > capacity) items.removeLast()
    }

    @Synchronized override fun entries(): List<LogEntry> = items.toList()
    @Synchronized override fun clear() = items.clear()
}

/**
 * Журнал у текстовому файлі (filesDir/network_errors.log) — зберігається між запусками.
 * Файл обрізається до maxLines останніх рядків, щоб не рости безмежно.
 */
class FileErrorLog(private val file: File, private val maxLines: Int = 500) : ErrorLog {

    @Synchronized override fun write(source: String, title: String, message: String) {
        val line = LogEntry(System.currentTimeMillis(), source, title, message.replace('\n', ' ')).format()
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines).joinToString("\n", postfix = "\n"))
    }

    /** Новіші записи — першими. */
    @Synchronized override fun entries(): List<LogEntry> =
        if (!file.exists()) emptyList() else file.readLines().mapNotNull(LogEntry::parse).asReversed()

    @Synchronized override fun clear() { if (file.exists()) file.writeText("") }

    val path: String get() = file.absolutePath
}
