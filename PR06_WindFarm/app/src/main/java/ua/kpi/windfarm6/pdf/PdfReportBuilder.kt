package ua.kpi.windfarm6.pdf

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/** Стовпець таблиці: заголовок, частка ширини, вирівнювання праворуч (для чисел). */
data class Col(val title: String, val weight: Float, val right: Boolean = false)

/** Серія для графіка у звіті. */
data class PdfSeries(val name: String, val values: List<Double>, val color: Int, val bars: Boolean)

/**
 * Побудова багатосторінкового PDF через android.graphics.pdf.PdfDocument.
 * Координати — у пунктах (1/72 дюйма), сторінка A4 = 595 × 842.
 * Конструктор відстежує поточну позицію y і сам переносить вміст на нову сторінку,
 * а таблиця повторює заголовок на кожній сторінці.
 */
class PdfReportBuilder(private val title: String, private val accent: Int = Color.rgb(21, 101, 192)) {
    companion object {
        const val W = 595f
        const val H = 842f
        const val M = 48f            // поля
        const val FOOTER = 30f
    }

    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private val canvas: Canvas get() = page!!.canvas
    private var y = M
    var pageCount = 0
        private set
    /** Розділи для змісту: назва → номер сторінки. */
    val sections = ArrayList<Pair<String, Int>>()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.rgb(33, 33, 33) }
    private val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val small = Paint(body).apply { textSize = 8.5f; color = Color.GRAY }
    private val line = Paint().apply { color = Color.rgb(200, 200, 200); strokeWidth = 0.6f }

    init { newPage() }

    private fun newPage() {
        page?.let { finish(it) }
        pageCount++
        page = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), pageCount).create())
        y = M
        // колонтитул: назва звіту і смуга кольору
        val p = Paint(small).apply { color = accent }
        canvas.drawRect(0f, 0f, W, 6f, Paint().apply { color = accent })
        canvas.drawText(title, M, 24f, p)
        y = M + 6
    }

    private fun finish(p: PdfDocument.Page) {
        p.canvas.drawText("Сторінка ${p.info.pageNumber}", W - M - 60, H - FOOTER / 2, small)
        doc.finishPage(p)
    }

    /** Чи поміститься блок висотою [h]; якщо ні — нова сторінка. */
    fun ensure(h: Float) { if (y + h > H - M - FOOTER) newPage() }

    fun pageBreak() = newPage()

    fun title(text: String, subtitle: String? = null) {
        val p = Paint(bold).apply { textSize = 22f; color = accent }
        ensure(60f)
        canvas.drawText(text, M, y + 22, p); y += 34
        if (subtitle != null) { canvas.drawText(subtitle, M, y + 10, Paint(body).apply { color = Color.DKGRAY }); y += 22 }
        canvas.drawLine(M, y, W - M, y, Paint().apply { color = accent; strokeWidth = 1.5f }); y += 14
    }

    fun heading(text: String) {
        ensure(40f)
        sections += text to pageCount
        val p = Paint(bold).apply { textSize = 14f; color = accent }
        y += 6
        canvas.drawText(text, M, y + 14, p); y += 24
    }

    /** Абзац з переносом слів по ширині сторінки. */
    fun paragraph(text: String, paint: Paint = body) {
        val lines = wrap(text, paint, W - 2 * M)
        val lh = paint.textSize * 1.45f
        lines.forEach { l -> ensure(lh); canvas.drawText(l, M, y + paint.textSize, paint); y += lh }
        y += 6
    }

    fun bullet(items: List<String>) = items.forEach { paragraph("•  $it") }

    fun keyValues(pairs: List<Pair<String, String>>) {
        val lh = 17f
        pairs.forEach { (k, v) ->
            ensure(lh)
            canvas.drawText(k, M, y + 11, body)
            canvas.drawText(v, W - M - bold.measureText(v), y + 11, bold)
            canvas.drawLine(M, y + lh - 2, W - M, y + lh - 2, line)
            y += lh
        }
        y += 8
    }

    /** Таблиця: смугасті рядки, числа праворуч, заголовок повторюється після переносу сторінки. */
    fun table(cols: List<Col>, rows: List<List<String>>, highlight: (Int) -> Boolean = { false }) {
        val total = cols.sumOf { it.weight.toDouble() }.toFloat()
        val widths = cols.map { (W - 2 * M) * it.weight / total }
        val rh = 18f
        fun header() {
            canvas.drawRect(M, y, W - M, y + rh, Paint().apply { color = accent })
            var x = M
            cols.forEachIndexed { i, c ->
                val p = Paint(bold).apply { color = Color.WHITE; textSize = 9.5f }
                val tx = if (c.right) x + widths[i] - 4 - p.measureText(c.title) else x + 4
                canvas.drawText(c.title, tx, y + 12.5f, p); x += widths[i]
            }
            y += rh
        }
        ensure(rh * 2); header()
        rows.forEachIndexed { r, row ->
            if (y + rh > H - M - FOOTER) { newPage(); header() }
            val bg = when { highlight(r) -> Color.rgb(255, 235, 238); r % 2 == 1 -> Color.rgb(243, 246, 250); else -> Color.WHITE }
            canvas.drawRect(M, y, W - M, y + rh, Paint().apply { color = bg })
            var x = M
            row.forEachIndexed { i, v ->
                val p = Paint(body).apply { textSize = 9.5f }
                val text = ellipsize(v, p, widths[i] - 8)
                val tx = if (cols[i].right) x + widths[i] - 4 - p.measureText(text) else x + 4
                canvas.drawText(text, tx, y + 12.5f, p); x += widths[i]
            }
            y += rh
        }
        canvas.drawLine(M, y, W - M, y, line); y += 12
    }

    /** Графік: стовпчики та/або лінії, вісь Y з «круглими» поділками, підписи X, легенда. */
    fun chart(caption: String, labels: List<String>, series: List<PdfSeries>, height: Float = 190f, unit: String = "") {
        ensure(height + 40)
        val left = M + 34; val right = W - M; val top = y + 8; val bottom = y + height - 22
        val maxV = series.flatMap { it.values }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val step = niceStep(maxV / 4)
        val topV = kotlin.math.ceil(maxV / step) * step
        fun py(v: Double) = (bottom - v / topV * (bottom - top)).toFloat()
        // сітка
        var t = 0.0
        while (t <= topV + step / 2) {
            canvas.drawLine(left, py(t), right, py(t), line)
            canvas.drawText(fmtAxis(t), M, py(t) + 3, small)
            t += step
        }
        val n = labels.size.coerceAtLeast(1)
        val slot = (right - left) / n
        series.filter { it.bars }.forEachIndexed { si, s ->
            val bars = series.count { it.bars }
            val bw = slot * 0.7f / bars
            s.values.forEachIndexed { i, v ->
                val x = left + i * slot + slot * 0.15f + si * bw
                canvas.drawRect(RectF(x, py(v), x + bw - 1, bottom), Paint().apply { color = s.color })
            }
        }
        series.filterNot { it.bars }.forEach { s ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; strokeWidth = 2f; style = Paint.Style.STROKE }
            val path = android.graphics.Path()
            s.values.forEachIndexed { i, v ->
                val x = left + i * slot + slot / 2
                if (i == 0) path.moveTo(x, py(v)) else path.lineTo(x, py(v))
            }
            canvas.drawPath(path, p)
        }
        val every = maxOf(1, n / 10)
        labels.forEachIndexed { i, l -> if (i % every == 0) canvas.drawText(l, left + i * slot + 2, bottom + 11, small) }
        // легенда
        var lx = left
        series.forEach { s ->
            canvas.drawRect(lx, bottom + 15, lx + 8, bottom + 23, Paint().apply { color = s.color })
            canvas.drawText(s.name, lx + 11, bottom + 22, small); lx += 16 + small.measureText(s.name) + 10
        }
        canvas.drawText(caption + if (unit.isNotEmpty()) ", $unit" else "", M, bottom + 36, Paint(small).apply { color = Color.DKGRAY })
        y = bottom + 46
    }

    /** Кругова діаграма часток (наприклад, виробіток за станціями). */
    fun pie(caption: String, parts: List<Pair<String, Double>>, colors: List<Int>) {
        ensure(170f)
        val total = parts.sumOf { it.second }.takeIf { it > 0 } ?: return
        val r = 70f; val cx = M + r + 10; val cy = y + r + 10
        var start = -90f
        parts.forEachIndexed { i, (name, v) ->
            val sweep = (v / total * 360).toFloat()
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), start, sweep, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i % colors.size] })
            start += sweep
            val ly = y + 14 + i * 16
            canvas.drawRect(cx + r + 30, ly - 8, cx + r + 40, ly + 2, Paint().apply { color = colors[i % colors.size] })
            canvas.drawText("$name — ${"%.1f".format(v / total * 100)} %", cx + r + 46, ly, body)
        }
        y += 2 * r + 24
        canvas.drawText(caption, M, y, Paint(small).apply { color = Color.DKGRAY }); y += 14
    }

    fun save(file: File): File {
        page?.let { finish(it) }; page = null
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    private fun wrap(text: String, p: Paint, width: Float): List<String> = text.split("\n").flatMap { para ->
        val out = ArrayList<String>(); var cur = ""
        para.split(" ").forEach { w ->
            val next = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(next) <= width) cur = next else { if (cur.isNotEmpty()) out += cur; cur = w }
        }
        out += cur; out
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        val mag = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(raw)))
        val f = raw / mag
        return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
    }

    private fun fmtAxis(v: Double) = when {
        v >= 1000 && v % 1000 == 0.0 -> "%.0fk".format(v / 1000)
        v >= 1000 -> "%.1fk".format(v / 1000)
        v < 10 && v % 1 != 0.0 -> "%.1f".format(v)
        else -> "%.0f".format(v)
    }
}
