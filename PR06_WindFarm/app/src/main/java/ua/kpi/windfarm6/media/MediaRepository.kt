package ua.kpi.windfarm6.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ua.kpi.windfarm6.data.AppJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/** Тип медіафайлу, визначений за вмістом (сигнатурою), а не лише за розширенням. */
@Serializable
enum class MediaType(val title: String, val ext: String) {
    JPEG("Фото JPEG", "jpg"), PNG("Зображення PNG", "png"), PDF("PDF", "pdf"), DOCX("Документ Word", "docx"), UNKNOWN("Невідомий", "bin");

    val isImage get() = this == JPEG || this == PNG
}

/** Опис збереженого файлу (зберігається в index.json поруч із файлами). */
@Serializable
data class MediaFile(
    val id: String,          // ім'я файлу на диску
    val title: String,       // назва для користувача (можна редагувати)
    val type: MediaType,
    val addedAt: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0
)

@Serializable
private data class MediaIndex(val files: List<MediaFile> = emptyList())

/** Перевірка типу за першими байтами файлу. */
object FileSignature {
    fun detect(head: ByteArray, zipEntries: () -> List<String> = { emptyList() }): MediaType {
        fun at(i: Int) = head.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> MediaType.JPEG
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> MediaType.PNG
            at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44 && at(3) == 0x46 -> MediaType.PDF        // %PDF
            at(0) == 0x50 && at(1) == 0x4B && at(2) == 0x03 && at(3) == 0x04 ->                      // ZIP: DOCX — це ZIP з word/document.xml
                if (zipEntries().any { it.startsWith("word/") }) MediaType.DOCX else MediaType.UNKNOWN
            else -> MediaType.UNKNOWN
        }
    }

    /** Розмір проміжного декодування: найбільша степінь двійки, за якої сторона ≥ [target]. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var s = 1
        while (max(width, height) / (s * 2) >= target) s *= 2
        return s
    }
}

/**
 * Сховище медіафайлів у внутрішній пам'яті: filesDir/media/<папка>/.
 * Дозволи на читання пам'яті не потрібні: файли вибираються через системний вибір
 * (Photo Picker / SAF), застосунок отримує тимчасовий доступ до Uri і копіює вміст до себе.
 */
class MediaRepository(root: File, folder: String) {
    val dir = File(root, "media/$folder").apply { mkdirs() }
    private val thumbs = File(dir, ".thumbs").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    companion object {
        const val MAX_SIDE = 1600       // фото зменшуються до 1600 px по довшій стороні
        const val THUMB_SIDE = 320
        const val MAX_BYTES = 20L * 1024 * 1024
        /** Кеш мініатюр у пам'яті (≈1/16 доступної), спільний для всіх екранів. */
        val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16 / 1024).toInt()) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    suspend fun list(): List<MediaFile> = withContext(Dispatchers.IO) { readIndex().files.filter { File(dir, it.id).exists() } }

    private fun readIndex(): MediaIndex = runCatching { AppJson.decodeFromString(MediaIndex.serializer(), indexFile.readText()) }.getOrElse { MediaIndex() }
    private fun writeIndex(i: MediaIndex) = indexFile.writeText(AppJson.encodeToString(MediaIndex.serializer(), i))

    private fun displayName(cr: ContentResolver, uri: Uri): String =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment ?: "файл"

    /**
     * Імпорт з Uri: перевірка розміру та сигнатури, копіювання; фото зменшуються і повертаються
     * за EXIF-орієнтацією. Непідтримувані типи відхиляються з поясненням.
     */
    suspend fun importFile(cr: ContentResolver, uri: Uri, allowed: Set<MediaType>): Result<MediaFile> = withContext(Dispatchers.IO) {
        runCatching {
            val name = displayName(cr, uri)
            val tmp = File(dir, "import.tmp")
            cr.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { out -> copyLimited(input, out) } } ?: throw IOException("Файл недоступний")
            val head = tmp.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
            val type = FileSignature.detect(head) { zipEntries(tmp) }
            if (type !in allowed) { tmp.delete(); throw IllegalArgumentException("«$name»: тип ${type.title} не підтримується") }
            store(tmp, name.substringBeforeLast('.'), type)
        }
    }

    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("Файл більший за ${MAX_BYTES / 1024 / 1024} МБ")
            out.write(buf, 0, n)
        }
    }

    private fun zipEntries(f: File): List<String> = runCatching {
        java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().take(50).map { it.name }.toList() }
    }.getOrElse { emptyList() }

    /** Перенести тимчасовий файл у сховище (з оптимізацією фото) і додати до індексу. */
    private fun store(tmp: File, title: String, type: MediaType): MediaFile {
        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}.${type.ext}"
        val target = File(dir, id)
        var w = 0; var h = 0
        if (type.isImage) {
            val bmp = decodeScaled(tmp, MAX_SIDE) ?: throw IOException("Не вдалося прочитати зображення")
            FileOutputStream(target).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) } // PNG теж зберігаємо як JPEG — менше місця
            w = bmp.width; h = bmp.height
            bmp.recycle()
            tmp.delete()
        } else if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        val f = MediaFile(id, title.ifBlank { id }, if (type == MediaType.PNG) MediaType.JPEG else type, System.currentTimeMillis(), target.length(), w, h)
        writeIndex(MediaIndex(readIndex().files + f))
        return f
    }

    /** Декодування з inSampleSize (не тягне в пам'ять повнорозмірне фото) + поворот за EXIF. */
    fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = FileSignature.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        var bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
        val scale = maxSide.toFloat() / max(bmp.width, bmp.height)
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (scale < 1f || rotation != 0f) {
            val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out != bmp) bmp.recycle()
            bmp = out
        }
        return bmp
    }

    /** Мініатюра: кеш у пам'яті → кеш на диску → генерація (для PDF — перша сторінка). */
    suspend fun thumbnail(f: MediaFile): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${dir.path}/${f.id}"
        memoryCache.get(key)?.let { return@withContext it }
        val disk = File(thumbs, f.id + ".jpg")
        val bmp = if (disk.exists()) BitmapFactory.decodeFile(disk.path) else {
            val made = when {
                f.type.isImage -> decodeScaled(File(dir, f.id), THUMB_SIDE)
                f.type == MediaType.PDF -> renderPdfPage(File(dir, f.id), 0, THUMB_SIDE)
                else -> null
            }
            made?.also { b -> FileOutputStream(disk).use { b.compress(Bitmap.CompressFormat.JPEG, 80, it) } }
        }
        bmp?.also { memoryCache.put(key, it) }
    }

    /** Рендер сторінки PDF у Bitmap системним PdfRenderer (без сторонніх бібліотек). */
    fun renderPdfPage(file: File, index: Int, width: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return@runCatching null
                r.openPage(index.coerceIn(0, r.pageCount - 1)).use { p ->
                    val h = (width.toFloat() * p.height / p.width).toInt()
                    Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    fun pdfPageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { r -> r.pageCount } }
    }.getOrDefault(0)

    suspend fun rename(id: String, title: String) = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "Назва не може бути порожньою" }
        writeIndex(MediaIndex(readIndex().files.map { if (it.id == id) it.copy(title = title.trim().take(80)) else it }))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, id).delete(); File(thumbs, "$id.jpg").delete()
        memoryCache.remove("${dir.path}/$id")
        writeIndex(MediaIndex(readIndex().files.filterNot { it.id == id }))
    }

    fun file(f: MediaFile) = File(dir, f.id)

    // ---------- демо-файли (щоб перевірити роботу без файлів на пристрої) ----------

    /** Згенерувати «фото» турбіни: небо, земля, башта, лопаті — з підписом. */
    suspend fun createDemoPhoto(label: String, seed: Int): MediaFile = withContext(Dispatchers.IO) {
        val w = 2400; val h = 1600 // більше за MAX_SIDE — щоб показати оптимізацію розміру
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val skies = intArrayOf(0xFF64B5F6.toInt(), 0xFFFFB74D.toInt(), 0xFF90A4AE.toInt(), 0xFF4FC3F7.toInt())
        c.drawColor(skies[seed % skies.size])
        p.color = 0xFF7CB342.toInt(); c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
        for (k in 0 until 3) {
            val x = w * (0.25f + 0.25f * k); val top = h * (0.25f + 0.05f * ((k + seed) % 3))
            p.color = Color.WHITE; p.strokeWidth = 26f
            c.drawLine(x, top, x, h * 0.75f, p)
            for (i in 0 until 3) {
                val a = Math.toRadians(i * 120.0 + seed * 17 + k * 40)
                c.drawLine(x, top, x + (260 * Math.cos(a)).toFloat(), top + (260 * Math.sin(a)).toFloat(), p)
            }
        }
        p.color = Color.BLACK; p.textSize = 90f
        c.drawText(label, 60f, h - 70f, p)
        val tmp = File(dir, "import.tmp")
        FileOutputStream(tmp).use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
        store(tmp, label, MediaType.JPEG)
    }

    /** Згенерувати PDF-акт огляду (PdfDocument), щоб було що переглядати. */
    suspend fun createDemoPdf(title: String, lines: List<String>): MediaFile = withContext(Dispatchers.IO) {
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; isFakeBoldText = true }
        page.canvas.drawText(title, 50f, 70f, p)
        p.textSize = 12f; p.isFakeBoldText = false
        lines.forEachIndexed { i, l -> page.canvas.drawText(l, 50f, 110f + i * 20f, p) }
        doc.finishPage(page)
        val tmp = File(dir, "import.tmp")
        FileOutputStream(tmp).use { doc.writeTo(it) }
        doc.close()
        store(tmp, title, MediaType.PDF)
    }
}
