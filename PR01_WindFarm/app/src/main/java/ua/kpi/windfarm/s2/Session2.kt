package ua.kpi.windfarm.s2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberChartScrollSpec
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf
import ua.kpi.windfarm.logic.TurbineSpec
import ua.kpi.windfarm.logic.WindPowerCalculator
import java.util.Locale

private fun Double.fmt(d: Int = 1) = String.format(Locale.US, "%.${d}f", this)

/**
 * Поле введення числа з валідацією.
 * Повертає null через onValue, якщо текст не є числом у межах [range].
 */
@Composable
private fun NumberField(
    label: String,
    text: String,
    onText: (String) -> Unit,
    range: ClosedFloatingPointRange<Double>,
    modifier: Modifier = Modifier
) {
    val value = text.replace(',', '.').toDoubleOrNull()
    val error = when {
        value == null -> "Введіть число"
        value !in range -> "Діапазон ${range.start.fmt(0)}…${range.endInclusive.fmt(0)}"
        else -> null
    }
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        label = { Text(label) },
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

private fun String.num(range: ClosedFloatingPointRange<Double>): Double? =
    replace(',', '.').toDoubleOrNull()?.takeIf { it in range }

@Composable
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

/** Базовий рівень: один параметр (швидкість вітру) → потужність турбіни. */
@Composable
fun BasicEnergyApp() {
    var speed by remember { mutableStateOf("8") }
    val v = speed.num(0.0..60.0)
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Потужність вітрової турбіни", style = MaterialTheme.typography.titleLarge)
        NumberField("Швидкість вітру, м/с", speed, { speed = it }, 0.0..60.0)
        // Розрахунок виконується у WindPowerCalculator (окремий файл logic/)
        val p = v?.let { WindPowerCalculator.turbinePowerKw(it, TurbineSpec()) }
        Text(
            if (p == null) "Перевірте введені дані" else "Потужність: ${p.fmt(0)} кВт",
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

/** Середній рівень: декілька параметрів, логіка у окремому файлі. */
@Composable
fun MediumEnergyApp() {
    var speed by remember { mutableStateOf("10") }
    var radius by remember { mutableStateOf("45") }
    var cp by remember { mutableStateOf("0.42") }
    var temp by remember { mutableStateOf("15") }
    var count by remember { mutableStateOf("20") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Розрахунок вітрової електростанції", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Вітер, м/с", speed, { speed = it }, 0.0..60.0, Modifier.weight(1f))
            NumberField("Радіус, м", radius, { radius = it }, 1.0..150.0, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Cp", cp, { cp = it }, 0.01..0.59, Modifier.weight(1f))
            NumberField("Темп., °C", temp, { temp = it }, -40.0..50.0, Modifier.weight(1f))
        }
        NumberField("Кількість турбін", count, { count = it }, 1.0..500.0)

        val v = speed.num(0.0..60.0)
        val r = radius.num(1.0..150.0)
        val c = cp.num(0.01..0.59)
        val t = temp.num(-40.0..50.0)
        val n = count.num(1.0..500.0)?.toInt()
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                if (v == null || r == null || c == null || t == null || n == null) {
                    Text("Виправте помилки у полях", color = MaterialTheme.colorScheme.error)
                } else {
                    // Номінальну потужність масштабуємо від площі ротора (≈ 0.31 кВт/м²)
                    val spec = TurbineSpec(rotorRadius = r, ratedPowerKw = WindPowerCalculator.sweptArea(r) * 0.31, powerCoefficient = c)
                    val pTurbine = WindPowerCalculator.turbinePowerKw(v, spec, t)
                    ResultLine("Густина повітря", "${WindPowerCalculator.airDensity(t).fmt(3)} кг/м³")
                    ResultLine("Площа ротора", "${WindPowerCalculator.sweptArea(r).fmt(0)} м²")
                    ResultLine("Номінал турбіни", "${spec.ratedPowerKw.fmt(0)} кВт")
                    ResultLine("Потужність турбіни", "${pTurbine.fmt(0)} кВт")
                    ResultLine("Потужність станції", "${WindPowerCalculator.farmPowerMw(v, spec, n, t).fmt(2)} МВт")
                    ResultLine("КВВП", "${WindPowerCalculator.capacityFactor(pTurbine, spec).fmt(0)} %")
                    ResultLine("Стан", WindPowerCalculator.status(v, spec).title)
                }
            }
        }
    }
}

/**
 * Просунутий рівень: масштабована структура (логіка — logic/, UI — тут),
 * стороння бібліотека Vico для графіка кривої потужності P(v).
 * Слайдер змінює стан → перераховуються значення → Compose перемальовує UI.
 */
@Composable
fun AdvancedEnergyApp() {
    var speed by remember { mutableFloatStateOf(9f) }
    var cp by remember { mutableFloatStateOf(0.42f) }
    val spec = remember(cp) { TurbineSpec(powerCoefficient = cp.toDouble()) }

    // Точки кривої потужності 0…27 м/с кроком 1 м/с
    val model = remember(spec) {
        entryModelOf((0..27).map { v -> entryOf(v.toFloat(), WindPowerCalculator.turbinePowerKw(v.toDouble(), spec).toFloat()) })
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Крива потужності турбіни 2 МВт", style = MaterialTheme.typography.titleLarge)
        Text("Швидкість вітру: ${speed.toDouble().fmt()} м/с")
        Slider(value = speed, onValueChange = { speed = it }, valueRange = 0f..30f)
        Text("Коефіцієнт Cp: ${cp.toDouble().fmt(2)}")
        Slider(value = cp, onValueChange = { cp = it }, valueRange = 0.2f..0.59f)
        val p = WindPowerCalculator.turbinePowerKw(speed.toDouble(), spec)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                ResultLine("Потужність", "${p.fmt(0)} кВт")
                ResultLine("Бофорт", "${WindPowerCalculator.beaufort(speed.toDouble())} — ${WindPowerCalculator.beaufortName(WindPowerCalculator.beaufort(speed.toDouble()))}")
                ResultLine("Стан", WindPowerCalculator.status(speed.toDouble(), spec).title)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("P, кВт  від  v, м/с", style = MaterialTheme.typography.labelLarge)
        Chart(
            // 28 точок по 11 dp вміщаються в ширину екрана, тож прокрутку вимкнено
            chart = lineChart(spacing = 11.dp),
            model = model,
            startAxis = rememberStartAxis(itemPlacer = AxisItemPlacer.Vertical.default(maxItemCount = 5)),
            bottomAxis = rememberBottomAxis(itemPlacer = AxisItemPlacer.Horizontal.default(spacing = 5)),
            chartScrollSpec = rememberChartScrollSpec(isScrollEnabled = false),
            modifier = Modifier.fillMaxWidth().height(220.dp)
        )
    }
}

/** Домашнє завдання: введення параметрів турбіни та станції, результати розрахунків. */
@Composable
fun WindHomework2() {
    var speed by remember { mutableStateOf("12") }
    var count by remember { mutableStateOf("10") }
    var hours by remember { mutableStateOf("24") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Добовий виробіток вітрової ЕС", style = MaterialTheme.typography.titleLarge)
        NumberField("Середня швидкість вітру, м/с", speed, { speed = it }, 0.0..60.0)
        NumberField("Кількість турбін 2 МВт", count, { count = it }, 1.0..500.0)
        NumberField("Тривалість, год", hours, { hours = it }, 1.0..8760.0)
        val v = speed.num(0.0..60.0); val n = count.num(1.0..500.0)?.toInt(); val h = hours.num(1.0..8760.0)
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                if (v == null || n == null || h == null) {
                    Text("Виправте помилки у полях", color = MaterialTheme.colorScheme.error)
                } else {
                    val spec = TurbineSpec()
                    val mw = WindPowerCalculator.farmPowerMw(v, spec, n)
                    ResultLine("Потужність станції", "${mw.fmt(2)} МВт")
                    ResultLine("Виробіток", "${(mw * h).fmt(1)} МВт·год")
                    // Домогосподарство споживає ≈ 10 кВт·год (0.01 МВт·год) на добу
                    ResultLine("Забезпечено домівок", "${(mw * h / (0.01 * h / 24)).toInt()}")
                }
            }
        }
    }
}
