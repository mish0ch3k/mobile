package ua.kpi.travel

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ua.kpi.travel.notify.ReminderScheduler
import ua.kpi.travel.ui.screens.TripDetailScreen
import ua.kpi.travel.ui.screens.TripEditScreen
import ua.kpi.travel.ui.screens.TripsScreen
import ua.kpi.travel.ui.theme.TravelTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !ReminderScheduler.canPost(this)) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val openTrip = intent.getLongExtra(ReminderScheduler.EXTRA_TRIP, 0L)
        setContent {
            TravelTheme {
                val nav = rememberNavController()
                LaunchedEffect(openTrip) { if (openTrip > 0) nav.navigate("trip/$openTrip") }
                NavHost(nav, startDestination = "trips") {
                    composable("trips") {
                        TripsScreen(onOpen = { nav.navigate("trip/$it") }, onCreate = { nav.navigate("edit/0") })
                    }
                    composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        val id = e.arguments?.getLong("id") ?: 0L
                        TripEditScreen(id, onDone = { saved ->
                            nav.popBackStack()
                            if (id == 0L) nav.navigate("trip/$saved")
                        }, onBack = { nav.popBackStack() })
                    }
                    composable("trip/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                        val id = e.arguments?.getLong("id") ?: 0L
                        TripDetailScreen(id, onEdit = { nav.navigate("edit/$it") }, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
