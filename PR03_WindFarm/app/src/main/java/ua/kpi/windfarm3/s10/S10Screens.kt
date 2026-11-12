package ua.kpi.windfarm3.s10

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ua.kpi.windfarm3.data.DataOrigin
import ua.kpi.windfarm3.data.upcoming
import ua.kpi.windfarm3.domain.ForecastAnalytics
import ua.kpi.windfarm3.domain.HourlyWind
import ua.kpi.windfarm3.domain.TurbineSpec
import ua.kpi.windfarm3.domain.WindPowerCalculator
import ua.kpi.windfarm3.ui.common.Chips
import ua.kpi.windfarm3.ui.common.FormField
import ua.kpi.windfarm3.ui.common.InfoRow
import ua.kpi.windfarm3.ui.common.Loading
import ua.kpi.windfarm3.ui.common.Message
import ua.kpi.windfarm3.ui.common.ScreenTitle
import ua.kpi.windfarm3.ui.common.SectionCard
import ua.kpi.windfarm3.ui.common.ageText
import ua.kpi.windfarm3.ui.common.fmt

/** Відображення помилки REST-запиту. */
@Composable
private fun ErrorText(state: Load<*>) {
    if (state is Load.Error) Message("${state.error.title}: ${state.error.userMessage}", true)
}

// ============================ БАЗОВИЙ ============================
@Composable
fun BasicRestApp(vm: BasicRestViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ScreenTitle("Поточний вітер (REST API)", "${vm.station.name} · ${vm.station.latitude}, ${vm.station.longitude}")
        Text("GET api.open-meteo.com/v1/forecast", style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::load, enabled = state != Load.Loading, modifier = Modifier.padding(vertical = 8.dp)) { Text("Отримати дані") }
        when (val s = state) {
            Load.Idle -> Text("Натисніть кнопку, щоб надіслати запит")
            Load.Loading -> Loading("Запит до сервера…")
            is Load.Error -> ErrorText(s)
            is Load.Success -> SectionCard("Відповідь сервера (JSON → Gson → модель)") {
                val c = s.data.current
                InfoRow("Час виміру", c.time.replace('T', ' '))
                InfoRow("Часовий пояс", s.data.timezone)
                InfoRow("Швидкість вітру (10 м)", "${c.speed10.fmt()} м/с")
                InfoRow("Пориви", "${c.gusts10.fmt()} м/с")
                InfoRow("Напрям", "${c.directionDeg.fmt(0)}° (${c.directionName})")
                InfoRow("Температура", "${c.temperature.fmt()} °C")
                InfoRow("Бофорт", "${WindPowerCalculator.beaufort(c.speed10)} — ${WindPowerCalculator.beaufortName(WindPowerCalculator.beaufort(c.speed10))}")
                InfoRow("Годин у прогнозі", "${s.data.hourly.size}")
            }
        }
    }
}

// ============================ СЕРЕДНІЙ ============================
@Composable
fun MediumRestApp(vm: MediumRestViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val forecast by vm.forecast.collectAsStateWithLifecycle()
    val hours by vm.hours.collectAsStateWithLifecycle()
    val cached by vm.fromCache.collectAsStateWithLifecycle()
    val only by vm.productiveOnly.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Прогноз вітру за назвою міста", "Ресурси: геокодування + прогноз")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormField("Населений пункт", query, { vm.query.value = it }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.search() }) { Text("Шукати") }
            }
            Chips(listOf("Одеса", "Запоріжжя", "Херсон", "Миколаїв", "Львів"), null, { it }) { vm.search(it) }
            when (val p = places) {
                Load.Idle -> {}
                Load.Loading -> Loading("Пошук…")
                is Load.Error -> ErrorText(p)
                is Load.Success -> if (p.data.isEmpty()) Message("Нічого не знайдено", true) else
                    Chips(p.data, selected, { "${it.name}, ${it.region}" }) { vm.select(it) }
            }
            if (cached) Text("⚡ Відповідь узято з кешу (без запиту до сервера)", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        item {
            when (val f = forecast) {
                Load.Idle -> {}
                Load.Loading -> Loading("Завантаження прогнозу…")
                is Load.Error -> ErrorText(f)
                is Load.Success -> {
                    val c = f.data.current
                    val e = ForecastAnalytics.forecast(f.data.upcoming(48), 1)
                    SectionCard(selected?.let { "${it.name} (${it.country})" }) {
                        InfoRow("Зараз на 10 м", "${c.speed10.fmt()} м/с, ${c.directionName}")
                        InfoRow("Середній вітер на 100 м (48 год)", "${e.avgSpeed.fmt()} м/с")
                        InfoRow("Виробіток 1 турбіни за 48 год", "${e.energyMwh.fmt(1)} МВт·год")
                        InfoRow("Годин з генерацією", "${e.productiveHours} з ${e.hours}")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(only, { vm.productiveOnly.value = it }); Text("  лише години з генерацією")
                    }
                    Chips(ForecastAnalytics.Sort.entries, sort, { it.title }) { vm.sort.value = it }
                    HourHeader()
                }
            }
        }
        items(hours, key = { it.time }) { HourRow(it, turbines = 1) }
    }
}

@Composable
private fun HourHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        listOf("Час" to 1.3f, "Вітер 100 м" to 1f, "Потужність" to 1f, "Стан" to 1.4f).forEach { (t, w) ->
            Text(t, Modifier.weight(w), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
    HorizontalDivider()
}

@Composable
private fun HourRow(h: HourlyWind, turbines: Int) {
    val spec = TurbineSpec()
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${h.dateLabel.takeLast(5)} ${h.hourLabel}", Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
        Text("${h.speed100.fmt()} м/с", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(ForecastAnalytics.powerMw(h, turbines).let { if (turbines == 1) "${(it * 1000).fmt(0)} кВт" else "${it.fmt(1)} МВт" },
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(WindPowerCalculator.status(h.speed100, spec).title, Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
    }
}

// ============================ ПРОСУНУТИЙ ============================
@Composable
fun AdvancedRestApp(vm: AdvancedRestViewModel = viewModel()) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val total by vm.totalEnergy24.collectAsStateWithLifecycle()
    val offline by vm.offlineCount.collectAsStateWithLifecycle()

    LazyVerticalGrid(GridCells.Adaptive(300.dp), Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenTitle("Прогноз виробітку станцій", "Паралельні запити · повтори · кеш Room")
                SectionCard {
                    InfoRow("Сумарний виробіток за 24 год", "${total.fmt(0)} МВт·год")
                    if (offline > 0) Message("Офлайн: $offline ст. показано з кешу", true)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.refresh(true) }, enabled = !loading) { Text("Оновити") }
                        if (loading) Loading("Запити до API…")
                    }
                }
            }
        }
        items(cards, key = { it.station.id }) { ForecastCard(it) }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ForecastCard(c: StationForecastCard) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (c.state is Load.Error) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp)) {
            Text(c.station.name, style = MaterialTheme.typography.titleMedium)
            Text("${c.station.region} · ${c.station.installedMw.fmt(0)} МВт", style = MaterialTheme.typography.bodySmall)
            when (val s = c.state) {
                Load.Idle -> {}
                Load.Loading -> Loading(if (c.attempt > 1) "Спроба ${c.attempt}…" else "Завантаження…")
                is Load.Error -> Message("${s.error.title}: ${s.error.userMessage}", true)
                is Load.Success -> {
                    val e = c.energy24
                    PowerBars(c.next24, c.station.turbines, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 6.dp))
                    if (e != null) {
                        InfoRow("Виробіток 24 год / 48 год", "${e.energyMwh.fmt(0)} / ${(c.energy48Mwh ?: 0.0).fmt(0)} МВт·год")
                        InfoRow("КВВП на добу", "${e.capacityFactor.fmt(0)} %")
                        e.peak?.let { InfoRow("Пік вітру", "${it.speed100.fmt()} м/с о ${it.hourLabel}") }
                        if (e.stormHours > 0) Message("Годин з аварійною зупинкою: ${e.stormHours}", true)
                    }
                    c.maintenance?.let { w -> InfoRow("Вікно для ТО", "${w.first().hourLabel}–${w.last().hourLabel} (${w.map { it.speed100 }.average().fmt()} м/с)") }
                    val origin = s.data.origin
                    Text("Джерело: ${origin.title}, ${ageText(s.data.fetchedAt)}" + if (c.attempt > 1) " · спроб: ${c.attempt}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (origin == DataOrigin.STALE_CACHE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/** Стовпчикова діаграма потужності станції на найближчі години (Canvas). */
@Composable
private fun PowerBars(hours: List<HourlyWind>, turbines: Int, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val storm = MaterialTheme.colorScheme.error
    val maxMw = turbines * TurbineSpec().ratedPowerKw / 1000.0
    Canvas(modifier) {
        if (hours.isEmpty()) return@Canvas
        val w = size.width / hours.size
        hours.forEachIndexed { i, h ->
            val p = ForecastAnalytics.powerMw(h, turbines)
            val bh = (p / maxMw).toFloat().coerceIn(0f, 1f) * size.height
            val color = if (h.speed100 >= TurbineSpec().cutOutSpeed) storm else primary
            if (bh > 0f) drawRect(color, Offset(i * w + 1f, size.height - bh), Size(w - 2f, bh))
            else drawRect(color.copy(alpha = 0.25f), Offset(i * w + 1f, size.height - 3f), Size(w - 2f, 3f))
        }
    }
}

// ============================ ДОМАШНЄ ============================
@Composable
fun HomeworkRestApp(vm: HomeworkRestViewModel = viewModel()) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val energy by vm.energy.collectAsStateWithLifecycle()
    val only by vm.productiveOnly.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            ScreenTitle("Погодинний прогноз станції", "Retrofit + Gson · 24 години")
            Chips(vm.stations, selected, { it.name.removePrefix("ВЕС ") }) { vm.select(it) }
            when (val s = state) {
                Load.Idle -> {}
                Load.Loading -> Loading()
                is Load.Error -> Column {
                    ErrorText(s)
                    OutlinedButton(onClick = { vm.load(true) }) { Text("Повторити") }
                }
                is Load.Success -> {
                    energy?.let { e ->
                        SectionCard("${selected.name} · ${selected.turbines} турбін") {
                            InfoRow("Виробіток за 24 год", "${e.energyMwh.fmt(0)} МВт·год")
                            InfoRow("Середній вітер 100 м", "${e.avgSpeed.fmt()} м/с")
                            InfoRow("Годин з генерацією", "${e.productiveHours} з ${e.hours}")
                            Text("Дані: ${s.data.origin.title}, ${ageText(s.data.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(only, { vm.productiveOnly.value = !only }, { Text("Лише з генерацією") })
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { vm.load(true) }) { Text("Оновити") }
                    }
                    Chips(ForecastAnalytics.Sort.entries, sort, { it.title }) { vm.sort.value = it }
                    HourHeader()
                }
            }
        }
        items(rows, key = { it.time }) { HourRow(it, selected.turbines) }
    }
}
