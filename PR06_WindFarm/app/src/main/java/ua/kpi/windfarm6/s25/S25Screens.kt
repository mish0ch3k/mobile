package ua.kpi.windfarm6.s25

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.kpi.windfarm6.charts.AxisScale
import ua.kpi.windfarm6.data.CsvCodec
import ua.kpi.windfarm6.data.Dates
import ua.kpi.windfarm6.data.EnergyRecord
import ua.kpi.windfarm6.data.RecordValidator
import ua.kpi.windfarm6.data.StationType
import ua.kpi.windfarm6.domain.TurbineSpec
import ua.kpi.windfarm6.domain.WindPowerCalculator
import ua.kpi.windfarm6.media.FileSignature
import ua.kpi.windfarm6.media.MediaType
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.Message
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import ua.kpi.windfarm6.ui.common.fmt
import kotlin.random.Random

/** Теги для пошуку елементів у UI-тестах (androidTest). */
object Tags {
    const val WIND = "wind_input"; const val COUNT = "count_input"; const val CALC = "calc_button"
    const val RESULT = "result"; const val WIND_ERROR = "wind_error"
    const val USER = "user"; const val PASSWORD = "password"; const val LOGIN = "login_button"; const val LOGIN_ERROR = "login_error"
    const val STATION_LIST = "station_list"; const val DETAILS = "details"
}

// ============================ БАЗОВИЙ ============================
/** Перевірка в застосунку: ті самі сценарії, що в юніт-тестах допоміжних функцій. */
data class Check(val area: String, val case: String, val expected: String, val actual: String) { val ok get() = expected == actual }

fun runChecks(): List<Check> {
    val spec = TurbineSpec()
    fun r(id: Long = 1, wind: Double = 9.0, energy: Double = 100.0, date: String = "2026-11-01") = EnergyRecord(id, "ВЕС", StationType.ONSHORE, date, energy, wind)
    return listOf(
        Check("CSV", "escape(\"a,b\")", "\"a,b\"", CsvCodec.escape("a,b")),
        Check("CSV", "escape з лапками", "\"x \"\"y\"\"\"", CsvCodec.escape("x \"y\"")),
        Check("CSV", "split з лапками", "[a, b,c, d]", CsvCodec.splitLine("a,\"b,c\",d").toString()),
        Check("CSV", "encode→decode", "1", CsvCodec.decode(CsvCodec.encode(listOf(r()))).records.size.toString()),
        Check("Дати", "2026-02-28 + 1", "2026-03-01", Dates.plusDays("2026-02-28", 1)),
        Check("Дати", "2028-02-28 + 1 (високосний)", "2028-02-29", Dates.plusDays("2028-02-28", 1)),
        Check("Дати", "некоректна дата", "null", Dates.parse("2026-13-40").toString()),
        Check("Валідація", "вітер 70 м/с", "1", RecordValidator.validate(r(wind = 70.0)).size.toString()),
        Check("Валідація", "від'ємний виробіток", "1", RecordValidator.validate(r(energy = -1.0)).size.toString()),
        Check("Файли", "сигнатура PDF", "PDF", FileSignature.detect("%PDF-1.7".toByteArray()).name),
        Check("Файли", "сигнатура JPEG", "JPEG", FileSignature.detect(byteArrayOf(-1, -40, -1, -32)).name),
        Check("Файли", "порожній файл", MediaType.UNKNOWN.name, FileSignature.detect(ByteArray(0)).name),
        Check("Фото", "inSampleSize 4000→1600", "2", FileSignature.sampleSize(4000, 3000, 1600).toString()),
        Check("Графіки", "поділки 0..113", "[0.0, 50.0, 100.0, 150.0]", AxisScale.ticks(0.0, 113.0, 4).toString()),
        Check("Турбіна", "P(8 м/с), кВт", "838", WindPowerCalculator.turbinePowerKw(8.0, spec).fmt(0)),
        Check("Турбіна", "P(30 м/с) — зупинка", "0", WindPowerCalculator.turbinePowerKw(30.0, spec).fmt(0)),
        Check("Калькулятор", "вітер «abc»", "Потрібне число", StationCalculator.parseWind("abc").second.toString()),
        Check("Калькулятор", "турбін 0", "Від 1 до 500", StationCalculator.parseCount("0").second.toString())
    )
}

class ChecksViewModel : ViewModel() {
    val results = MutableStateFlow<List<Check>>(emptyList())
    val time = MutableStateFlow(0L)
    fun run() = viewModelScope.launch {
        val t0 = System.nanoTime()
        results.value = withContext(Dispatchers.Default) { runChecks() }
        time.value = (System.nanoTime() - t0) / 1000
    }
}

@Composable
fun BasicTestApp(vm: ChecksViewModel = viewModel()) {
    val res by vm.results.collectAsStateWithLifecycle()
    val time by vm.time.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            ScreenTitle("Самоперевірка допоміжних функцій", "Ті самі сценарії — у src/test (JUnit)")
            Button({ vm.run() }) { Text("Запустити ${runChecks().size} перевірок") }
            if (res.isNotEmpty()) Message("Пройдено ${res.count { it.ok }} з ${res.size} за $time мкс", res.any { !it.ok })
        }
        items(res) { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(if (c.ok) "✓" else "✗", color = if (c.ok) Color(0xFF2E7D32) else Color(0xFFC62828), modifier = Modifier.padding(end = 8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${c.area}: ${c.case}", style = MaterialTheme.typography.bodyMedium)
                    Text("очікується ${c.expected} · отримано ${c.actual}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
            HorizontalDivider()
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
/**
 * Міні-застосунок «вхід → список станцій → деталі» з навігацією.
 * Репозиторій передається параметром — UI-тест підставляє FakeStationRepository.
 */
@Composable
fun MediumTestApp(repo: StationRepository = ServiceLocator.stations) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "login") {
        composable("login") { LoginScreen(repo) { nav.navigate("list") { popUpTo("login") { inclusive = true } } } }
        composable("list") { StationListScreen(repo) { id -> nav.navigate("details/$id") } }
        composable("details/{id}", listOf(navArgument("id") { type = NavType.IntType })) { e ->
            StationDetailsScreen(repo, e.arguments?.getInt("id") ?: -1) { nav.popBackStack() }
        }
    }
}

@Composable
fun LoginScreen(repo: StationRepository, onSuccess: () -> Unit) {
    var user by rememberSaveable { mutableStateOf("operator") }
    var pass by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Вхід оператора", "Демо: operator / wind2026")
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth().testTag(Tags.USER), label = { Text("Логін") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth().testTag(Tags.PASSWORD), label = { Text("Пароль") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button({
            if (user.isBlank() || pass.isBlank()) { error = "Заповніть логін і пароль"; return@Button }
            busy = true; error = null
            scope.launch { val ok = repo.login(user, pass); busy = false; if (ok) onSuccess() else error = "Невірний логін або пароль" }
        }, Modifier.fillMaxWidth().testTag(Tags.LOGIN), enabled = !busy) { Text(if (busy) "Перевірка…" else "Увійти") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.LOGIN_ERROR)) }
    }
}

@Composable
fun StationListScreen(repo: StationRepository, onOpen: (Int) -> Unit) {
    val list by androidx.compose.runtime.produceState<List<Station>?>(null, repo) { value = repo.stations() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Станції")
        val l = list
        if (l == null) CircularProgressIndicator()
        else LazyColumn(Modifier.testTag(Tags.STATION_LIST)) {
            items(l, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(vertical = 10.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    Text("${s.capacityMw.fmt(0)} МВт · вітер ${s.windMs.fmt()} м/с", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun StationDetailsScreen(repo: StationRepository, id: Int, onBack: () -> Unit) {
    val s by androidx.compose.runtime.produceState<Station?>(null, id) { value = repo.stations().firstOrNull { it.id == id } }
    Column(Modifier.fillMaxSize().padding(16.dp).testTag(Tags.DETAILS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val st = s
        if (st == null) { CircularProgressIndicator(); return@Column }
        ScreenTitle(st.name)
        val kw = WindPowerCalculator.turbinePowerKw(st.windMs, TurbineSpec())
        InfoRow("Встановлена потужність", "${st.capacityMw.fmt(0)} МВт")
        InfoRow("Вітер", "${st.windMs.fmt()} м/с")
        InfoRow("Поточна потужність", "${(kw / 2000 * st.capacityMw).fmt(1)} МВт")
        OutlinedButton(onBack) { Text("← До списку") }
    }
}

// ============================ ПРОСУНУТИЙ ============================
class LoaderViewModel : ViewModel() {
    val state = MutableStateFlow<LoadState>(LoadState.Idle)
    val failRate = MutableStateFlow(0.5f)
    val badData = MutableStateFlow(false)
    val slow = MutableStateFlow(false)
    val log = MutableStateFlow<List<String>>(emptyList())
    private val rnd = Random(25)

    fun load() = viewModelScope.launch {
        state.value = LoadState.Loading
        val source: Source = {
            delay(if (slow.value) 2500 else 250)
            if (rnd.nextFloat() < failRate.value) throw java.io.IOException("HTTP 503").also { addLog("спроба: збій 503") }
            addLog("спроба: успіх")
            listOf(Station(1, "Приазовська", if (badData.value) 0.0 else 200.0, 9.4), Station(2, "Тилігульська", 76.0, 8.1))
        }
        state.value = StationLoader(source, timeoutMs = 5_000).load()
        addLog("результат: ${state.value::class.simpleName}")
    }

    private fun addLog(s: String) { log.value = (log.value + s).takeLast(8) }
}

@Composable
fun AdvancedTestApp(vm: LoaderViewModel = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val rate by vm.failRate.collectAsStateWithLifecycle()
    val bad by vm.badData.collectAsStateWithLifecycle()
    val slow by vm.slow.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Асинхронне завантаження", "Повтори, тайм-аут, некоректні дані — граничні умови")
        SectionCard("Умови сервера") {
            Text("Імовірність збою: ${(rate * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
            Slider(rate, { vm.failRate.value = it })
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(slow, { vm.slow.value = it }); Text("  Повільний сервер (2,5 с)") }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(bad, { vm.badData.value = it }); Text("  Некоректні дані") }
        }
        Button({ vm.load() }, enabled = st !is LoadState.Loading) { Text("Завантажити") }
        when (val s = st) {
            LoadState.Idle -> Text("Натисніть «Завантажити»")
            LoadState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Text("  Завантаження з повторами…") }
            is LoadState.Success -> SectionCard("Успіх") {
                InfoRow("Спроб", "${s.attempts}"); InfoRow("Час", "${s.ms} мс")
                s.stations.forEach { InfoRow(it.name, "${it.capacityMw.fmt(0)} МВт") }
            }
            is LoadState.Error -> Message("${s.message} (спроб: ${s.attempts})", true)
        }
        if (log.isNotEmpty()) SectionCard("Журнал") { log.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
        Text("Автотести: ./gradlew testFullDebugUnitTest connectedFullDebugAndroidTest; покриття: createFullDebugUnitTestCoverageReport.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ДОМАШНЄ ============================
/** Ключовий екран для UI-тесту: калькулятор потужності станції. */
@Composable
fun HomeworkTestApp() {
    var wind by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("10") }
    var result by remember { mutableStateOf<CalcResult?>(null) }
    val invalid = result as? CalcResult.Invalid
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Калькулятор станції", "Турбіни 2 МВт, Cp = 0,42")
        OutlinedTextField(wind, { wind = it }, Modifier.fillMaxWidth().testTag(Tags.WIND), label = { Text("Швидкість вітру, м/с") }, singleLine = true,
            isError = invalid?.windError != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            supportingText = { invalid?.windError?.let { Text(it, Modifier.testTag(Tags.WIND_ERROR)) } })
        OutlinedTextField(count, { count = it }, Modifier.fillMaxWidth().testTag(Tags.COUNT), label = { Text("Кількість турбін") }, singleLine = true,
            isError = invalid?.countError != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { invalid?.countError?.let { Text(it) } })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ result = StationCalculator.calculate(wind, count) }, Modifier.testTag(Tags.CALC)) { Text("Розрахувати") }
            listOf("4", "9.5", "14", "26").forEach { v -> TextButton({ wind = v; result = StationCalculator.calculate(v, count) }) { Text(v) } }
        }
        (result as? CalcResult.Ok)?.let { r ->
            SectionCard("Результат") {
                Column(Modifier.testTag(Tags.RESULT)) {
                    InfoRow("Потужність турбіни", "${r.turbineKw.fmt(0)} кВт")
                    InfoRow("Потужність станції", "${r.stationMw.fmt(2)} МВт")
                    InfoRow("Виробіток за добу", "${r.dailyMwh.fmt(1)} МВт·год")
                    InfoRow("Стан", r.status.title)
                }
            }
        }
    }
}
