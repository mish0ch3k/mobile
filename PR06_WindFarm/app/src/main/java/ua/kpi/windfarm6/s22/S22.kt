package ua.kpi.windfarm6.s22

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.kpi.windfarm6.data.Dates
import ua.kpi.windfarm6.data.EnergyRecord
import ua.kpi.windfarm6.data.FileFormat
import ua.kpi.windfarm6.data.ReadResult
import ua.kpi.windfarm6.data.RecordFileStore
import ua.kpi.windfarm6.data.RecordValidator
import ua.kpi.windfarm6.data.SampleData
import ua.kpi.windfarm6.data.StationStatus
import ua.kpi.windfarm6.data.StationType
import ua.kpi.windfarm6.ui.common.Chart
import ua.kpi.windfarm6.ui.common.ChartSeries
import ua.kpi.windfarm6.ui.common.Chips
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.Message
import ua.kpi.windfarm6.ui.common.MultiChips
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import ua.kpi.windfarm6.ui.common.fmt

// ============================ СПІЛЬНЕ ============================

/**
 * Базова ViewModel роботи з файлом: стан списку, повідомлення, помилки читання.
 * Кожна операція з файлом — у корутині viewModelScope (IO всередині RecordFileStore).
 */
open class FileViewModel(app: Application, baseName: String, val format: FileFormat) : AndroidViewModel(app) {
    protected val store = RecordFileStore(app.filesDir, baseName)
    val records = MutableStateFlow<List<EnergyRecord>>(emptyList())
    val readErrors = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<Pair<String, Boolean>?>(null) // текст, чи це помилка
    val fileInfo = MutableStateFlow("")

    init { reload() }

    fun reload() = viewModelScope.launch { applyResult(store.read(format)) }

    protected fun applyResult(r: ReadResult) {
        records.value = r.records
        readErrors.value = r.errors
        fileInfo.value = store.info(format)
        if (r.missing) message.value = "Файлу ще немає — додайте перший запис" to false
    }

    /** Додати запис: перевірка → збереження → повторне читання з диска (UI показує те, що реально у файлі). */
    open fun add(r: EnergyRecord) = viewModelScope.launch {
        val problems = RecordValidator.validate(r)
        if (problems.isNotEmpty()) { message.value = problems.joinToString("; ") to true; return@launch }
        runCatching {
            if (format == FileFormat.CSV) store.appendCsv(r) else store.write(format, records.value + r)
        }.onSuccess { message.value = "Збережено у ${store.file(format).name}" to false }
            .onFailure { message.value = "Помилка запису: ${it.message}" to true }
        applyResult(store.read(format))
    }

    fun nextId() = (records.value.maxOfOrNull { it.id } ?: 0) + 1

    fun clear() = viewModelScope.launch { store.delete(format); applyResult(store.read(format)); message.value = "Файл видалено" to false }

    fun corrupt() = viewModelScope.launch {
        store.corrupt(format); applyResult(store.read(format))
        message.value = "У файл дописано некоректні дані — див. помилки читання" to true
    }

    fun fillSample(days: Int = 30) = viewModelScope.launch {
        store.write(format, SampleData.generate(days)); applyResult(store.read(format))
        message.value = "Записано демонстраційні дані за $days діб" to false
    }
}

/**
 * Форма запису без клавіатури: станція — чипи, дата — кнопки ±, виробіток і вітер — повзунки.
 * Так неможливо ввести «abc» замість числа, а валідація перевіряє діапазони.
 */
@Composable
fun RecordForm(nextId: Long, withStatus: Boolean, onSave: (EnergyRecord) -> Unit) {
    var station by rememberSaveable { mutableStateOf(SampleData.stations.first().first) }
    var date by rememberSaveable { mutableStateOf(Dates.today()) }
    var energy by rememberSaveable { mutableDoubleStateOf(1500.0) }
    var wind by rememberSaveable { mutableDoubleStateOf(9.0) }
    var status by rememberSaveable { mutableStateOf(StationStatus.NORMAL) }
    SectionCard("Новий запис") {
        Chips(SampleData.stations.map { it.first }, station, { it }) { station = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ date = Dates.plusDays(date, -1) }) { Text("◀") }
            Text("Дата: $date", Modifier.weight(1f))
            TextButton({ date = Dates.plusDays(date, 1) }) { Text("▶") }
        }
        Text("Виробіток: ${energy.fmt(0)} МВт·год", style = MaterialTheme.typography.bodySmall)
        Slider(energy.toFloat(), { energy = Math.round(it / 10.0) * 10.0 }, valueRange = 0f..5000f)
        Text("Середній вітер: ${wind.fmt()} м/с", style = MaterialTheme.typography.bodySmall)
        Slider(wind.toFloat(), { wind = Math.round(it * 10) / 10.0 }, valueRange = 0f..30f)
        if (withStatus) Chips(StationStatus.entries, status, { it.title }) { status = it }
        Button({
            val type = SampleData.stations.first { it.first == station }.second
            onSave(EnergyRecord(nextId, station, type, date, energy, wind, if (withStatus) status else StationStatus.NORMAL))
        }, Modifier.fillMaxWidth()) { Text("Зберегти") }
    }
}

@Composable
private fun FileStatus(vm: FileViewModel) {
    val msg by vm.message.collectAsStateWithLifecycle()
    val errors by vm.readErrors.collectAsStateWithLifecycle()
    val info by vm.fileInfo.collectAsStateWithLifecycle()
    Text(info, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
    msg?.let { Message(it.first, it.second) }
    if (errors.isNotEmpty()) SectionCard("Пропущено під час читання: ${errors.size}") {
        errors.take(5).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RecordRow(r: EnergyRecord) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(Dates.short(r.date), Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
        Text(r.station, Modifier.weight(1.8f), style = MaterialTheme.typography.bodySmall)
        Text("${r.energyMwh.fmt(0)}", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall)
        Text("${r.avgWind.fmt()}", Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall)
        Text(r.status.title, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall,
            color = if (r.status == StationStatus.NORMAL) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TableHeader() {
    Row(Modifier.fillMaxWidth()) {
        listOf("Дата" to 0.8f, "Станція" to 1.8f, "МВт·год" to 0.9f, "м/с" to 0.6f, "Стан" to 1.3f).forEach { (t, w) ->
            Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium)
        }
    }
    HorizontalDivider()
}

// ============================ БАЗОВИЙ ============================
class BasicCsvViewModel(app: Application) : FileViewModel(app, "basic", FileFormat.CSV)

@Composable
fun BasicCsvApp(vm: BasicCsvViewModel = viewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Журнал виробітку · CSV", "Внутрішнє сховище застосунку")
            RecordForm(vm.nextId(), withStatus = false) { vm.add(it) }
            FileStatus(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.reload() }) { Text("Прочитати") }
                OutlinedButton({ vm.corrupt() }) { Text("Зіпсувати") }
                OutlinedButton({ vm.clear() }) { Text("Видалити") }
            }
            Text("Записів: ${records.size}", style = MaterialTheme.typography.titleSmall)
            TableHeader()
        }
        items(records.asReversed(), key = { it.id }) { RecordRow(it) }
    }
}

// ============================ СЕРЕДНІЙ ============================
class MediumJsonViewModel(app: Application) : FileViewModel(app, "history", FileFormat.JSON) {
    /** Вміст файлу як текст — показуємо структуру JSON. */
    val raw = MutableStateFlow("")
    fun loadRaw() = viewModelScope.launch {
        raw.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            store.file(format).takeIf { it.exists() }?.readText()?.take(900) ?: "—"
        }
    }
}

@Composable
fun MediumJsonApp(vm: MediumJsonViewModel = viewModel()) {
    val records by vm.records.collectAsStateWithLifecycle()
    val raw by vm.raw.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Історія станцій · JSON", "kotlinx.serialization")
            RecordForm(vm.nextId(), withStatus = true) { vm.add(it) }
            FileStatus(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ vm.fillSample(7) }) { Text("Демо 7 діб") }
                OutlinedButton({ vm.loadRaw() }) { Text("Вміст") }
                OutlinedButton({ vm.corrupt() }) { Text("Зіпсувати") }
            }
            if (raw.isNotEmpty()) SectionCard("Початок файлу") {
                Text(raw, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            Text("Записів: ${records.size}", style = MaterialTheme.typography.titleSmall)
            TableHeader()
        }
        items(records.asReversed(), key = { it.id }) { RecordRow(it) }
    }
}

// ============================ ПРОСУНУТИЙ ============================
enum class Period(val title: String, val days: Int?) { WEEK("7 діб", 7), TWO("14 діб", 14), MONTH("30 діб", 30), ALL("Усі", null) }

/**
 * Аналітика з файлу: джерело CSV або JSON, період, типи станцій.
 * Фільтри застосовуються до завантажених записів через combine — UI оновлюється сам.
 */
class AdvancedFileViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RecordFileStore(app.filesDir, "analytics")
    val source = MutableStateFlow(FileFormat.CSV)
    val period = MutableStateFlow(Period.WEEK)
    val types = MutableStateFlow<Set<StationType>>(emptySet())
    private val loaded = MutableStateFlow(ReadResult(emptyList()))
    val status = MutableStateFlow("")

    init { viewModelScope.launch { prepare(); load() } }

    /** Якщо файлів ще немає — створюємо обидва з однаковими даними, щоб порівняти формати. */
    private suspend fun prepare() {
        if (!store.file(FileFormat.CSV).exists() || !store.file(FileFormat.JSON).exists()) {
            val data = SampleData.generate(45)
            store.write(FileFormat.CSV, data); store.write(FileFormat.JSON, data)
        }
    }

    fun setSource(f: FileFormat) { source.value = f; viewModelScope.launch { load() } }

    private suspend fun load() {
        val t0 = System.nanoTime()
        loaded.value = store.read(source.value)
        status.value = "${store.info(source.value)} · прочитано за ${(System.nanoTime() - t0) / 1_000_000} мс"
    }

    val filtered: StateFlow<List<EnergyRecord>> = combine(loaded, period, types) { l, p, t ->
        val from = p.days?.let { Dates.plusDays(Dates.today(), -(it - 1)) } ?: ""
        l.records.filter { it.date >= from && (t.isEmpty() || it.type in t) }.sortedBy { it.date }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val errors: StateFlow<List<String>> = combine(loaded, source) { l, _ -> l.errors }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}

@Composable
fun AdvancedFileApp(vm: AdvancedFileViewModel = viewModel()) {
    val source by vm.source.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val data by vm.filtered.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    var selDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var selStation by rememberSaveable { mutableStateOf<Int?>(null) }

    val byDay = data.groupBy { it.date }.toSortedMap()
    val daily = byDay.values.map { d -> d.sumOf { it.energyMwh } }
    val windAvg = byDay.values.map { d -> d.map { it.avgWind }.average() * 100 } // ×100 — на одну вісь з МВт·год
    val byStation = data.groupBy { it.station }.mapValues { (_, l) -> l.sumOf { it.energyMwh } }.toList().sortedByDescending { it.second }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Аналітика з локальних файлів")
            Chips(FileFormat.entries, source, { "Джерело: ${it.title}" }, vm::setSource)
            Text(status, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            errors.firstOrNull()?.let { Message(it, true) }
            Chips(Period.entries, period, { it.title }) { vm.period.value = it; selDay = null }
            MultiChips(StationType.entries, types, { it.title }) { t -> vm.types.value = if (t in types) types - t else types + t; selDay = null; selStation = null }
            SectionCard("Виробіток по добах, МВт·год") {
                Chart(listOf(ChartSeries(daily, Color(0xFF1565C0), true, "МВт·год"), ChartSeries(windAvg, Color(0xFF2E7D32), false, "Вітер ×100")),
                    byDay.keys.map { Dates.short(it) }, Modifier.fillMaxWidth().height(220.dp), selDay, { selDay = it },
                    tooltip = { i -> "Виробіток: ${daily[i].fmt(0)} МВт·год\nСер. вітер: ${(windAvg[i] / 100).fmt()} м/с" })
            }
            SectionCard("За станціями") {
                Chart(listOf(ChartSeries(byStation.map { it.second }, Color(0xFFEF6C00), true, "МВт·год")),
                    byStation.map { it.first.take(7) }, Modifier.fillMaxWidth().height(180.dp), selStation, { selStation = it },
                    tooltip = { i -> "${byStation[i].first}\n${byStation[i].second.fmt(0)} МВт·год" })
            }
            SectionCard("Підсумок") {
                InfoRow("Записів", "${data.size}")
                InfoRow("Сумарний виробіток", "${data.sumOf { it.energyMwh }.fmt(0)} МВт·год")
                InfoRow("Найкраща доба", byDay.maxByOrNull { (_, l) -> l.sumOf { it.energyMwh } }?.key?.let(Dates::short) ?: "—")
                InfoRow("Штормових зупинок", "${data.count { it.status == StationStatus.STORM }}")
            }
        }
    }
}

// ============================ ДОМАШНЄ ============================
enum class SortBy(val title: String) { DATE("Дата"), ENERGY("Виробіток"), TYPE("Тип"), STATION("Станція") }

/** Журнал ВЕС: запис у CSV і JSON одночасно, читання з вибраного формату, сортування. */
class HomeworkFileViewModel(app: Application) : FileViewModel(app, "homework", FileFormat.CSV) {
    private val json = RecordFileStore(app.filesDir, "homework")
    val sort = MutableStateFlow(SortBy.DATE)
    val descending = MutableStateFlow(true)
    val readFrom = MutableStateFlow(FileFormat.CSV)

    val sorted: StateFlow<List<EnergyRecord>> = combine(records, sort, descending) { l, s, d ->
        val c: Comparator<EnergyRecord> = when (s) {
            SortBy.DATE -> compareBy<EnergyRecord>({ it.date }, { it.station })
            SortBy.ENERGY -> compareBy<EnergyRecord> { it.energyMwh }
            SortBy.TYPE -> compareBy<EnergyRecord>({ it.type.ordinal }, { it.station })
            SortBy.STATION -> compareBy<EnergyRecord>({ it.station }, { it.date })
        }
        if (d) l.sortedWith(c.reversed()) else l.sortedWith(c)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Запис іде в обидва файли: CSV — для Excel, JSON — для обміну з сервером. */
    override fun add(r: EnergyRecord) = viewModelScope.launch {
        val problems = RecordValidator.validate(r)
        if (problems.isNotEmpty()) { message.value = problems.joinToString("; ") to true; return@launch }
        val all = records.value + r
        runCatching { store.write(FileFormat.CSV, all); json.write(FileFormat.JSON, all) }
            .onSuccess { message.value = "Збережено в homework.csv і homework.json" to false }
            .onFailure { message.value = "Помилка запису: ${it.message}" to true }
        read(readFrom.value)
    }

    fun read(f: FileFormat) = viewModelScope.launch {
        readFrom.value = f
        val r = (if (f == FileFormat.CSV) store else json).read(f)
        applyResult(r)
        fileInfo.value = "${store.info(FileFormat.CSV)} · ${json.info(FileFormat.JSON)}"
    }

    fun importSample() = viewModelScope.launch {
        val data = SampleData.generate(10)
        store.write(FileFormat.CSV, data); json.write(FileFormat.JSON, data)
        read(readFrom.value)
        message.value = "Імпортовано ${data.size} записів" to false
    }
}

@Composable
fun HomeworkFileApp(vm: HomeworkFileViewModel = viewModel()) {
    val list by vm.sorted.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val desc by vm.descending.collectAsStateWithLifecycle()
    val from by vm.readFrom.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Журнал ВЕС · CSV + JSON")
            RecordForm(vm.nextId(), withStatus = true) { vm.add(it) }
            FileStatus(vm)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton({ vm.importSample() }) { Text("Імпорт демо") }
                OutlinedButton({ vm.corrupt() }) { Text("Зіпсувати CSV") }
            }
            Chips(FileFormat.entries, from, { "Читати ${it.title}" }) { vm.read(it) }
            Chips(SortBy.entries, sort, { it.title }) { vm.sort.value = it }
            TextButton({ vm.descending.value = !desc }) { Text(if (desc) "↓ за спаданням" else "↑ за зростанням") }
            Text("Записів: ${list.size}", style = MaterialTheme.typography.titleSmall)
            TableHeader()
        }
        items(list, key = { it.id }) { RecordRow(it) }
    }
}
