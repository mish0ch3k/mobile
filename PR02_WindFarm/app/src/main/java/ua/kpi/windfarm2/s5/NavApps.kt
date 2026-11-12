package ua.kpi.windfarm2.s5

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.kpi.windfarm2.domain.TurbineCatalog
import ua.kpi.windfarm2.domain.TurbineSpec
import ua.kpi.windfarm2.domain.WindPowerCalculator
import ua.kpi.windfarm2.ui.common.FormField
import ua.kpi.windfarm2.ui.common.InfoRow
import ua.kpi.windfarm2.ui.common.MenuButton
import ua.kpi.windfarm2.ui.common.ScreenTitle
import ua.kpi.windfarm2.ui.common.fmt

/** Маршрути навігації. */
private object Routes {
    const val MENU = "menu"
    const val MONITOR = "monitor"
    const val TURBINES = "turbines"
    const val DETAILS = "details/{id}"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    fun details(id: Int) = "details/$id"
}

// ========================= БАЗОВИЙ РІВЕНЬ =========================
/** Головне меню з двома кнопками та два екрани-заглушки (NavController + NavHost). */
@Composable
fun BasicNavApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.MENU) {
        composable(Routes.MENU) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                ScreenTitle("Вітрова електростанція", "Головне меню")
                Spacer(Modifier.height(16.dp))
                MenuButton("Моніторинг вітру") { nav.navigate(Routes.MONITOR) }
                MenuButton("Список турбін") { nav.navigate(Routes.TURBINES) }
            }
        }
        composable(Routes.MONITOR) { StubScreen("Моніторинг вітру", "Тут будуть поточні виміри датчиків") { nav.popBackStack() } }
        composable(Routes.TURBINES) { StubScreen("Список турбін", "Тут буде перелік турбін станції") { nav.popBackStack() } }
    }
}

@Composable
private fun StubScreen(title: String, text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        ScreenTitle(title)
        Text(text)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text("← Назад до меню") }
    }
}

// ========================= СЕРЕДНІЙ РІВЕНЬ =========================
/**
 * Третій екран із формою; передача даних:
 *  - вперед — аргумент маршруту details/{id};
 *  - назад — результат через savedStateHandle попереднього запису стеку (ім'я оператора).
 */
@Composable
fun MediumNavApp() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.MENU) {
        composable(Routes.MENU) { entry ->
            // Значення, яке повернув екран налаштувань; UI оновлюється автоматично
            val operator by entry.savedStateHandle.getStateFlow("operator", "не вказано").collectAsState()
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                ScreenTitle("Вітрова електростанція")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Черговий оператор:")
                    Text(operator, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(12.dp))
                MenuButton("Список турбін") { nav.navigate(Routes.TURBINES) }
                MenuButton("Налаштування оператора") { nav.navigate(Routes.SETTINGS) }
            }
        }
        composable(Routes.TURBINES) { TurbineList(onOpen = { nav.navigate(Routes.details(it)) }, onBack = { nav.popBackStack() }) }
        composable(Routes.DETAILS, arguments = listOf(navArgument("id") { type = NavType.IntType })) { entry ->
            TurbineDetails(entry.arguments?.getInt("id") ?: -1) { nav.popBackStack() }
        }
        composable(Routes.SETTINGS) {
            var name by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                ScreenTitle("Налаштування оператора")
                FormField("Ім'я оператора", name, { name = it }, if (name.isBlank()) "Введіть ім'я" else null, showError = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.Button(enabled = name.isNotBlank(), onClick = {
                        nav.previousBackStackEntry?.savedStateHandle?.set("operator", name.trim())
                        nav.popBackStack()
                    }) { Text("Зберегти") }
                    OutlinedButton(onClick = { nav.popBackStack() }) { Text("Скасувати") }
                }
            }
        }
    }
}

/** Список турбін (перевикористовується на кількох рівнях). */
@Composable
private fun TurbineList(onOpen: (Int) -> Unit, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        ScreenTitle("Турбіни станції")
        LazyColumn(Modifier.weight(1f)) {
            items(TurbineCatalog.turbines) { t ->
                val status = WindPowerCalculator.status(t.windSpeed, TurbineSpec())
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text("${t.windSpeed.fmt()} м/с · ${status.title}") },
                    leadingContent = { Icon(Icons.Filled.Air, null) },
                    modifier = Modifier.clickable { onOpen(t.id) }
                )
            }
        }
        if (onBack != null) OutlinedButton(onClick = onBack) { Text("← Назад") }
    }
}

/** Деталі турбіни за ідентифікатором з аргументу навігації. */
@Composable
private fun TurbineDetails(id: Int, onBack: (() -> Unit)?) {
    val t = TurbineCatalog.byId(id)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        if (t == null) {
            ScreenTitle("Турбіну №$id не знайдено")
        } else {
            val spec = TurbineSpec()
            val p = WindPowerCalculator.turbinePowerKw(t.windSpeed, spec)
            ScreenTitle(t.name, "Ідентифікатор: ${t.id}")
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    InfoRow("Швидкість вітру", "${t.windSpeed.fmt()} м/с")
                    InfoRow("Бофорт", "${WindPowerCalculator.beaufort(t.windSpeed)} — ${WindPowerCalculator.beaufortName(WindPowerCalculator.beaufort(t.windSpeed))}")
                    InfoRow("Потужність", "${p.fmt(0)} кВт")
                    InfoRow("КВВП", "${WindPowerCalculator.capacityFactor(p, spec).fmt(0)} %")
                    InfoRow("Стан", WindPowerCalculator.status(t.windSpeed, spec).title)
                }
            }
        }
        if (onBack != null) OutlinedButton(onClick = onBack) { Text("← Назад") }
    }
}

// ========================= ПРОСУНУТИЙ РІВЕНЬ =========================
/** Стан користувача та налаштування (бізнес-логіка рівня застосунку). */
private class UserSettings {
    var operator by mutableStateOf("Гаврилюк М.")
    var showStorms by mutableStateOf(true)  // показувати турбіни в аварійній зупинці
    var stormAlerts by mutableStateOf(true)
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination(Routes.MENU, "Огляд", Icons.Filled.Home),
    Destination(Routes.TURBINES, "Турбіни", Icons.Filled.Air),
    Destination(Routes.SETTINGS, "Налаштування", Icons.Filled.Settings)
)

/**
 * Адаптивна навігація: на вузьких екранах — нижня панель (NavigationBar),
 * на широких (≥ 600 dp) — бічна (NavigationRail) і деталі поруч зі списком.
 */
@Composable
fun AdvancedNavApp() {
    val nav = rememberNavController()
    val settings = remember { UserSettings() }
    val current by nav.currentBackStackEntryAsState()
    val route = current?.destination?.route

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    destinations.forEach { d ->
                        NavigationRailItem(route == d.route, { nav.go(d.route) }, { Icon(d.icon, d.label) }, label = { Text(d.label) })
                    }
                }
                AdvancedHost(nav, settings, wide = true, modifier = Modifier.weight(1f))
            }
        } else {
            Scaffold(bottomBar = {
                NavigationBar {
                    destinations.forEach { d ->
                        NavigationBarItem(route == d.route, { nav.go(d.route) }, { Icon(d.icon, d.label) }, label = { Text(d.label) })
                    }
                }
            }) { padding -> AdvancedHost(nav, settings, wide = false, modifier = Modifier.padding(padding)) }
        }
    }
}

/** Перехід між вкладками без накопичення копій у стеку. */
private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun AdvancedHost(nav: NavHostController, s: UserSettings, wide: Boolean, modifier: Modifier) {
    NavHost(nav, startDestination = Routes.MENU, modifier = modifier) {
        composable(Routes.MENU) { Overview(s) }
        composable(Routes.TURBINES) {
            if (wide) {
                // Список + деталі поруч (master-detail)
                var selected by remember { mutableStateOf(1) }
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f)) { TurbineList({ selected = it }, null) }
                    Column(Modifier.weight(1f)) { TurbineDetails(selected, null) }
                }
            } else TurbineList({ nav.navigate(Routes.details(it)) }, null)
        }
        composable(Routes.DETAILS, arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
            TurbineDetails(e.arguments?.getInt("id") ?: -1) { nav.popBackStack() }
        }
        composable(Routes.SETTINGS) { SettingsScreen(s) }
    }
}

/** Огляд станції з урахуванням налаштувань користувача. */
@Composable
private fun Overview(s: UserSettings) {
    val spec = TurbineSpec()
    val shown = TurbineCatalog.turbines.filter { s.showStorms || it.windSpeed < spec.cutOutSpeed }
    val total = shown.sumOf { WindPowerCalculator.turbinePowerKw(it.windSpeed, spec) }
    val storms = TurbineCatalog.turbines.count { it.windSpeed >= spec.cutOutSpeed }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        ScreenTitle("Вітаємо, ${s.operator}!", "Огляд вітрової електростанції")
        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                InfoRow("Турбін у списку", "${shown.size} з ${TurbineCatalog.turbines.size}")
                InfoRow("Сумарна потужність", "${(total / 1000).fmt(2)} МВт")
                InfoRow("Середній вітер", "${(shown.map { it.windSpeed }.average().takeIf { !it.isNaN() } ?: 0.0).fmt()} м/с")
            }
        }
        if (s.stormAlerts && storms > 0) {
            Text("⚠ Аварійна зупинка через бурю: $storms турб.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsScreen(s: UserSettings) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitle("Налаштування")
        FormField("Ім'я оператора", s.operator, { s.operator = it }, if (s.operator.isBlank()) "Ім'я не може бути порожнім" else null, showError = true)
        SettingSwitch("Показувати турбіни в аварійній зупинці", s.showStorms) { s.showStorms = it }
        SettingSwitch("Сповіщення про бурю", s.stormAlerts) { s.stormAlerts = it }
    }
}

@Composable
private fun SettingSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ========================= ДОМАШНЄ ЗАВДАННЯ =========================
/** Навігація: вхід → меню → турбіни → деталі; вихід повертає на екран входу. */
@Composable
fun HomeworkNavApp() {
    val nav = rememberNavController()
    var user by remember { mutableStateOf("") }
    NavHost(nav, startDestination = Routes.LOGIN) {
        composable(Routes.LOGIN) {
            var login by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                ScreenTitle("Вхід оператора")
                FormField("Логін", login, { login = it }, if (login.length < 3) "Щонайменше 3 символи" else null)
                MenuButton("Увійти") {
                    if (login.length >= 3) {
                        user = login
                        nav.navigate(Routes.MENU) { popUpTo(Routes.LOGIN) { inclusive = true } }
                    }
                }
            }
        }
        composable(Routes.MENU) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                ScreenTitle("Головне меню", "Оператор: $user")
                MenuButton("Турбіни станції") { nav.navigate(Routes.TURBINES) }
                MenuButton("Вийти") { nav.navigate(Routes.LOGIN) { popUpTo(Routes.MENU) { inclusive = true } } }
            }
        }
        composable(Routes.TURBINES) { TurbineList({ nav.navigate(Routes.details(it)) }) { nav.popBackStack() } }
        composable(Routes.DETAILS, arguments = listOf(navArgument("id") { type = NavType.IntType })) { e ->
            TurbineDetails(e.arguments?.getInt("id") ?: -1) { nav.popBackStack() }
        }
    }
}
