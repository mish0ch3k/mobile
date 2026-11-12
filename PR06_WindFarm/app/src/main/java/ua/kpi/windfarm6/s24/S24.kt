package ua.kpi.windfarm6.s24

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.kpi.windfarm6.data.Dates
import ua.kpi.windfarm6.data.EnergyRecord
import ua.kpi.windfarm6.data.SampleData
import ua.kpi.windfarm6.data.StationStatus
import ua.kpi.windfarm6.media.MediaRepository
import ua.kpi.windfarm6.pdf.Col
import ua.kpi.windfarm6.pdf.PdfReportBuilder
import ua.kpi.windfarm6.pdf.PdfSeries
import ua.kpi.windfarm6.ui.common.Chips
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.Message
import ua.kpi.windfarm6.ui.common.MultiChips
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import java.io.File
import java.util.Locale
import android.graphics.Color as AColor

private fun Double.f(d: Int = 1) = String.format(Locale("uk"), "%,.${d}f", this)
private val BLUE = AColor.rgb(21, 101, 192)
private val ORANGE = AColor.rgb(239, 108, 0)
private val GREEN = AColor.rgb(46, 125, 50)
private val PIE = listOf(BLUE, ORANGE, GREEN, AColor.rgb(106, 27, 154), AColor.rgb(0, 131, 143))

/** Параметри звіту, які задає користувач (просунутий рівень). */
data class ReportOptions(
    val days: Int = 14,
    val stations: Set<String> = SampleData.stations.map { it.first }.toSet(),
    val summary: Boolean = true,
    val table: Boolean = true,
    val charts: Boolean = true,
    val recommendations: Boolean = true,
    val author: String = "Гаврилюк М. Є.",
    val comment: String = ""
)

/** Генератори звітів (чиста логіка над даними; лише PdfReportBuilder працює з Android Canvas). */
object Reports {
    private fun lastDay(data: List<EnergyRecord>) = data.filter { it.date == data.maxOf { r -> r.date } }

    /** Базовий: текст + таблиця станцій за останню добу. */
    fun basic(data: List<EnergyRecord>, out: File): PdfReportBuilder = PdfReportBuilder("Добовий звіт ВЕС").apply {
        val day = lastDay(data)
        title("Добовий звіт вітрових електростанцій", "Дата: ${day.first().date}")
        paragraph("Звіт містить добовий виробіток електроенергії, середню швидкість вітру та стан кожної станції. " +
            "Дані сформовано автоматично із журналу телеметрії.")
        table(listOf(Col("Станція", 2.2f), Col("Тип", 1.4f), Col("Виробіток, МВт·год", 1.6f, true), Col("Вітер, м/с", 1f, true), Col("Стан", 1.4f)),
            day.map { listOf(it.station, it.type.title, it.energyMwh.f(), it.avgWind.f(), it.status.title) },
            highlight = { day[it].status != StationStatus.NORMAL })
        paragraph("Разом за добу: ${day.sumOf { it.energyMwh }.f()} МВт·год.")
        save(out)
    }

    /** Середній: стилі, динамічні таблиці, графіки (лінія за 14 діб, стовпчики, кругова). */
    fun medium(data: List<EnergyRecord>, out: File): PdfReportBuilder = PdfReportBuilder("Звіт за 14 діб", ORANGE).apply {
        val from = Dates.plusDays(data.maxOf { it.date }, -13)
        val period = data.filter { it.date >= from }
        val byDay = period.groupBy { it.date }.toSortedMap()
        val byStation = period.groupBy { it.station }.mapValues { (_, l) -> l.sumOf { it.energyMwh } }
        title("Виробіток за 14 діб", "$from — ${data.maxOf { it.date }}")
        heading("Динаміка виробітку")
        chart("Сумарний добовий виробіток і середній вітер (×100)", byDay.keys.map { Dates.short(it) }, listOf(
            PdfSeries("МВт·год", byDay.values.map { d -> d.sumOf { it.energyMwh } }, BLUE, bars = true),
            PdfSeries("Вітер ×100", byDay.values.map { d -> d.map { it.avgWind }.average() * 100 }, ORANGE, bars = false)))
        heading("Внесок станцій")
        pie("Частка станцій у виробітку", byStation.toList().sortedByDescending { it.second }, PIE)
        heading("Таблиця за станціями")
        table(listOf(Col("Станція", 2.2f), Col("МВт·год", 1.3f, true), Col("Сер. вітер", 1f, true), Col("Зупинок", 1f, true)),
            period.groupBy { it.station }.map { (s, l) -> listOf(s, l.sumOf { it.energyMwh }.f(), l.map { it.avgWind }.average().f(), "${l.count { it.status != StationStatus.NORMAL }}") })
        save(out)
    }

    /** Просунутий: багатосторінковий звіт із розділами, що обирає користувач. */
    fun advanced(data: List<EnergyRecord>, o: ReportOptions, out: File, progress: (Float) -> Unit): PdfReportBuilder =
        PdfReportBuilder("Аналітичний звіт ВЕС · ${o.days} діб", GREEN).apply {
            val to = data.maxOf { it.date }
            val from = Dates.plusDays(to, -(o.days - 1))
            val sel = data.filter { it.date >= from && it.station in o.stations }
            title("Аналітичний звіт вітрових електростанцій", "$from — $to · станцій: ${o.stations.size} · автор: ${o.author}")
            paragraph("Мета звіту — оцінити виробіток, використання встановленої потужності та простої станцій за обраний період.")
            if (o.comment.isNotBlank()) paragraph("Коментар автора: ${o.comment}")
            progress(0.1f)
            if (sel.isEmpty()) { paragraph("За обраними параметрами даних немає."); save(out); return@apply }
            if (o.summary) {
                heading("1. Зведені показники")
                keyValues(listOf(
                    "Сумарний виробіток" to "${sel.sumOf { it.energyMwh }.f()} МВт·год",
                    "Середній добовий виробіток" to "${(sel.sumOf { it.energyMwh } / o.days).f()} МВт·год",
                    "Середня швидкість вітру" to "${sel.map { it.avgWind }.average().f()} м/с",
                    "Діб зі штормовою зупинкою" to "${sel.filter { it.status == StationStatus.STORM }.map { it.date }.distinct().size}",
                    "Записів у вибірці" to "${sel.size}"))
            }
            progress(0.3f)
            if (o.charts) {
                heading("2. Графіки")
                val byDay = sel.groupBy { it.date }.toSortedMap()
                chart("Добовий виробіток", byDay.keys.map { Dates.short(it) }, listOf(PdfSeries("МВт·год", byDay.values.map { d -> d.sumOf { it.energyMwh } }, GREEN, true)))
                val st = sel.groupBy { it.station }
                chart("Виробіток і вітер за станціями", st.keys.map { it.take(9) }, listOf(
                    PdfSeries("МВт·год / 10", st.values.map { l -> l.sumOf { it.energyMwh } / 10 }, BLUE, true),
                    PdfSeries("Вітер ×100", st.values.map { l -> l.map { it.avgWind }.average() * 100 }, ORANGE, true)))
                pie("Частки станцій", st.mapValues { (_, l) -> l.sumOf { it.energyMwh } }.toList(), PIE)
            }
            progress(0.6f)
            if (o.table) {
                heading("3. Журнал за добами")
                table(listOf(Col("Дата", 1f), Col("Станція", 2f), Col("МВт·год", 1.1f, true), Col("Вітер", 0.8f, true), Col("Стан", 1.4f)),
                    sel.sortedWith(compareBy({ it.date }, { it.station })).map { listOf(Dates.short(it.date), it.station, it.energyMwh.f(), it.avgWind.f(), it.status.title) },
                    highlight = { false })
            }
            progress(0.85f)
            if (o.recommendations) {
                heading("4. Висновки та рекомендації")
                val worst = sel.groupBy { it.station }.minByOrNull { (_, l) -> l.sumOf { it.energyMwh } }?.key
                val storms = sel.count { it.status == StationStatus.STORM }
                bullet(listOf(
                    "Найменший виробіток — «$worst»: перевірити налаштування кута лопатей і стан редукторів.",
                    if (storms > 0) "Зафіксовано $storms станційо-діб штормової зупинки — переглянути графік ТО на періоди затишшя." else "Штормових зупинок не було.",
                    "Рекомендовано порівняти фактичний виробіток з прогнозом за даними метеомачти."))
            }
            heading("Зміст")
            sections.dropLast(1).forEach { (name, p) -> paragraph("$name ........ с. $p") }
            progress(1f)
            save(out)
        }

    /** Домашнє: звіт про стан одного об'єкта — Приазовська ВЕС. */
    fun station(data: List<EnergyRecord>, name: String, out: File): PdfReportBuilder = PdfReportBuilder("Паспорт стану · $name", BLUE).apply {
        val l = data.filter { it.station == name }.sortedBy { it.date }
        val capacity = 200.0
        title("Звіт про стан: $name", "Період ${l.first().date} — ${l.last().date}")
        paragraph("$name — наземна вітрова електростанція встановленою потужністю ${capacity.f(0)} МВт (100 турбін по 2 МВт). " +
            "Нижче наведено показники роботи за ${l.size} діб, стан обладнання та графіки виробітку.")
        heading("Ключові показники")
        val total = l.sumOf { it.energyMwh }
        keyValues(listOf(
            "Виробіток за період" to "${total.f()} МВт·год",
            "Коефіцієнт використання потужності" to "${(total / (capacity * 24 * l.size) * 100).f()} %",
            "Середній вітер" to "${l.map { it.avgWind }.average().f()} м/с",
            "Найкраща доба" to (l.maxByOrNull { it.energyMwh }?.let { "${it.date} · ${it.energyMwh.f()} МВт·год" } ?: "—"),
            "Діб у нормі / обмеження / шторм / ТО" to StationStatus.entries.joinToString(" / ") { s -> "${l.count { it.status == s }}" }))
        heading("Графік виробітку")
        chart("Добовий виробіток і середній вітер ×100", l.map { Dates.short(it.date) }, listOf(
            PdfSeries("МВт·год", l.map { it.energyMwh }, BLUE, true), PdfSeries("Вітер ×100", l.map { it.avgWind * 100 }, ORANGE, false)))
        heading("Показники за добами")
        table(listOf(Col("Дата", 1.2f), Col("МВт·год", 1.2f, true), Col("Вітер, м/с", 1f, true), Col("КВВП, %", 1f, true), Col("Стан", 1.4f)),
            l.map { listOf(it.date, it.energyMwh.f(), it.avgWind.f(), (it.energyMwh / (capacity * 24) * 100).f(), it.status.title) },
            highlight = { l[it].status != StationStatus.NORMAL })
        heading("Пояснення")
        paragraph("КВВП — відношення фактичного виробітку до можливого за роботи на повній потужності протягом доби. " +
            "Для наземних ВЕС України типовий річний КВВП — 30–40 %. Рядки, виділені червоним, — доби з обмеженням, штормовою зупинкою або ТО.")
        save(out)
    }
}

/**
 * Генерація в Dispatchers.Default (малювання) з записом у filesDir/reports,
 * далі — рендер сторінок для попереднього перегляду через PdfRenderer.
 */
open class ReportViewModel(app: Application) : AndroidViewModel(app) {
    val data = SampleData.generate(30)
    val pages = MutableStateFlow<List<Bitmap>>(emptyList())
    val busy = MutableStateFlow(false)
    val progress = MutableStateFlow(0f)
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)
    val file = MutableStateFlow<File?>(null)
    private val dir = File(app.filesDir, "reports")
    private val preview = MediaRepository(app.filesDir, "reports-preview")

    fun generate(name: String, build: (File, (Float) -> Unit) -> PdfReportBuilder) = viewModelScope.launch {
        busy.value = true; progress.value = 0f
        val t0 = System.currentTimeMillis()
        runCatching {
            val out = File(dir, name)
            val b = withContext(Dispatchers.Default) { build(out) { p -> progress.value = p } }
            val bitmaps = withContext(Dispatchers.IO) { (0 until b.pageCount).mapNotNull { preview.renderPdfPage(out, it, 900) } }
            pages.value = bitmaps
            file.value = out
            message.value = "Збережено: reports/$name · ${b.pageCount} стор. · ${out.length() / 1024} КБ · ${System.currentTimeMillis() - t0} мс" to false
        }.onFailure { message.value = "Помилка генерації: ${it.message}" to true }
        busy.value = false
    }
}

fun sharePdf(context: Context, f: File) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Надіслати звіт").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Попередній перегляд сторінок + статус + кнопка «Надіслати». */
@Composable
private fun ReportScreen(vm: ReportViewModel, title: String, subtitle: String, controls: @Composable () -> Unit, onGenerate: () -> Unit) {
    val pages by vm.pages.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val file by vm.file.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            ScreenTitle(title, subtitle)
            controls()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onGenerate, enabled = !busy) { Text("Сформувати PDF") }
                file?.let { f -> OutlinedButton({ sharePdf(context, f) }) { Text("Надіслати") } }
            }
            if (busy) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
            msg?.let { Message(it.first, it.second) }
        }
        itemsIndexed(pages) { i, b ->
            Column {
                Text("Сторінка ${i + 1}", style = MaterialTheme.typography.labelMedium)
                Image(b.asImageBitmap(), "Сторінка ${i + 1}", Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline), contentScale = ContentScale.FillWidth)
            }
        }
    }
}

class BasicPdfViewModel(app: Application) : ReportViewModel(app)
class MediumPdfViewModel(app: Application) : ReportViewModel(app)
class HomeworkPdfViewModel(app: Application) : ReportViewModel(app)
class AdvancedPdfViewModel(app: Application) : ReportViewModel(app) {
    val options = MutableStateFlow(ReportOptions())
}

@Composable
fun BasicPdfApp(vm: BasicPdfViewModel = viewModel()) =
    ReportScreen(vm, "PDF-звіт · добовий", "Текст і таблиця, PdfDocument", {}) {
        vm.generate("daily.pdf") { f, _ -> Reports.basic(vm.data, f) }
    }

@Composable
fun MediumPdfApp(vm: MediumPdfViewModel = viewModel()) =
    ReportScreen(vm, "PDF-звіт · 14 діб", "Графіки, кругова діаграма, динамічні таблиці", {}) {
        vm.generate("period14.pdf") { f, _ -> Reports.medium(vm.data, f) }
    }

@Composable
fun AdvancedPdfApp(vm: AdvancedPdfViewModel = viewModel()) {
    val o by vm.options.collectAsStateWithLifecycle()
    ReportScreen(vm, "Конструктор звіту", "Багатосторінковий PDF із розділами", {
        SectionCard("Параметри") {
            Chips(listOf(7, 14, 30), o.days, { "$it діб" }) { d -> vm.options.update { it.copy(days = d) } }
            MultiChips(SampleData.stations.map { it.first }, o.stations, { it }) { s ->
                vm.options.update { it.copy(stations = if (s in it.stations) it.stations - s else it.stations + s) }
            }
            listOf("Зведені показники" to o.summary, "Графіки" to o.charts, "Журнал за добами" to o.table, "Висновки" to o.recommendations).forEachIndexed { i, (t, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t, Modifier.weight(1f))
                    Switch(v, { on -> vm.options.update { when (i) { 0 -> it.copy(summary = on); 1 -> it.copy(charts = on); 2 -> it.copy(table = on); else -> it.copy(recommendations = on) } } })
                }
            }
            OutlinedTextField(o.author, { a -> vm.options.update { it.copy(author = a) } }, Modifier.fillMaxWidth(), label = { Text("Автор") }, singleLine = true)
            OutlinedTextField(o.comment, { c -> vm.options.update { it.copy(comment = c) } }, Modifier.fillMaxWidth(), label = { Text("Коментар") })
        }
    }) {
        val opts = vm.options.value
        vm.generate("analytics_${opts.days}d.pdf") { f, p -> Reports.advanced(vm.data, opts, f, p) }
    }
}

@Composable
fun HomeworkPdfApp(vm: HomeworkPdfViewModel = viewModel()) {
    val name = "Приазовська"
    val l = vm.data.filter { it.station == name }
    ReportScreen(vm, "Звіт про стан ВЕС", "$name · 30 діб", {
        SectionCard("Дані звіту") {
            InfoRow("Діб у вибірці", "${l.size}")
            InfoRow("Виробіток", "${l.sumOf { it.energyMwh }.f()} МВт·год")
            InfoRow("Діб не в нормі", "${l.count { it.status != StationStatus.NORMAL }}")
        }
    }) { vm.generate("station_report.pdf") { f, _ -> Reports.station(vm.data, name, f) } }
}
