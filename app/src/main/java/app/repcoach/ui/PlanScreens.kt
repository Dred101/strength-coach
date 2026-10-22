@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.repcoach.AppViewModel
import app.repcoach.Screen
import app.repcoach.UiState
import app.repcoach.core.PlannedExercise
import app.repcoach.core.Progression
import app.repcoach.core.Recovery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)

internal fun formatDate(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFormat)

internal fun kg(weight: Double?): String = weight?.let { Progression.fmt(it) + " kg" } ?: "your choice"

internal fun prescription(e: PlannedExercise): String {
    val reps = if (e.repsMin == e.repsMax) "${e.repsMin}" else "${e.repsMin}–${e.repsMax}"
    return "${e.sets} × $reps @ ${kg(e.weightKg)}, ${e.targetRir} in reserve"
}

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    val p = Progression(state.workouts)
    val last = state.workouts.lastOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Rep Coach", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Text("${p.sessionsThisWeek()} of 3 full-body sessions done")
                last?.let {
                    val days = Progression.daysBetween(it.startMillis, System.currentTimeMillis())
                    Text("Last session: ${it.title}, " + when (days) { 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" })
                }
                if (state.workouts.isEmpty()) Text("No history yet. In Settings, import your Hevy export or restore a backup to give the coach something to work from.")
            }
        }
        RecoveryCard(state.recovery, state.healthStatus)
        if (state.active != null) {
            Button(onClick = { vm.go(Screen.WORKOUT) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Back to your workout") }
        } else {
            Button(onClick = { vm.go(Screen.CHECK_IN) }, Modifier.fillMaxWidth().height(56.dp)) { Text("I'm at the gym") }
            OutlinedButton(onClick = { vm.startEmptyWorkout() }, Modifier.fillMaxWidth()) { Text("Log a workout without a plan") }
        }
        val stalled = p.plateaus()
        if (stalled.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Stalled lifts", style = MaterialTheme.typography.titleMedium)
                    stalled.take(4).forEach { Text("${it.exercise}: no gain in 8 weeks") }
                    Text("The coach adds support work for these.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun RecoveryCard(r: Recovery?, status: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recovery", style = MaterialTheme.typography.titleMedium)
            val lines = listOfNotNull(
                r?.sleepHours?.let { "Sleep: %.1f h".format(Locale.UK, it) },
                r?.restingHr?.let { "Resting heart rate: ${it.toInt()} bpm" + (r.restingHrBaseline?.let { b -> " (usually ${b.toInt()})" } ?: "") },
                r?.hrvMs?.let { "HRV: ${it.toInt()} ms" + (r.hrvBaseline?.let { b -> " (usually ${b.toInt()})" } ?: "") },
                r?.stepsYesterday?.let { "Steps yesterday: $it" },
            )
            if (lines.isEmpty()) Text("Health Connect: $status") else lines.forEach { Text(it) }
        }
    }
}

@Composable
fun CheckInScreen(state: UiState, vm: AppViewModel) {
    val c = state.checkIn
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("How are you feeling today?", style = MaterialTheme.typography.headlineSmall)
        Text("Energy out of 10", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..10).forEach { n ->
                FilterChip(selected = c.energy == n, onClick = { vm.setCheckIn(c.copy(energy = n)) }, label = { Text("$n") })
            }
        }
        Text("How long have you got?", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 60, 75, 90).forEach { m ->
                FilterChip(selected = c.minutes == m, onClick = { vm.setCheckIn(c.copy(minutes = m)) }, label = { Text("$m min") })
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { vm.makePlan() }, Modifier.fillMaxWidth().height(56.dp)) { Text("Plan my session") }
        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("Cancel") }
    }
}

@Composable
fun PlanScreen(state: UiState, vm: AppViewModel) {
    val plan = state.plan ?: return
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                Text(plan.title, style = MaterialTheme.typography.headlineSmall)
                Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (plan.source == "claude") "Planned by Claude" else "Planned by the built-in rules",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        items(plan.exercises) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text(prescription(e), fontWeight = FontWeight.SemiBold)
                    if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.makePlan() }, Modifier.weight(1f)) { Text("Re-plan") }
                Button(onClick = { vm.startWorkout() }, Modifier.weight(1f).height(56.dp)) { Text("Start") }
            }
        }
    }
}
