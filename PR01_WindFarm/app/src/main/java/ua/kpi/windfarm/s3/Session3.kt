package ua.kpi.windfarm.s3

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.kpi.windfarm.R
import ua.kpi.windfarm.logic.TurbineSpec
import ua.kpi.windfarm.logic.TurbineStatus
import ua.kpi.windfarm.logic.WindPowerCalculator
import java.util.Locale

private fun Double.fmt(d: Int = 1) = String.format(Locale.US, "%.${d}f", this)

@Composable
private fun TurbineImage(sizeDp: Int = 120, rotation: Float = 0f) {
    Image(
        painter = painterResource(R.drawable.ic_wind_turbine),
        contentDescription = stringResource(R.string.turbine_description),
        modifier = Modifier.size(sizeDp.dp).rotate(rotation)
    )
}

/** Базовий рівень: заголовок, зображення, одна кнопка, що змінює текст. */
@Composable
fun BasicWindUi() {
    var running by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Вітрова турбіна №1", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        TurbineImage()
        Spacer(Modifier.height(12.dp))
        Text(if (running) "Турбіна працює" else "Турбіна зупинена")
        Spacer(Modifier.height(12.dp))
        Button(onClick = { running = !running }) { Text(if (running) "Зупинити" else "Запустити") }
    }
}

/** Середній рівень: кілька кнопок, Row/Column, обробка взаємодії через параметри. */
@Composable
fun MediumWindUi() {
    var speed by remember { mutableDoubleStateOf(6.0) }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Керування моделюванням вітру", style = MaterialTheme.typography.titleLarge)
        TurbineImage(100)
        Text("Швидкість вітру: ${speed.fmt()} м/с", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        // Кнопки передають зміну через лямбда-параметр onChange
        SpeedButtons(onChange = { delta -> speed = (speed + delta).coerceIn(0.0, 30.0) })
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { speed = 0.0 }) { Text("Скинути") }
    }
}

@Composable
private fun SpeedButtons(onChange: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onChange(-1.0) }) { Text("−1 м/с") }
        Button(onClick = { onChange(+1.0) }) { Text("+1 м/с") }
        Button(onClick = { onChange(+5.0) }) { Text("+5 м/с") }
    }
}

/**
 * Просунутий рівень: декілька composable, стан через remember,
 * анімоване обертання ротора, адаптивне розташування (BoxWithConstraints).
 */
@Composable
fun AdvancedWindUi() {
    var speed by remember { mutableDoubleStateOf(8.0) }
    val spec = remember { TurbineSpec() }
    val status = WindPowerCalculator.status(speed, spec)
    val power = WindPowerCalculator.turbinePowerKw(speed, spec)

    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
        // На широкому екрані (планшет / альбомна орієнтація) — дві колонки
        if (maxWidth > 600.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedTurbine(speed, status)
                StatusPanel(speed, power, status, onSpeed = { speed = it })
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedTurbine(speed, status)
                StatusPanel(speed, power, status, onSpeed = { speed = it })
            }
        }
    }
}

/** Ротор обертається тим швидше, чим сильніший вітер; у штиль і бурю — стоїть. */
@Composable
private fun AnimatedTurbine(speed: Double, status: TurbineStatus) {
    val moving = status == TurbineStatus.PARTIAL_LOAD || status == TurbineStatus.RATED
    val period = if (moving) (6000 / speed.coerceAtLeast(1.0)).toInt().coerceIn(200, 4000) else 1_000_000
    val transition = rememberInfiniteTransition(label = "rotor")
    val angle by transition.animateFloat(
        initialValue = 0f, targetValue = if (moving) 360f else 0f,
        animationSpec = infiniteRepeatable(tween(period, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    TurbineImage(160, angle)
}

@Composable
private fun StatusPanel(speed: Double, power: Double, status: TurbineStatus, onSpeed: (Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(Icons.Filled.Air, "Вітер", "${speed.fmt()} м/с · Бофорт ${WindPowerCalculator.beaufort(speed)}")
        InfoCard(Icons.Filled.Bolt, "Потужність", "${power.fmt(0)} кВт")
        InfoCard(Icons.Filled.Warning, "Стан", status.title)
        // weight(1f) ділить ширину порівну, тож 4 кнопки вміщаються на вузькому екрані
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.0 to "Штиль", 8.0 to "Норма", 14.0 to "Номінал", 27.0 to "Буря").forEach { (v, label) ->
                OutlinedButton(
                    onClick = { onSpeed(v) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium)
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Домашнє завдання: текст, зображення, кнопки, логіка — в окремих функціях. */
@Composable
fun WindHomework3() {
    var turbineIndex by remember { mutableIntStateOf(0) }
    val speeds = remember { listOf(4.2, 9.8, 13.5, 26.1, 7.0) } // останні виміри датчиків турбін
    val speed = speeds[turbineIndex]
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Турбіна №${turbineIndex + 1} з ${speeds.size}", style = MaterialTheme.typography.headlineSmall)
        TurbineImage(110)
        Text(describeTurbine(speed), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { turbineIndex = prevIndex(turbineIndex, speeds.size) }) { Text("◀ Попередня") }
            Button(onClick = { turbineIndex = nextIndex(turbineIndex, speeds.size) }) { Text("Наступна ▶") }
        }
    }
}

// Бізнес-логіка домашнього завдання — окремі функції
internal fun describeTurbine(speed: Double): String {
    val spec = TurbineSpec()
    return "Вітер ${speed.fmt()} м/с, ${WindPowerCalculator.turbinePowerKw(speed, spec).fmt(0)} кВт — " +
        WindPowerCalculator.status(speed, spec).title
}

internal fun nextIndex(i: Int, size: Int) = (i + 1) % size
internal fun prevIndex(i: Int, size: Int) = (i - 1 + size) % size
