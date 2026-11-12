package ua.kpi.windfarm6.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.util.Locale

/** Результат читання файлу: коректні записи + опис пропущених рядків. */
data class ReadResult(val records: List<EnergyRecord>, val errors: List<String> = emptyList(), val missing: Boolean = false)

/**
 * CSV за RFC 4180: роздільник «,», поля з комою, лапками чи переносом беруться в лапки,
 * лапки всередині подвоюються. Числа завжди з крапкою (Locale.US), щоб файл однаково
 * читався в Excel / LibreOffice і на телефоні з будь-якою мовою системи.
 */
object CsvCodec {
    val HEADER = listOf("id", "station", "type", "date", "energy_mwh", "avg_wind_ms", "status", "note")

    fun escape(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun encode(records: List<EnergyRecord>): String = buildString {
        appendLine(HEADER.joinToString(","))
        records.forEach { r ->
            appendLine(listOf(r.id.toString(), r.station, r.type.name, r.date, "%.1f".format(Locale.US, r.energyMwh),
                "%.1f".format(Locale.US, r.avgWind), r.status.name, r.note.replace('\n', ' ').replace('\r', ' ')).joinToString(",") { escape(it) })
        }
    }

    /** Розбір одного рядка з урахуванням лапок. */
    fun splitLine(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /**
     * Розбір файлу. Некоректні рядки не зупиняють читання — вони пропускаються
     * з повідомленням «рядок N: причина», щоб один зіпсований рядок не знищив увесь журнал.
     */
    fun decode(text: String): ReadResult {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ReadResult(emptyList())
        val header = splitLine(lines.first()).map { it.trim().lowercase() }
        if (header.take(6) != HEADER.take(6)) return ReadResult(emptyList(), listOf("Невідомий заголовок CSV: ${lines.first().take(60)}"))
        val records = ArrayList<EnergyRecord>()
        val errors = ArrayList<String>()
        lines.drop(1).forEachIndexed { idx, line ->
            val n = idx + 2
            val f = splitLine(line)
            if (f.size < 6) { errors += "рядок $n: замало полів (${f.size})"; return@forEachIndexed }
            val r = runCatching {
                EnergyRecord(
                    id = f[0].trim().toLong(),
                    station = f[1].trim(),
                    type = StationType.valueOf(f[2].trim()),
                    date = f[3].trim(),
                    energyMwh = f[4].trim().toDouble(),
                    avgWind = f[5].trim().toDouble(),
                    status = f.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() }?.let { StationStatus.valueOf(it) } ?: StationStatus.NORMAL,
                    note = f.getOrNull(7) ?: ""
                )
            }.getOrElse { errors += "рядок $n: ${it.message ?: "некоректне значення"}"; return@forEachIndexed }
            val problems = RecordValidator.validate(r)
            if (problems.isEmpty()) records += r else errors += "рядок $n: ${problems.first()}"
        }
        return ReadResult(records, errors)
    }
}

object JsonCodec {
    fun encode(records: List<EnergyRecord>): String =
        AppJson.encodeToString(RecordsFile.serializer(), RecordsFile(1, Dates.today(), records))

    fun decode(text: String): ReadResult = try {
        val file = AppJson.decodeFromString(RecordsFile.serializer(), text)
        val (ok, bad) = file.records.partition { RecordValidator.validate(it).isEmpty() }
        ReadResult(ok, bad.map { "запис id=${it.id}: ${RecordValidator.validate(it).first()}" })
    } catch (e: SerializationException) {
        ReadResult(emptyList(), listOf("Пошкоджений JSON: ${e.message?.lineSequence()?.firstOrNull()?.take(120)}"))
    } catch (e: IllegalArgumentException) {
        ReadResult(emptyList(), listOf("Некоректний JSON: ${e.message?.take(120)}"))
    }
}

enum class FileFormat(val ext: String, val title: String) { CSV("csv", "CSV"), JSON("json", "JSON") }

/**
 * Файли у внутрішньому сховищі (context.filesDir): доступні лише застосунку, дозволи не потрібні.
 * Усі операції — у Dispatchers.IO, щоб не блокувати UI.
 * Запис атомарний: спершу тимчасовий файл, потім перейменування — обрив під час запису
 * не залишить напівзаписаний журнал.
 */
class RecordFileStore(private val dir: File, private val baseName: String = "energy") {
    fun file(format: FileFormat) = File(dir, "$baseName.${format.ext}")

    suspend fun read(format: FileFormat): ReadResult = withContext(Dispatchers.IO) {
        val f = file(format)
        if (!f.exists()) return@withContext ReadResult(emptyList(), missing = true)
        try {
            val text = f.readText(Charsets.UTF_8)
            if (format == FileFormat.CSV) CsvCodec.decode(text) else JsonCodec.decode(text)
        } catch (e: IOException) {
            ReadResult(emptyList(), listOf("Помилка читання ${f.name}: ${e.message}"))
        }
    }

    suspend fun write(format: FileFormat, records: List<EnergyRecord>) = withContext(Dispatchers.IO) {
        val f = file(format)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(if (format == FileFormat.CSV) CsvCodec.encode(records) else JsonCodec.encode(records), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("Не вдалося зберегти ${f.name}") }
    }

    /** Дописати один запис у CSV без перезапису файлу (для журналу, що росте). */
    suspend fun appendCsv(r: EnergyRecord) = withContext(Dispatchers.IO) {
        val f = file(FileFormat.CSV)
        if (!f.exists() || f.length() == 0L) f.writeText(CsvCodec.HEADER.joinToString(",") + "\n")
        f.appendText(CsvCodec.encode(listOf(r)).lines().drop(1).filter { it.isNotEmpty() }.joinToString("\n", postfix = "\n"))
    }

    suspend fun delete(format: FileFormat) = withContext(Dispatchers.IO) { file(format).delete() }

    fun info(format: FileFormat): String = file(format).let { if (it.exists()) "${it.name} · ${it.length()} Б" else "${it.name} · немає" }

    /** Для демонстрації обробки помилок: дописати у файл «сміття». */
    suspend fun corrupt(format: FileFormat) = withContext(Dispatchers.IO) {
        val f = file(format)
        if (!f.exists()) return@withContext
        if (format == FileFormat.CSV) f.appendText("42,Без дати,ONSHORE,вчора,abc,7\nкороткий рядок\n")
        else f.writeText(f.readText().dropLast(f.length().toInt().coerceAtMost(40)))
    }
}
