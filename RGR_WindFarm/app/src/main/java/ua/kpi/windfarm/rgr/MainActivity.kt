package ua.kpi.windfarm.rgr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import ua.kpi.windfarm.rgr.notify.AlertNotifier
import ua.kpi.windfarm.rgr.ui.AlertsScreen
import ua.kpi.windfarm.rgr.ui.AnalyticsScreen
import ua.kpi.windfarm.rgr.ui.CalculatorScreen
import ua.kpi.windfarm.rgr.ui.DashboardScreen
import ua.kpi.windfarm.rgr.ui.ForecastScreen
import ua.kpi.windfarm.rgr.ui.StationScreen
import ua.kpi.windfarm.rgr.ui.theme.WindFarmTheme

/** Вкладки нижньої панелі — по одній на кожне завдання РГР. */
enum class Tab(val route: String, val title: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Моніторинг", Icons.Filled.Dashboard),
    CALC("calc", "Розрахунок", Icons.Filled.Calculate),
    FORECAST("forecast", "Прогноз", Icons.Filled.Cloud),
    ANALYTICS("analytics", "Аналітика", Icons.Filled.Analytics),
    ALERTS("alerts", "Тривоги", Icons.Filled.Warning)
}

class MainActivity : ComponentActivity() {
    /** Відкриття вкладки «Тривоги» з натиснутого сповіщення. */
    private val openAlerts = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openAlerts.value = intent?.getBooleanExtra(AlertNotifier.EXTRA_ALERTS, false) == true
        val container = (application as WindApp).container
        setContent {
            WindFarmTheme {
                val nav = rememberNavController()
                val active by container.alerts.activeCount.collectAsStateWithLifecycle(0)
                val open by openAlerts.collectAsStateWithLifecycle()
                LaunchedEffect(open) { if (open) { nav.go(Tab.ALERTS.route); openAlerts.value = false } }
                Scaffold(bottomBar = { BottomBar(nav, active) }) { padding ->
                    Box(Modifier.padding(padding)) {
                        NavHost(nav, startDestination = Tab.DASHBOARD.route) {
                            composable(Tab.DASHBOARD.route) { DashboardScreen { id -> nav.navigate("station/$id") } }
                            composable(Tab.CALC.route) { CalculatorScreen() }
                            composable(Tab.FORECAST.route) { ForecastScreen() }
                            composable(Tab.ANALYTICS.route) { AnalyticsScreen() }
                            composable(Tab.ALERTS.route) { AlertsScreen() }
                            composable("station/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                                StationScreen(e.arguments?.getLong("id") ?: 1L) { nav.popBackStack() }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(AlertNotifier.EXTRA_ALERTS, false)) openAlerts.value = true
    }
}

/** Перехід між вкладками без накопичення копій екранів у стеку. */
private fun NavHostController.go(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun BottomBar(nav: NavHostController, activeAlerts: Int) {
    val current by nav.currentBackStackEntryAsState()
    NavigationBar {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = current?.destination?.route == t.route,
                onClick = { nav.go(t.route) },
                icon = {
                    if (t == Tab.ALERTS && activeAlerts > 0) BadgedBox(badge = { Badge { Text("$activeAlerts") } }) { Icon(t.icon, t.title) }
                    else Icon(t.icon, t.title)
                },
                label = { Text(t.title) }
            )
        }
    }
}
