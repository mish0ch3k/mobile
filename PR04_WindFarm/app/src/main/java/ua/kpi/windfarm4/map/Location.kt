package ua.kpi.windfarm4.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ua.kpi.windfarm4.domain.UserLocation
import ua.kpi.windfarm4.ui.common.SectionCard

/** Стан доступу до геолокації. */
enum class LocationAccess { GRANTED, DENIED, DISABLED }

/**
 * Отримання позиції через системний LocationManager (без Google Play Services):
 * спершу остання відома позиція, далі — оновлення від GPS та мережевого провайдера.
 */
class LocationTracker(context: Context) {
    private val ctx = context.applicationContext
    private val lm = ctx.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(): Boolean =
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    fun access(): LocationAccess = when {
        !hasPermission() -> LocationAccess.DENIED
        !isEnabled() -> LocationAccess.DISABLED
        else -> LocationAccess.GRANTED
    }

    /**
     * Потік позицій. callbackFlow перетворює колбеки LocationListener на Flow;
     * awaitClose знімає підписку, коли збирач зупиняється (екран закрито).
     */
    @SuppressLint("MissingPermission") // дозвіл перевіряється в hasPermission()
    fun updates(minTimeMs: Long = 2000, minDistanceM: Float = 5f): Flow<UserLocation> = callbackFlow {
        if (!hasPermission()) { close(SecurityException("Немає дозволу на геолокацію")); return@callbackFlow }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
        providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { trySend(it.toUser()) }
        val listener = LocationListener { loc -> trySend(loc.toUser()) }
        providers.forEach { lm.requestLocationUpdates(it, minTimeMs, minDistanceM, listener, Looper.getMainLooper()) }
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toUser() = UserLocation(latitude, longitude, accuracy, provider ?: "")
}

/**
 * Запит дозволу на геолокацію з поясненням. [onResult] — чи надано хоча б приблизний доступ.
 * Якщо користувач відмовив, застосунок працює далі без позиції та показує пояснення.
 */
@Composable
fun LocationPermissionCard(access: LocationAccess, onResult: (Boolean) -> Unit) {
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        asked = true
        onResult(res.values.any { it })
    }
    if (access == LocationAccess.GRANTED) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.LocationOff, null, tint = MaterialTheme.colorScheme.error)
            Text(if (access == LocationAccess.DISABLED) "Геолокацію вимкнено в налаштуваннях пристрою" else "Немає доступу до геолокації",
                style = MaterialTheme.typography.titleSmall)
        }
        Text(
            if (access == LocationAccess.DISABLED) "Увімкніть «Місцезнаходження» в шторці або налаштуваннях — мапа й об'єкти доступні й без цього."
            else if (asked) "Доступ не надано. Мапа працює, але відстані до об'єктів і ваша позиція не показуються."
            else "Дозвіл потрібен, щоб показати вашу позицію на мапі та відстань до енергетичних об'єктів.",
            style = MaterialTheme.typography.bodySmall
        )
        if (access == LocationAccess.DENIED) Button(onClick = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }) { Text("Надати доступ") }
    }
}

/** Зручна перевірка з Compose. */
@Composable
fun rememberLocationTracker(): LocationTracker {
    val ctx = LocalContext.current
    return remember { LocationTracker(ctx) }
}
