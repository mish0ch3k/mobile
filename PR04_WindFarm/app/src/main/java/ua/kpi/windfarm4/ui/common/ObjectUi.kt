package ua.kpi.windfarm4.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.WindPower
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm4.domain.EnergyObject
import ua.kpi.windfarm4.domain.ObjectStatus
import ua.kpi.windfarm4.domain.ObjectType
import ua.kpi.windfarm4.map.MapMarker

/** Колір типу об'єкта — однаковий у списку та на мапі. */
fun typeColor(t: ObjectType): Color = when (t) {
    ObjectType.WIND_FARM -> Color(0xFF1565C0)
    ObjectType.TURBINE -> Color(0xFF00897B)
    ObjectType.MET_MAST -> Color(0xFF6A1B9A)
    ObjectType.SUBSTATION -> Color(0xFFEF6C00)
    ObjectType.STORAGE -> Color(0xFF2E7D32)
}

fun statusColor(s: ObjectStatus): Color = when (s) {
    ObjectStatus.WORKING -> Color(0xFF2E7D32)
    ObjectStatus.MAINTENANCE -> Color(0xFFF9A825)
    ObjectStatus.STORM_STOP -> Color(0xFFC62828)
    ObjectStatus.OFFLINE -> Color(0xFF757575)
}

fun typeIcon(t: ObjectType): ImageVector = when (t) {
    ObjectType.WIND_FARM -> Icons.Filled.WindPower
    ObjectType.TURBINE -> Icons.Filled.Air
    ObjectType.MET_MAST -> Icons.Filled.Sensors
    ObjectType.SUBSTATION -> Icons.Filled.ElectricalServices
    ObjectType.STORAGE -> Icons.Filled.BatteryChargingFull
}

/** Маркер мапи з об'єкта; непрацюючі об'єкти — кольором статусу. */
fun EnergyObject.toMarker(): MapMarker = MapMarker(
    id = id, latitude = latitude, longitude = longitude, title = name,
    snippet = "${type.title} · ${status.title}",
    color = (if (status == ObjectStatus.WORKING) typeColor(type) else statusColor(status)).toArgb(),
    details = buildString {
        if (capacityMw > 0) append("${capacityMw.fmt(0)} МВт")
        if (generates && status == ObjectStatus.WORKING) append(" · зараз ${powerMw.fmt(1)} МВт")
        if (windSpeed > 0) append(" · вітер ${windSpeed.fmt()} м/с")
    }
)

@Composable
fun TypeBadge(t: ObjectType) {
    Surface(shape = MaterialTheme.shapes.small, color = typeColor(t).copy(alpha = 0.12f)) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(typeIcon(t), null, tint = typeColor(t), modifier = Modifier.size(16.dp))
            Text(" ${t.short}", style = MaterialTheme.typography.labelMedium, color = typeColor(t))
        }
    }
}

@Composable
fun StatusBadge(s: ObjectStatus) {
    Surface(shape = MaterialTheme.shapes.small, color = statusColor(s).copy(alpha = 0.12f)) {
        Text(s.title, style = MaterialTheme.typography.labelMedium, color = statusColor(s), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** «12 хв тому» для дати останнього оновлення. */
fun updatedText(t: Long, now: Long = System.currentTimeMillis()): String {
    val m = (now - t) / 60_000
    return when {
        m < 1 -> "щойно"
        m < 60 -> "$m хв тому"
        else -> "${m / 60} год ${m % 60} хв тому"
    }
}

/**
 * Картка об'єкта. [selected] — виділення рамкою (синхронізація з мапою), [extra] — додатковий вміст
 * (параметри, відстань, прапорець вибору) залежно від рівня складності.
 */
@Composable
fun ObjectCard(
    o: EnergyObject,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    showDescription: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(o.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TypeBadge(o.type)
                }
                if (showDescription) Text(o.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                extra?.invoke()
            }
        }
    }
}

/** Рядок параметрів: потужність, генерація, вітер, статус і завантаження. */
@Composable
fun ObjectParams(o: EnergyObject, distanceKm: Double? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(o.status)
        if (o.capacityMw > 0) Text("${o.capacityMw.fmt(0)} МВт", style = MaterialTheme.typography.bodySmall)
        if (o.windSpeed > 0) Text("вітер ${o.windSpeed.fmt()} м/с", style = MaterialTheme.typography.bodySmall)
        distanceKm?.let { Text("${it.fmt(0)} км", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    if (o.generates) {
        Text("Генерація ${o.powerMw.fmt(1)} МВт (${o.loadPercent.fmt(0)} %)", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { (o.loadPercent / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

/** Повідомлення, коли список порожній. */
@Composable
fun EmptyState(text: String = "Немає об'єктів, що відповідають умовам") {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔍", style = MaterialTheme.typography.headlineMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge)
        Text("Змініть пошук або фільтри", style = MaterialTheme.typography.bodySmall)
    }
}
