@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.repcoach.UiState
import app.repcoach.core.ChartMetric
import app.repcoach.core.PersonalRecord
import app.repcoach.core.Progression
import app.repcoach.core.Records
import java.time.Duration
import java.util.Locale

private enum class Range(val label: String, val days: Long?) { M3("3 months", 91), M6("6 months", 182), Y1("1 year", 365), ALL("All", null) }

@Composable
fun ProgressScreen(state: UiState) {
    val p = remember(state.workouts) { Progression(state.workouts) }
    val weekly = remember(state.workouts) { Records.weeklySets(state.workouts, 12) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var metric by rememberSaveable { mutableStateOf(ChartMetric.E1RM) }
    var range by rememberSaveable { mutableStateOf(Range.M6) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Progress", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Hard sets per week", style = MaterialTheme.typography.titleMedium)
                    Text("Last 12 weeks, week starting", style = MaterialTheme.typography.bodySmall)
                    BarChart(weekly, Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lifts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text("Tap a lift to see its chart. Gold dots are new records.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartMetric.entries.forEach { m -> FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.label) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Range.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                }
            }
        }
        items(p.exerciseNames().take(40), key = { it }) { name ->
            val t = p.trend(name)
            val history = p.exposures[name].orEmpty()
            Card(Modifier.fillMaxWidth().clickable { selected = if (selected == name) null else name }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text(
                            (history.lastOrNull()?.bestE1rm?.toInt()?.let { "$it kg" } ?: "") +
                                (t?.changePercent?.let { "  %+.0f%%".format(Locale.UK, it) } ?: ""),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (t?.plateau == true) Text("Stalled for 8 weeks", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (selected == name) {
                        val from = range.days?.let { System.currentTimeMillis() - Duration.ofDays(it).toMillis() } ?: 0L
                        val points = remember(state.workouts, name, metric, range) { Records.chart(state.workouts, name, metric, from) }
                        if (points.size >= 2) {
                            ProgressLineChart(points, Modifier.fillMaxWidth().height(200.dp).padding(top = 12.dp))
                            val change = points.last().value - points.first().value
                            Text(
                                "${metric.label}: ${Progression.fmt(points.first().value.oneDp())} → ${Progression.fmt(points.last().value.oneDp())} ${metric.unit} " +
                                    "(%+.1f) over ${points.size} sessions".format(Locale.UK, change),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("Not enough sessions in this range for a chart.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun Double.oneDp(): Double = Math.round(this * 10) / 10.0

@Composable
fun RecordsScreen(state: UiState) {
    val all = remember(state.workouts) { Records.all(state.workouts) }
    val order = remember(state.workouts) { Progression(state.workouts).exerciseNames() }
    val recentCutoff = System.currentTimeMillis() - Duration.ofDays(30).toMillis()
    val recent = all.values.flatten().filter { it.achievedMillis >= recentCutoff }.sortedByDescending { it.achievedMillis }
    var open by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Personal records", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        if (all.isEmpty()) item { Text("Log or import some workouts to see your records.") }
        if (recent.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("New in the last 30 days", style = MaterialTheme.typography.titleMedium)
                    recent.take(8).forEach { RecordLine(it, showExercise = true) }
                    if (recent.size > 8) Text("and ${recent.size - 8} more", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(order.filter { it in all }, key = { it }) { name ->
            val records = all.getValue(name)
            val headline = records.firstOrNull { it.kind == app.repcoach.core.RecordKind.E1RM }
            Card(Modifier.fillMaxWidth().clickable { open = if (open == name) null else name }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        headline?.let { Text("e1RM ${Progression.fmt(it.value)} kg", fontWeight = FontWeight.SemiBold) }
                    }
                    if (open == name) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        records.forEach { RecordLine(it, showExercise = false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordLine(r: PersonalRecord, showExercise: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(RecordGold, CircleShape))
        Column(Modifier.weight(1f)) {
            Text((if (showExercise) "${r.exercise}: " else "") + r.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(r.detail + " · " + formatDate(r.achievedMillis).substringBefore(","), style = MaterialTheme.typography.bodySmall)
        }
    }
}
