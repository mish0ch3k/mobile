package ua.kpi.windfarm4.map

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import ua.kpi.windfarm4.R
import ua.kpi.windfarm4.domain.UserLocation
import java.io.File

/** Маркер для мапи: незалежний від osmdroid опис, який формує ViewModel / UI. */
data class MapMarker(
    val id: Int,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String,
    val color: Int,         // ARGB
    val details: String = ""
)

/**
 * Налаштування osmdroid (OpenStreetMap). Ключ API не потрібен; обов'язково задати
 * User-Agent і теку кешу плиток у внутрішньому сховищі застосунку (без дозволу на пам'ять).
 */
fun initOsm(context: Context) {
    Configuration.getInstance().apply {
        userAgentValue = context.packageName
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
}

/**
 * Мапа OpenStreetMap у Compose (osmdroid MapView через AndroidView).
 *  - [selectedId]: виділений маркер (більший, з InfoWindow, якщо [showInfoWindow]); мапа центрується на ньому;
 *  - [onMarkerClick]: натискання на маркер передається у ViewModel (двобічний зв'язок зі списком);
 *  - [user]: поточна позиція користувача (синій маркер).
 */
@Composable
fun OsmMap(
    markers: List<MapMarker>,
    selectedId: Int?,
    onMarkerClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    user: UserLocation? = null,
    showInfoWindow: Boolean = false,
    zoomOnSelect: Double = 9.0,
    center: Pair<Double, Double> = 47.2 to 32.0,
    initialZoom: Double = 6.3
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clickHandler by rememberUpdatedState(onMarkerClick)
    val icons = remember { MarkerIcons(context) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(initialZoom)
            controller.setCenter(GeoPoint(center.first, center.second))
            minZoomLevel = 4.0
        }
    }

    // MapView має власний життєвий цикл — передаємо йому події Activity
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); mapView.onDetach() }
    }

    // Фокус мапи на вибраному об'єкті (вибір у списку → мапа)
    LaunchedEffect(selectedId) {
        markers.firstOrNull { it.id == selectedId }?.let {
            mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude), zoomOnSelect, 700L)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        // Перебудова маркерів при кожній зміні даних: колір, розмір і InfoWindow залежать від стану
        map.overlays.filterIsInstance<Marker>().forEach { it.closeInfoWindow() }
        map.overlays.removeAll { it is Marker }
        markers.forEach { m ->
            val selected = m.id == selectedId
            val marker = Marker(map).apply {
                position = GeoPoint(m.latitude, m.longitude)
                title = m.title
                snippet = m.snippet
                subDescription = m.details
                icon = icons.pin(m.color, selected)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { mk, _ ->
                    clickHandler(m.id)  // маркер → список
                    if (showInfoWindow) mk.showInfoWindow()
                    true
                }
            }
            map.overlays.add(marker)
            if (selected && showInfoWindow) marker.showInfoWindow()
        }
        user?.let { u ->
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(u.latitude, u.longitude)
                title = "Ви тут"
                snippet = "Точність ±${u.accuracyM.toInt()} м · ${u.provider}"
                icon = icons.user()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            })
        }
        map.invalidate()
    })
}

/** Кешовані зображення маркерів: шпилька потрібного кольору (звичайна / збільшена) і точка користувача. */
private class MarkerIcons(private val context: Context) {
    private val cache = HashMap<Pair<Int, Boolean>, Drawable>()

    fun pin(color: Int, selected: Boolean): Drawable = cache.getOrPut(color to selected) {
        val size = if (selected) 112 else 72
        val d = ContextCompat.getDrawable(context, R.drawable.ic_pin)!!.mutate().apply { setTint(color) }
        BitmapDrawable(context.resources, d.toBitmap(size, size))
    }

    private val userIcon by lazy {
        BitmapDrawable(context.resources, ContextCompat.getDrawable(context, R.drawable.ic_user_location)!!.toBitmap(56, 56))
    }

    fun user(): Drawable = userIcon
}
