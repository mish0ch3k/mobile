package ua.kpi.windfarm.s1

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ua.kpi.windfarm.R
import ua.kpi.windfarm.logic.TurbineSpec
import ua.kpi.windfarm.logic.TurbineStatus
import ua.kpi.windfarm.logic.WindPowerCalculator
import java.util.Locale
import kotlin.random.Random

/** Імітація датчика: швидкість вітру 0…28 м/с з одним знаком після коми. */
private fun readWindSensor(): Double = (Random.nextDouble(0.0, 28.0) * 10).toInt() / 10.0

private fun Double.fmt(digits: Int = 1) = String.format(Locale.US, "%.${digits}f", this)

/** Базовий рівень: заголовок, текст, кнопка, колір фону. */
@Composable
fun WindMonitorLevel1() {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFE3F2FD)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hello Wind Power", fontSize = 28.sp, color = Color.Black)
        Spacer(Modifier.height(8.dp))
        Text("Вітрова електростанція з моніторингом швидкості вітру", fontSize = 16.sp, color = Color.DarkGray)
        Spacer(Modifier.height(24.dp))
        // На базовому рівні кнопка ще не змінює стан
        Button(onClick = { }) { Text("Оновити дані") }
    }
}

/** Середній рівень: змінні дані, випадкове оновлення, Column + Row. */
@Composable
fun WindMonitorLevel2() {
    var windSpeed by remember { mutableDoubleStateOf(0.0) }
    val spec = remember { TurbineSpec() }
    val power = WindPowerCalculator.turbinePowerKw(windSpeed, spec)

    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF1F8E9)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Вітрова електростанція", fontSize = 24.sp, color = Color.Black)
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("Швидкість вітру", "${windSpeed.fmt()} м/с")
            Metric("Потужність", "${power.fmt(0)} кВт")
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { windSpeed = readWindSensor() }) { Text("Оновити дані датчика") }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = Color.Gray)
        Text(value, fontSize = 22.sp, color = Color.DarkGray)
    }
}

/** Просунутий рівень: анімація значень, State, MaterialTheme. */
@Composable
fun WindMonitorLevel3() {
    var windSpeed by remember { mutableDoubleStateOf(0.0) }
    val spec = remember { TurbineSpec() }
    val power = WindPowerCalculator.turbinePowerKw(windSpeed, spec)
    val status = WindPowerCalculator.status(windSpeed, spec)

    // Плавні переходи між значеннями
    val animSpeed by animateFloatAsState(windSpeed.toFloat(), tween(800), label = "speed")
    val animPower by animateFloatAsState(power.toFloat(), tween(800), label = "power")
    val statusColor by animateColorAsState(
        when (status) {
            TurbineStatus.IDLE -> MaterialTheme.colorScheme.secondary
            TurbineStatus.PARTIAL_LOAD -> MaterialTheme.colorScheme.primary
            TurbineStatus.RATED -> MaterialTheme.colorScheme.tertiary
            TurbineStatus.STORM_STOP -> MaterialTheme.colorScheme.error
        }, label = "status"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Вітрова електростанція", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                Text("Швидкість вітру: ${animSpeed.toDouble().fmt()} м/с", style = MaterialTheme.typography.titleMedium)
                Text("Потужність турбіни: ${animPower.toDouble().fmt(0)} кВт", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (animPower / spec.ratedPowerKw).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor
                )
                Spacer(Modifier.height(8.dp))
                Text("Стан: ${status.title}", color = statusColor, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { windSpeed = readWindSensor() }) { Text("Оновити дані датчика") }
    }
}

/** Домашнє завдання: заголовок, основний і другий показник, Column/Row, іконка, MaterialTheme. */
@Composable
fun WindHomework1() {
    var windSpeed by remember { mutableDoubleStateOf(7.5) }
    val beaufort = WindPowerCalculator.beaufort(windSpeed)

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_wind_turbine),
            contentDescription = stringResource(R.string.turbine_description),
            modifier = Modifier.size(96.dp)
        )
        Text("Моніторинг вітру", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Швидкість", style = MaterialTheme.typography.labelLarge)
                Text("${windSpeed.fmt()} м/с", style = MaterialTheme.typography.titleLarge)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Бофорт", style = MaterialTheme.typography.labelLarge)
                Text("$beaufort — ${WindPowerCalculator.beaufortName(beaufort)}", style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = { windSpeed = readWindSensor() }) { Text("Оновити") }
    }
}
