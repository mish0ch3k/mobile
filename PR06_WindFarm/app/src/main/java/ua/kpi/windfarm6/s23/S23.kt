package ua.kpi.windfarm6.s23

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.kpi.windfarm6.media.MediaFile
import ua.kpi.windfarm6.media.MediaRepository
import ua.kpi.windfarm6.media.MediaType
import ua.kpi.windfarm6.ui.common.Chips
import ua.kpi.windfarm6.ui.common.Message
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
private fun size(b: Long) = if (b > 1024 * 1024) "%.1f МБ".format(b / 1048576.0) else "${b / 1024} КБ"

enum class MediaSort(val title: String) { DATE("Дата"), TYPE("Тип"), NAME("Назва"), SIZE("Розмір") }
enum class MediaFilter(val title: String) { ALL("Усі"), PHOTO("Фото"), PDF("PDF"), DOCX("Word") }

/**
 * ViewModel однієї папки медіафайлів: список, імпорт, сортування, фільтр, перейменування.
 */
open class MediaViewModel(app: Application, folder: String) : AndroidViewModel(app) {
    var repo = MediaRepository(app.filesDir, folder)
        protected set
    val files = MutableStateFlow<List<MediaFile>>(emptyList())
    val sort = MutableStateFlow(MediaSort.DATE)
    val filter = MutableStateFlow(MediaFilter.ALL)
    val message = MutableStateFlow<Pair<String, Boolean>?>(null)
    val busy = MutableStateFlow(false)

    val shown: StateFlow<List<MediaFile>> = combine(files, sort, filter) { l, s, f ->
        l.filter {
            when (f) {
                MediaFilter.ALL -> true; MediaFilter.PHOTO -> it.type.isImage
                MediaFilter.PDF -> it.type == MediaType.PDF; MediaFilter.DOCX -> it.type == MediaType.DOCX
            }
        }.let { x ->
            when (s) {
                MediaSort.DATE -> x.sortedByDescending { it.addedAt }
                MediaSort.TYPE -> x.sortedWith(compareBy<MediaFile>({ it.type.ordinal }, { -it.addedAt }))
                MediaSort.NAME -> x.sortedBy { it.title.lowercase() }
                MediaSort.SIZE -> x.sortedByDescending { it.sizeBytes }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { refresh() }

    fun refresh() = viewModelScope.launch { files.value = repo.list() }

    /** Імпорт кількох Uri; кожен файл перевіряється окремо, помилки збираються в одне повідомлення. */
    fun importFiles(uris: List<Uri>, allowed: Set<MediaType>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        busy.value = true
        val cr = getApplication<Application>().contentResolver
        val results = uris.map { repo.importFile(cr, it, allowed) }
        val failed = results.mapNotNull { it.exceptionOrNull()?.message }
        val ok = results.count { it.isSuccess }
        message.value = (if (failed.isEmpty()) "Додано файлів: $ok" else "Додано: $ok; відхилено: ${failed.joinToString("; ")}") to failed.isNotEmpty()
        refresh(); busy.value = false
    }

    fun demoPhotos(n: Int = 3) = viewModelScope.launch {
        busy.value = true
        repeat(n) { i -> repo.createDemoPhoto("Огляд ВТ-0${files.value.size % 5 + 1}", files.value.size + i) ; files.value = repo.list() }
        message.value = "Створено $n фото 2400×1600 → збережено 1600×1067 (JPEG 85 %)" to false
        busy.value = false
    }

    fun demoPdf(title: String) = viewModelScope.launch {
        repo.createDemoPdf(title, listOf("Об'єкт: ${repo.dir.name}", "Дата: ${dateFmt.format(Date())}", "Висновок: дефектів не виявлено",
            "Вібрація башти: 1.2 мм/с (норма до 4.5)", "Знос гальмівних колодок: 18 %"))
        refresh(); message.value = "Створено PDF «$title»" to false
    }

    fun rename(f: MediaFile, title: String) = viewModelScope.launch {
        runCatching { repo.rename(f.id, title) }.onFailure { message.value = (it.message ?: "Помилка") to true }
        refresh()
    }

    fun delete(f: MediaFile) = viewModelScope.launch { repo.delete(f.id); refresh() }
}

/** Мініатюра з асинхронним завантаженням (produceState) — список не «гальмує» під час прокрутки. */
@Composable
fun Thumb(repo: MediaRepository, f: MediaFile, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(null, f.id, repo) { value = repo.thumbnail(f) }
    Box(modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), f.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(if (f.type == MediaType.PDF) Icons.Filled.PictureAsPdf else Icons.Filled.Description, f.type.title, Modifier.size(36.dp))
    }
}

/** Відкрити файл у зовнішньому застосунку через FileProvider (внутрішні файли не мають публічного шляху). */
fun openExternally(context: Context, repo: MediaRepository, f: MediaFile): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", repo.file(f))
    val mime = when (f.type) {
        MediaType.PDF -> "application/pdf"; MediaType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "image/jpeg"
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

/** Перегляд: фото — у повному розмірі, PDF — сторінки через PdfRenderer, DOCX — зовнішній застосунок. */
@Composable
fun Preview(repo: MediaRepository, f: MediaFile, onClose: () -> Unit) {
    val context = LocalContext.current
    var page by remember(f.id) { mutableStateOf(0) }
    val pages = remember(f.id) { if (f.type == MediaType.PDF) repo.pdfPageCount(repo.file(f)) else 0 }
    val bmp by produceState<Bitmap?>(null, f.id, page) {
        value = withContext(Dispatchers.IO) {
            when {
                f.type.isImage -> repo.decodeScaled(repo.file(f), 1200)
                f.type == MediaType.PDF -> repo.renderPdfPage(repo.file(f), page, 1000)
                else -> null
            }
        }
    }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClose) { Text("Закрити") } },
        dismissButton = { TextButton({ if (!openExternally(context, repo, f)) onClose() }) { Text("Відкрити в…") } },
        title = { Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("${f.type.title} · ${size(f.sizeBytes)}" + if (f.width > 0) " · ${f.width}×${f.height}" else "", style = MaterialTheme.typography.labelMedium)
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), f.title, Modifier.fillMaxWidth().padding(top = 8.dp), contentScale = ContentScale.FillWidth)
                else if (f.type == MediaType.DOCX) Text("Попередній перегляд DOCX недоступний — натисніть «Відкрити в…»", Modifier.padding(top = 8.dp))
                if (pages > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ if (page > 0) page-- }) { Text("◀") }
                    Text("Сторінка ${page + 1} з $pages")
                    TextButton({ if (page < pages - 1) page++ }) { Text("▶") }
                }
            }
        })
}

@Composable
private fun SortFilter(vm: MediaViewModel, withFilter: Boolean) {
    val sort by vm.sort.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    if (withFilter) Chips(MediaFilter.entries, filter, { it.title }) { vm.filter.value = it }
    Chips(MediaSort.entries, sort, { "↕ ${it.title}" }) { vm.sort.value = it }
}

@Composable
private fun Status(vm: MediaViewModel) {
    val msg by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    if (busy) Text("Обробка…", style = MaterialTheme.typography.labelMedium)
    msg?.let { Message(it.first, it.second) }
}

// ============================ БАЗОВИЙ ============================
class BasicPhotoViewModel(app: Application) : MediaViewModel(app, "photos")

/** Галерея фото: Photo Picker (без дозволів), зменшення до 1600 px, сітка мініатюр. */
@Composable
fun BasicPhotoApp(vm: BasicPhotoViewModel = viewModel()) {
    val files by vm.shown.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MediaFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.importFiles(it, setOf(MediaType.JPEG, MediaType.PNG)) }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ScreenTitle("Фото енергооб'єктів", "${files.size} фото · ${size(files.sumOf { it.sizeBytes })}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("З галереї") }
                    OutlinedButton({ vm.demoPhotos(3) }) { Text("Демо-фото") }
                }
                Status(vm)
            }
        }
        items(files, key = { it.id }) { f -> Thumb(vm.repo, f, Modifier.aspectRatio(1f).clickable { open = f }) }
    }
    open?.let { Preview(vm.repo, it) { open = null } }
}

// ============================ СЕРЕДНІЙ ============================
class MediumDocsViewModel(app: Application) : MediaViewModel(app, "documents")

@Composable
private fun FileRow(vm: MediaViewModel, f: MediaFile, onOpen: () -> Unit, onRename: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Thumb(vm.repo, f, Modifier.size(56.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text("${f.type.title} · ${size(f.sizeBytes)} · ${dateFmt.format(Date(f.addedAt))}", style = MaterialTheme.typography.labelSmall)
        }
        if (onRename != null) IconButton(onRename) { Icon(Icons.Filled.Edit, "Перейменувати") }
        IconButton({ vm.delete(f) }) { Icon(Icons.Filled.Delete, "Видалити") }
    }
}

/** Документи PDF / DOCX і фото: SAF-вибір, перегляд PDF у застосунку, фільтр і сортування. */
@Composable
fun MediumDocsApp(vm: MediumDocsViewModel = viewModel()) {
    val files by vm.shown.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MediaFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.importFiles(it, setOf(MediaType.PDF, MediaType.DOCX, MediaType.JPEG, MediaType.PNG))
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            ScreenTitle("Документи ВЕС", "PDF, Word і фото")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ picker.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*")) }) { Text("Додати файли") }
                OutlinedButton({ vm.demoPdf("Акт огляду ВТ-0${files.size % 5 + 1}") }) { Text("Демо PDF") }
                OutlinedButton({ vm.demoPhotos(1) }) { Text("Фото") }
            }
            Status(vm)
            SortFilter(vm, withFilter = true)
            if (files.isEmpty()) Text("Немає файлів за фільтром", style = MaterialTheme.typography.bodySmall)
        }
        items(files, key = { it.id }) { f -> FileRow(vm, f, { open = f }) }
    }
    open?.let { Preview(vm.repo, it) { open = null } }
}

// ============================ ПРОСУНУТИЙ ============================
val OBJECTS = listOf("ВТ-01", "ВТ-02", "ВТ-03", "Підстанція", "Метеомачта")

/** Файли обраного об'єкта: окрема папка на об'єкт, перейменування, галерея/список, кеш мініатюр. */
class AdvancedMediaViewModel(app: Application) : MediaViewModel(app, "objects/${OBJECTS.first()}") {
    val obj = MutableStateFlow(OBJECTS.first())
    val grid = MutableStateFlow(true)
    fun select(o: String) { obj.value = o; repo = MediaRepository(getApplication<Application>().filesDir, "objects/$o"); refresh() }
    fun cacheInfo() = "Кеш мініатюр: ${MediaRepository.memoryCache.size()} / ${MediaRepository.memoryCache.maxSize()} КБ"
}

@Composable
fun AdvancedMediaApp(vm: AdvancedMediaViewModel = viewModel()) {
    val files by vm.shown.collectAsStateWithLifecycle()
    val obj by vm.obj.collectAsStateWithLifecycle()
    val grid by vm.grid.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MediaFile?>(null) }
    var renaming by remember { mutableStateOf<MediaFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        vm.importFiles(it, setOf(MediaType.PDF, MediaType.DOCX, MediaType.JPEG, MediaType.PNG))
    }
    val header: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenTitle("Медіа об'єкта")
            Chips(OBJECTS, obj, { it }, vm::select)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ picker.launch(arrayOf("*/*")) }) { Text("Додати") }
                OutlinedButton({ vm.demoPhotos(2) }) { Text("Фото") }
                OutlinedButton({ vm.demoPdf("Паспорт $obj") }) { Text("PDF") }
                OutlinedButton({ vm.grid.value = !grid }) { Text(if (grid) "Список" else "Сітка") }
            }
            Status(vm)
            SortFilter(vm, withFilter = true)
            Text("${files.size} файлів · ${vm.cacheInfo()}", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (grid) LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { header() }
        items(files, key = { it.id }) { f ->
            Column(Modifier.clickable { open = f }) {
                Thumb(vm.repo, f, Modifier.fillMaxWidth().aspectRatio(1f))
                Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable { renaming = f })
            }
        }
    } else LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
        item { header() }
        items(files, key = { it.id }) { f -> FileRow(vm, f, { open = f }, { renaming = f }) }
    }
    open?.let { Preview(vm.repo, it) { open = null } }
    renaming?.let { f -> RenameDialog(f, onDone = { t -> vm.rename(f, t); renaming = null }, onCancel = { renaming = null }) }
}

@Composable
fun RenameDialog(f: MediaFile, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable(f.id) { mutableStateOf(f.title) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Перейменувати") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, isError = text.isBlank(), supportingText = { if (text.isBlank()) Text("Назва не може бути порожньою") }) },
        confirmButton = { TextButton({ onDone(text) }, enabled = text.isNotBlank()) { Text("Зберегти") } },
        dismissButton = { TextButton(onCancel) { Text("Скасувати") } })
}

// ============================ ДОМАШНЄ ============================
/** Документація турбіни ВТ-03: фото і документи з перевіркою типу за сигнатурою файлу. */
class HomeworkMediaViewModel(app: Application) : MediaViewModel(app, "turbine-vt03")

@Composable
fun HomeworkMediaApp(vm: HomeworkMediaViewModel = viewModel()) {
    val files by vm.shown.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MediaFile?>(null) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { vm.importFiles(it, setOf(MediaType.JPEG, MediaType.PNG)) }
    val docs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importFiles(it, setOf(MediaType.PDF, MediaType.DOCX)) }
    val images = files.filter { it.type.isImage }
    val documents = files.filterNot { it.type.isImage }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            ScreenTitle("Турбіна ВТ-03", "Фото огляду та технічна документація")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button({ photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Фото") }
                Button({ docs.launch(arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) { Text("Документ") }
                OutlinedButton({ vm.demoPhotos(2); vm.demoPdf("Акт ТО ВТ-03") }) { Text("Демо") }
            }
            Status(vm)
            Text("Тип перевіряється за вмістом: JPEG (FF D8 FF), PNG (89 50 4E 47), PDF (%PDF), DOCX (ZIP з word/).", style = MaterialTheme.typography.bodySmall)
            SortFilter(vm, withFilter = false)
            SectionCard("Фото (${images.size})") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    images.take(4).forEach { f -> Thumb(vm.repo, f, Modifier.weight(1f).aspectRatio(1f).clickable { open = f }) }
                    repeat(4 - minOf(4, images.size)) { Box(Modifier.weight(1f)) }
                }
                if (images.size > 4) Text("ще ${images.size - 4}…", style = MaterialTheme.typography.labelSmall)
            }
            Text("Документи (${documents.size})", style = MaterialTheme.typography.titleSmall)
        }
        items(documents, key = { it.id }) { f -> FileRow(vm, f, { open = f }) }
    }
    open?.let { Preview(vm.repo, it) { open = null } }
}
