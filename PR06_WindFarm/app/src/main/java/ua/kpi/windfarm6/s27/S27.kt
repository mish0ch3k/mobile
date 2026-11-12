package ua.kpi.windfarm6.s27

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm6.BuildConfig
import ua.kpi.windfarm6.data.SampleData
import ua.kpi.windfarm6.domain.WindPowerCalculator
import ua.kpi.windfarm6.ui.common.InfoRow
import ua.kpi.windfarm6.ui.common.ScreenTitle
import ua.kpi.windfarm6.ui.common.SectionCard
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Відомості про встановлену збірку: версія, варіант, підпис, розмір. */
data class BuildInfo(
    val appId: String, val versionName: String, val versionCode: Long, val buildType: String, val flavor: String,
    val debuggable: Boolean, val minified: Boolean, val minSdk: Int, val targetSdk: Int,
    val signer: String, val sha256: String, val debugCert: Boolean, val apkKb: Long, val installed: String,
    val permissions: List<String>, val obfuscatedName: String
)

@Suppress("DEPRECATION")
fun readBuildInfo(context: Context): BuildInfo {
    val pm = context.packageManager
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        else PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
    val pi: PackageInfo = pm.getPackageInfo(context.packageName, flags)
    val sigBytes = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() else pi.signatures?.firstOrNull()?.toByteArray()
    val cert = sigBytes?.let { runCatching { CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate }.getOrNull() }
    val sha = sigBytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString(":") { b -> "%02X".format(b) } } ?: "—"
    val ai = context.applicationInfo
    return BuildInfo(
        appId = context.packageName, versionName = pi.versionName ?: "—",
        versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
        buildType = BuildConfig.BUILD_TYPE, flavor = BuildConfig.FLAVOR,
        debuggable = ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0, minified = BuildConfig.MINIFIED,
        minSdk = if (Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 0, targetSdk = ai.targetSdkVersion,
        signer = cert?.subjectX500Principal?.name ?: "—", sha256 = sha,
        debugCert = cert?.subjectX500Principal?.name?.contains("Android Debug") == true,
        apkKb = File(ai.sourceDir).length() / 1024,
        installed = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(pi.lastUpdateTime)),
        permissions = pi.requestedPermissions?.map { it.substringAfterLast('.') } ?: emptyList(),
        // у release з R8 назви класів скорочуються (обфускація) — видно прямо в застосунку
        obfuscatedName = WindPowerCalculator::class.java.name
    )
}

@Composable
private fun rememberInfo(): BuildInfo { val c = LocalContext.current; return remember { readBuildInfo(c) } }

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicReleaseApp() {
    val i = rememberInfo()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenTitle("Про збірку", "Встановлений пакет і підпис")
        SectionCard("Версія") {
            InfoRow("applicationId", i.appId)
            InfoRow("versionName / versionCode", "${i.versionName} / ${i.versionCode}")
            InfoRow("Тип збірки / варіант", "${i.buildType} / ${i.flavor}")
            InfoRow("minSdk / targetSdk", "${i.minSdk} / ${i.targetSdk}")
            InfoRow("Встановлено", i.installed)
            InfoRow("Розмір APK", "${i.apkKb} КБ")
        }
        SectionCard("Підпис") {
            Text(i.signer, style = MaterialTheme.typography.bodySmall)
            Text("SHA-256:", style = MaterialTheme.typography.labelMedium)
            Text(i.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(if (i.debugCert) "Підписано debug-ключем — для публікації потрібен власний keystore" else "Підписано ключем релізу",
                color = if (i.debugCert) MaterialTheme.colorScheme.error else Color(0xFF2E7D32))
        }
        Text("Пристрій: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumReleaseApp() {
    val i = rememberInfo()
    val shown = SampleData.stations.take(BuildConfig.MAX_STATIONS)
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenTitle("Оптимізація та варіанти")
        SectionCard("R8 / ProGuard") {
            InfoRow("Мініфікація (R8)", if (i.minified) "увімкнено" else "вимкнено (debug)")
            InfoRow("Можна налагоджувати", if (i.debuggable) "так" else "ні")
            Text("Ім'я класу калькулятора під час виконання:", style = MaterialTheme.typography.labelMedium)
            Text(i.obfuscatedName, fontFamily = FontFamily.Monospace)
            Text(if (i.obfuscatedName.contains("WindPowerCalculator")) "Назви не змінені — це debug-збірка" else "Назву скорочено R8 — код важче декомпілювати",
                style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Build flavor: ${i.flavor}") {
            InfoRow("Станцій доступно", "${shown.size} з ${SampleData.stations.size}")
            shown.forEach { Text("• ${it.first}", style = MaterialTheme.typography.bodySmall) }
            if (shown.size < SampleData.stations.size) Text("Демо-варіант обмежує кількість станцій (BuildConfig.MAX_STATIONS = ${BuildConfig.MAX_STATIONS})",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Варіанти збірки") {
            listOf("demoDebug", "demoRelease", "fullDebug", "fullRelease").forEach { v ->
                Text((if (v.equals(i.flavor + i.buildType.replaceFirstChar { it.uppercase() }, true)) "▶ " else "   ") + v, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ============================ ПРОСУНУТИЙ ============================
data class ReleaseCheck(val title: String, val ok: Boolean, val hint: String)

fun releaseChecks(i: BuildInfo): List<ReleaseCheck> = listOf(
    ReleaseCheck("Збірка release", i.buildType == "release", "Build → Generate Signed Bundle / APK → release"),
    ReleaseCheck("Не налагоджувана", !i.debuggable, "debuggable має бути false"),
    ReleaseCheck("Підпис власним ключем", !i.debugCert && i.sha256 != "—", "keystore.properties + signingConfigs.release"),
    ReleaseCheck("R8 увімкнено", i.minified, "isMinifyEnabled = true, isShrinkResources = true"),
    ReleaseCheck("targetSdk ≥ 35", i.targetSdk >= 35, "Вимога Google Play для нових застосунків"),
    ReleaseCheck("versionCode > 0", i.versionCode > 0, "Збільшувати з кожним завантаженням у Play Console"),
    ReleaseCheck("Без зайвих дозволів", i.permissions.none { it in setOf("READ_EXTERNAL_STORAGE", "ACCESS_FINE_LOCATION", "READ_CONTACTS") },
        "Файли вибираються через Photo Picker / SAF — дозволи на пам'ять не потрібні"),
    ReleaseCheck("Розмір APK < 15 МБ", i.apkKb < 15 * 1024, "Поточний: ${i.apkKb / 1024} МБ")
)

@Composable
fun AdvancedReleaseApp() {
    val i = rememberInfo()
    val checks = releaseChecks(i)
    val passed = checks.count { it.ok }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenTitle("Готовність до релізу", "${i.flavor}${i.buildType.replaceFirstChar { it.uppercase() }} · ${i.versionName}")
        LinearProgressIndicator({ passed.toFloat() / checks.size }, Modifier.fillMaxWidth())
        Text("Виконано $passed з ${checks.size}", style = MaterialTheme.typography.titleSmall)
        checks.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(if (c.ok) "✓" else "✗", color = if (c.ok) Color(0xFF2E7D32) else Color(0xFFC62828), modifier = Modifier.padding(end = 8.dp), fontWeight = FontWeight.Bold)
                Column {
                    Text(c.title)
                    if (!c.ok) Text(c.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionCard("Дозволи пакета") { Text(i.permissions.joinToString().ifEmpty { "немає" }, style = MaterialTheme.typography.bodySmall) }
        Text("У debug-збірці частина пунктів очікувано не виконана; встановіть fullRelease, щоб побачити повну перевірку.", style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ДОМАШНЄ ============================
private data class Slide(val title: String, val points: List<String>)

private val slides = listOf(
    Slide("Вітрова ЕС — мобільний моніторинг", listOf("Варіант 2: вітрова електростанція з моніторингом швидкості вітру", "Автор: Гаврилюк М. Є., ТВ-31", "Kotlin · Jetpack Compose · Material 3")),
    Slide("Ключові функції", listOf("Анімований дашборд потужності станції", "Журнал виробітку у CSV і JSON з аналітикою", "Фото та документи об'єктів, перегляд PDF", "PDF-звіти з таблицями та графіками", "Моніторинг ресурсів планшета, світла/темна тема")),
    Slide("Архітектура", listOf("MVVM: Compose UI → ViewModel (StateFlow) → репозиторії", "data: CsvCodec, JsonCodec, RecordFileStore (Dispatchers.IO, атомарний запис)", "media: MediaRepository, перевірка сигнатур, кеш мініатюр LruCache", "pdf: PdfReportBuilder на PdfDocument; monitor: ResourceMonitor")),
    Slide("Технології", listOf("kotlinx.serialization, coroutines, Flow", "Photo Picker і SAF замість дозволів на пам'ять", "PdfDocument / PdfRenderer, FileProvider", "Navigation Compose, ExifInterface")),
    Slide("Тестування", listOf("Юніт-тести: CSV/JSON, дати, валідація, сигнатури, калькулятор, повтори й тайм-аути (віртуальний час)", "UI-тести Compose: калькулятор, вхід → список → деталі з FakeRepository", "Покриття: createFullDebugUnitTestCoverageReport (JaCoCo)", "Сумісність: minSdk 24 (Android 7.0) … targetSdk 35 (Android 15)")),
    Slide("Реліз", listOf("keystore (keytool) + keystore.properties поза репозиторієм", "R8 + shrinkResources, правила proguard-rules.pro", "Варіанти demo / full, versionCode ${BuildConfig.VERSION_CODE}", "App Bundle → Play Console: внутрішнє → закрите → відкрите тестування"))
)

@Composable
fun HomeworkReleaseApp() {
    var index by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScreenTitle("Презентація проєкту", "Слайд ${index + 1} з ${slides.size}")
        AnimatedContent(index, transitionSpec = {
            val fwd = targetState > initialState
            slideInHorizontally { if (fwd) it else -it } togetherWith slideOutHorizontally { if (fwd) -it else it }
        }, label = "slide", modifier = Modifier.weight(1f)) { k ->
            Card(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(slides[k].title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    slides[k].points.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ if (index > 0) index-- }, enabled = index > 0) { Text("◀ Назад") }
            Text("${index + 1} / ${slides.size}")
            OutlinedButton({ if (index < slides.size - 1) index++ }, enabled = index < slides.size - 1) { Text("Далі ▶") }
        }
    }
}
