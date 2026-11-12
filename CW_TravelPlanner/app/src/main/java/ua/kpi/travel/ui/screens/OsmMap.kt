package ua.kpi.travel.ui.screens

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import ua.kpi.travel.R

data class MapMarker(val id: Long, val latitude: Double, val longitude: Double, val title: String, val snippet: String, val color: Int)

/**
 * Мапа OpenStreetMap (osmdroid) у Compose. Маркери з'єднані лінією в порядку маршруту;
 * натискання маркера виділяє його у списку, вибір у списку центрує мапу.
 */
@Composable
fun OsmMap(markers: List<MapMarker>, selectedId: Long?, onMarkerClick: (Long) -> Unit, modifier: Modifier = Modifier,
           center: Pair<Double, Double>, initialZoom: Double = 13.0) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val click by rememberUpdatedState(onMarkerClick)
    val icons = remember { PinIcons(context) }
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(initialZoom)
            controller.setCenter(GeoPoint(center.first, center.second))
        }
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) map.onResume()
            if (e == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); map.onDetach() }
    }
    LaunchedEffect(selectedId) {
        markers.firstOrNull { it.id == selectedId }?.let { map.controller.animateTo(GeoPoint(it.latitude, it.longitude), 15.0, 600L) }
    }
    AndroidView({ map }, modifier, update = { v ->
        v.overlays.filterIsInstance<Marker>().forEach { it.closeInfoWindow() }
        v.overlays.clear()
        if (markers.size > 1) v.overlays.add(Polyline(v).apply {
            setPoints(markers.map { GeoPoint(it.latitude, it.longitude) })
            outlinePaint.color = 0x8800658F.toInt(); outlinePaint.strokeWidth = 6f
        })
        markers.forEach { m ->
            val sel = m.id == selectedId
            val mk = Marker(v).apply {
                position = GeoPoint(m.latitude, m.longitude); title = m.title; snippet = m.snippet
                icon = icons.pin(m.color, sel); setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { marker, _ -> click(m.id); marker.showInfoWindow(); true }
            }
            v.overlays.add(mk)
            if (sel) mk.showInfoWindow()
        }
        v.invalidate()
    })
}

private class PinIcons(private val context: Context) {
    private val cache = HashMap<Pair<Int, Boolean>, Drawable>()
    fun pin(color: Int, big: Boolean): Drawable = cache.getOrPut(color to big) {
        val s = if (big) 104 else 72
        val d = ContextCompat.getDrawable(context, R.drawable.ic_pin)!!.mutate().apply { setTint(color) }
        BitmapDrawable(context.resources, d.toBitmap(s, s))
    }
}
