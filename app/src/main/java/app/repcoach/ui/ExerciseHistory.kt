@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.repcoach.core.ChartMetric
import app.repcoach.core.Progression
import app.repcoach.core.Records
import app.repcoach.core.SetType
import app.repcoach.core.Workout

/** Full-screen history for one exercise: its chart, records and every past session, newest first. */
@Composable
fun ExerciseHistoryDialog(exercise: String, workouts: List<Workout>, onDismiss: () -> Unit) {
    var metric by remember { mutableStateOf(ChartMetric.E1RM) }
    val sessions = remember(workouts, exercise) {
        workouts.mapNotNull { w -> w.exercises.firstOrNull { it.name == exercise }?.let { w to it } }.asReversed()
    }
    val records = remember(workouts, exercise) { Records.all(workouts)[exercise].orEmpty() }
    val points = remember(workouts, exercise, metric) { Records.chart(workouts, exercise, metric) }
    val trend = remember(workouts, exercise) { Progression(workouts).trend(exercise) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(exercise, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                    if (trend?.plateau == true) Text("Stalled for 8 weeks", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (sessions.isEmpty()) {
                    item { Text("You haven't done this exercise before.") }
                    return@LazyColumn
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChartMetric.entries.forEach { m -> FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m.label) }) }
                    }
                    if (points.size >= 2) ProgressLineChart(points, Modifier.fillMaxWidth().height(200.dp).padding(top = 8.dp))
                    else Text("One session so far, so no chart yet.", style = MaterialTheme.typography.bodySmall)
                }
                if (records.isNotEmpty()) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Records", style = MaterialTheme.typography.titleMedium)
                            records.forEach { RecordLine(it, showExercise = false) }
                        }
                    }
                }
                item { Text("Past sessions (${sessions.size})", style = MaterialTheme.typography.titleMedium) }
                items(sessions, key = { it.first.id }) { (w, ex) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(formatDate(w.startMillis), fontWeight = FontWeight.SemiBold)
                            Text(ex.sets.joinToString("   ") { s ->
                                "${Progression.fmt(s.weightKg)}×${s.reps}" +
                                    (s.rir?.let { " (${it.toInt()} RIR)" } ?: "") +
                                    if (s.type == SetType.WARMUP) "w" else ""
                            }, style = MaterialTheme.typography.bodyMedium)
                            if (ex.notes.isNotBlank()) Text(ex.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Text("", Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}
