@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.repcoach.AppViewModel
import app.repcoach.COACH_CHAT
import app.repcoach.Screen
import app.repcoach.UiState
import app.repcoach.core.PlanReview
import app.repcoach.core.PlannedExercise
import app.repcoach.core.Planner
import app.repcoach.core.Progression
import app.repcoach.core.Why
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
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::restoreBackup) }
    val hevy = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importHevy) }
    val autoBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let(vm::enableAutoBackup) }
    val matches = remember(state.plans, state.workouts) { PlanReview.match(state.plans, state.workouts) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Rep Coach", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This week", style = MaterialTheme.typography.titleMedium)
                Text("${p.sessionsThisWeek()} session${if (p.sessionsThisWeek() == 1) "" else "s"} logged in Hevy so far")
                if (state.workouts.isNotEmpty()) {
                    val due = remember(state.workouts, state.programme) { Planner(p, state.programme).dueDay() }
                    Text(due?.let { "Next: ${it.title}" } ?: "Full Body A and B done: any extra days are mixed sessions")
                }
                last?.let {
                    val days = Progression.daysBetween(it.startMillis, System.currentTimeMillis())
                    Text("Last session: ${it.title}, " + when (days) { 0 -> "today"; 1 -> "yesterday"; else -> "$days days ago" })
                }
            }
        }
        if (state.workouts.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bring in your Hevy history", style = MaterialTheme.typography.titleMedium)
                    Text("The coach plans from what you've logged in Hevy. In Hevy: Profile, Settings, Export & Import Data, Export Workouts, then pick that file here. Or restore a Rep Coach backup.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { hevy.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, Modifier.fillMaxWidth()) { Text("Import from Hevy") }
                    OutlinedButton(onClick = { restorer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, Modifier.fillMaxWidth()) { Text("Restore my backup") }
                }
            }
        } else if (state.settings.hevyKey.isBlank()) {
            // Without Hevy Pro there's no sync, so a fresh export keeps the coach up to date.
            val days = last?.let { Progression.daysBetween(it.startMillis, System.currentTimeMillis()) } ?: 0
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (days >= 7) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep the coach up to date", style = MaterialTheme.typography.titleMedium)
                    Text("Import a fresh Hevy export before planning, so the coach sees your latest workouts and can check how each plan went.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { hevy.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }, Modifier.fillMaxWidth()) { Text("Import latest Hevy export") }
                }
            }
        }
        if (state.workouts.isNotEmpty() && !state.autoBackup) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Protect your plans and chats", style = MaterialTheme.typography.titleMedium)
                    Text("Turn on automatic backup so a reinstall never loses them. Save the file in Google Drive or Downloads.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { autoBackup.launch("repcoach-autobackup.json") }, Modifier.fillMaxWidth()) { Text("Turn on automatic backup") }
                }
            }
        }
        Button(onClick = { vm.go(Screen.CHECK_IN) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Plan a session") }
        OutlinedButton(onClick = { vm.go(Screen.COACH) }, Modifier.fillMaxWidth()) { Text("Ask the coach") }
        if (state.plans.isNotEmpty()) {
            Text("Recommended workouts", style = MaterialTheme.typography.titleMedium)
            state.plans.take(12).forEach { saved ->
                val done = matches[saved.id]
                Card(Modifier.fillMaxWidth().clickable { vm.openPlan(saved.id) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text(saved.plan.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Planned ${formatDate(saved.createdMillis)} · " + (done?.let { "done ${formatDate(it.startMillis)}" } ?: "not logged yet") +
                                if (saved.chat.isNotEmpty()) " · ${saved.chat.size} messages" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (done != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val stalled = p.plateaus()
        if (stalled.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Stalled lifts", style = MaterialTheme.typography.titleMedium)
                    stalled.take(4).forEach { Text("${it.exercise}: no gain in 8 weeks") }
                    Text("The coach adds support work for these.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.startQuestion(COACH_CHAT, "What should I do about my stalled ${stalled.first().exercise}?") }) { Text("Ask the coach about this") }
                }
            }
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

private val planStarters = listOf(
    "Explain this workout in plain words",
    "Why these weights?",
    "I've only got 45 minutes",
    "Something's sore: what should I change?",
)

private val doneStarters = listOf(
    "How did I do, and what changes next time?",
    "Why did I fall short on some sets?",
)

@Composable
fun PlanScreen(state: UiState, vm: AppViewModel) {
    val saved = state.openPlan ?: return
    val plan = saved.plan
    val p = remember(state.workouts) { Progression(state.workouts) }
    val done = remember(state.plans, state.workouts, saved.id) { PlanReview.match(state.plans, state.workouts)[saved.id] }
    val comparison = remember(plan, done) { done?.let { PlanReview.compare(plan, it) } }
    var historyFor by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val thinking = state.chatBusy == saved.id
    LaunchedEffect(saved.chat.size, thinking) {
        if (saved.chat.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(plan.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("Back") }
                    }
                    Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Planned ${formatDate(saved.createdMillis)} for energy ${saved.energy}/10 · " +
                                if (plan.source == "claude") "by Claude" else "by the built-in rules",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        TextButton(onClick = { vm.showWhy(Why.forPlan(plan, p)) }) { Text("Why?") }
                    }
                }
            }
            items(plan.exercises) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        Text(prescription(e), fontWeight = FontWeight.SemiBold)
                        if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { vm.showWhy(Why.forExercise(e, p)) }) { Text("Why?") }
                            TextButton(onClick = { vm.startQuestion(saved.id, "About ${e.name}: ") }) { Text("Ask") }
                            TextButton(onClick = { historyFor = e.name }) { Text("History") }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (done == null || comparison == null) {
                            Text("How it went", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Do this workout and log it in Hevy. " + if (state.settings.hevyKey.isNotBlank()) "When it syncs, it appears here next to the plan."
                                    else "Then import a fresh Hevy export (Today tab) and it appears here next to the plan.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("How it went: ${formatDate(done.startMillis)}", style = MaterialTheme.typography.titleMedium)
                            comparison.forEach { c ->
                                Column {
                                    Text(c.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                    Text("Planned ${c.planText}. Did ${c.doneText}.", style = MaterialTheme.typography.bodySmall)
                                    Text(c.verdict, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (state.settings.hevyKey.isNotBlank() && done == null) {
                    OutlinedButton(onClick = { vm.sendPlanToHevy() }, Modifier.fillMaxWidth()) { Text("Send to Hevy as a routine") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.go(Screen.CHECK_IN) }, Modifier.weight(1f)) { Text("Plan another") }
                    OutlinedButton(onClick = { confirmDelete = true }, Modifier.weight(1f)) { Text("Delete") }
                }
            }
            item {
                Text("Ask the coach about this workout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(
                    "It knows this plan, the reasons behind each exercise and your Hevy history. Ask for a change (a swap, less time, a sore joint) and it updates the workout.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            chatMessages(saved.chat, thinking)
            item { Spacer(Modifier.height(8.dp)) }
        }
        ChatInput(
            hasKey = state.settings.apiKey.isNotBlank(),
            busy = state.chatBusy != null,
            error = state.chatError,
            starters = if (saved.chat.isEmpty()) (if (done != null) doneStarters else planStarters) else emptyList(),
            draft = state.draft?.takeIf { it.first == saved.id }?.second,
            onDraftUsed = vm::draftUsed,
            onAsk = { vm.askAboutPlan(saved.id, it) },
        )
    }
    historyFor?.let { ExerciseHistoryDialog(it, state.workouts, onDismiss = { historyFor = null }) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this recommended workout and its chat?") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePlan(saved.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
