@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.repcoach.ActiveWorkout
import app.repcoach.AppViewModel
import app.repcoach.UiState
import app.repcoach.core.Exercises
import app.repcoach.core.LoggedSet
import app.repcoach.core.PlannedExercise
import app.repcoach.core.Progression
import app.repcoach.core.SetPlan
import app.repcoach.core.SetType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WorkoutScreen(state: UiState, vm: AppViewModel) {
    val active = state.active ?: return
    var confirmFinish by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Keep the screen on while training so the rest timer stays visible.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize()) {
        RestTimer(active.restEndsAt, onSkip = vm::skipRest, onAdd = { vm.addRest(30) })
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SessionHeader(active)
            }
            items(active.plan.exercises, key = { it.name }) { e ->
                val logged = active.logged[e.name].orEmpty()
                val badges = remember(state.workouts, logged) {
                    logged.indices.map { i -> vm.brokenRecords(e.name, logged.take(i), logged[i]).joinToString(", ") { it.title } }
                }
                ExerciseCard(
                    e = e,
                    logged = logged,
                    badges = badges,
                    onLog = { set ->
                        vm.logSet(e.name, set, e.restSeconds)
                        // Once the last working set is in, scroll on to the next exercise still to do.
                        val working = logged.count { it.type != SetType.WARMUP } + if (set.type != SetType.WARMUP) 1 else 0
                        if (set.type != SetType.WARMUP && working == e.sets) {
                            val after = active.logged + (e.name to logged + set)
                            val next = SetPlan.nextUnfinished(active.plan.exercises, after, e.name)
                            val target = next?.let { n -> active.plan.exercises.indexOf(n) + 1 } ?: (active.plan.exercises.size + 1)
                            scope.launch { listState.animateScrollToItem(target) }
                        }
                    },
                    onUndo = { vm.removeLastSet(e.name) },
                    onSwap = { vm.swapExercise(e.name) },
                    onHistory = { historyFor = e.name },
                )
            }
            item {
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adding = true }, Modifier.fillMaxWidth()) { Text("Add an exercise") }
                    Button(onClick = { confirmFinish = true }, Modifier.fillMaxWidth().height(56.dp)) { Text("Finish workout") }
                }
            }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish this workout?") },
            text = {
                val minutes = (System.currentTimeMillis() - active.startMillis) / 60_000
                Text("${active.logged.values.sumOf { it.size }} sets, ${Progression.fmt(sessionVolume(active).toDouble())} kg lifted in $minutes min.")
            },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finishWorkout() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFinish = false; vm.discardWorkout() }) { Text("Discard") }
                    TextButton(onClick = { confirmFinish = false }) { Text("Keep going") }
                }
            },
        )
    }

    historyFor?.let { ExerciseHistoryDialog(it, state.workouts, onDismiss = { historyFor = null }) }

    if (adding) AddExerciseDialog(state, onPick = { adding = false; vm.addExercise(it) }, onDismiss = { adding = false })
}

private fun sessionVolume(active: ActiveWorkout): Long =
    active.logged.values.flatten().filter { it.type != SetType.WARMUP }.sumOf { it.weightKg * it.reps }.toLong()

/** Session title with a running clock and the total weight lifted so far. */
@Composable
private fun SessionHeader(active: ActiveWorkout) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.startMillis) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val elapsed = ((now - active.startMillis) / 1000).coerceAtLeast(0)
    val clock = if (elapsed >= 3600) "%d:%02d:%02d".format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60) else "%d:%02d".format(elapsed / 60, elapsed % 60)
    Column(Modifier.padding(top = 8.dp)) {
        Text(active.plan.title, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("⏱ $clock" + (active.plan.estimatedMinutes?.let { " of ~$it min" } ?: ""), fontWeight = FontWeight.SemiBold)
            Text("${"%,d".format(sessionVolume(active))} kg lifted", fontWeight = FontWeight.SemiBold)
        }
        Text("Started ${formatDate(active.startMillis)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RestTimer(endsAt: Long?, onSkip: () -> Unit, onAdd: () -> Unit) {
    if (endsAt == null) return
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        var buzzed = false
        while (true) {
            now = System.currentTimeMillis()
            if (now >= endsAt && !buzzed) { vibrate(context); buzzed = true }
            if (now >= endsAt + 60_000) break
            delay(250)
        }
    }
    val left = ((endsAt - now) / 1000).toInt()
    val ready = left <= 0
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (ready) "Go: next set" else "Rest", style = MaterialTheme.typography.titleMedium,
                    color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (ready) "Rested ${-left}s extra" else "%d:%02d".format(left / 60, left % 60),
                    fontSize = if (ready) 16.sp else 36.sp, fontWeight = FontWeight.Bold,
                    color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ready) TextButton(onClick = onAdd) { Text("+30s") }
            TextButton(onClick = onSkip) {
                Text(if (ready) "Hide" else "Skip", color = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun vibrate(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
}

@Composable
private fun ExerciseCard(
    e: PlannedExercise,
    logged: List<LoggedSet>,
    badges: List<String>,
    onLog: (LoggedSet) -> Unit,
    onUndo: () -> Unit,
    onSwap: () -> Unit,
    onHistory: () -> Unit,
) {
    val lastSet = logged.lastOrNull()
    val upcoming = remember(e, logged) { SetPlan.remaining(e, logged) }
    val warmups = remember(e) { SetPlan.warmups(e) }
    val next = upcoming.firstOrNull()
    // Each logged set re-plans the rest, so the inputs refill with the next suggested set.
    var weight by remember(e.name, logged.size) {
        mutableStateOf((next?.weightKg ?: lastSet?.weightKg?.takeIf { it > 0 })?.let { Progression.fmt(it) } ?: "")
    }
    var reps by remember(e.name, logged.size) { mutableStateOf((next?.reps ?: lastSet?.reps ?: e.repsMax).toString()) }
    var rir by remember(e.name) { mutableIntStateOf(e.targetRir) }
    val done = logged.count { it.type != SetType.WARMUP } >= e.sets

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).clickable(onClick = onHistory))
                TextButton(onClick = onHistory) { Text("History") }
                if (logged.isEmpty()) TextButton(onClick = onSwap) { Text("Swap") }
            }
            Text(
                "${e.sets} sets of ${if (e.repsMin == e.repsMax) "${e.repsMin}" else "${e.repsMin}–${e.repsMax}"} reps, " +
                    "${e.targetRir} in reserve, rest ${e.restSeconds / 60}:%02d".format(e.restSeconds % 60),
                fontWeight = FontWeight.SemiBold,
            )
            if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodySmall)
            Text("Reps: " + Exercises.info(e.name).repReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Rest: " + SetPlan.restReason(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (warmups.isNotEmpty() && logged.isEmpty()) {
                Text("Warm-up: " + warmups.joinToString(", ") { "${kg(it.weightKg)} × ${it.reps}" }, style = MaterialTheme.typography.bodyMedium)
                Text(warmups.first().note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            var workingNumber = 0
            logged.forEachIndexed { i, s ->
                val label = if (s.type == SetType.WARMUP) "Warm-up" else "Set ${++workingNumber}"
                Text("✓ $label: ${Progression.fmt(s.weightKg)} kg × ${s.reps}" + (s.rir?.let { ", ${it.toInt()} in reserve" } ?: ""))
                badges.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    Text("🏆 New record: $it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }
            upcoming.forEachIndexed { i, s ->
                Column {
                    Text(
                        "Set ${workingNumber + i + 1}: ${kg(s.weightKg)} × ${s.reps}",
                        fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { weight = stepWeight(weight, -1) }) { Text("−2.5") }
                OutlinedTextField(
                    value = weight, onValueChange = { weight = it.replace(',', '.') }, label = { Text("kg") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { weight = stepWeight(weight, 1) }) { Text("+2.5") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = reps, onValueChange = { reps = it.filter(Char::isDigit) }, label = { Text("reps") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Text("Reps left in the tank", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..4).forEach { n ->
                    FilterChip(selected = rir == n, onClick = { rir = n }, label = { Text(if (n == 4) "4+" else "$n") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val r = reps.toIntOrNull() ?: return@Button
                        val w = loggedWeight(weight)
                        weight = Progression.fmt(w)
                        onLog(LoggedSet(weightKg = w, reps = r, type = SetType.NORMAL, rir = rir.toDouble()))
                    },
                    enabled = reps.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Log set") }
                OutlinedButton(
                    onClick = {
                        val r = reps.toIntOrNull() ?: return@OutlinedButton
                        val w = loggedWeight(weight)
                        weight = Progression.fmt(w)
                        onLog(LoggedSet(weightKg = w, reps = r, type = SetType.WARMUP))
                    },
                    enabled = reps.isNotBlank(),
                ) { Text("Warm-up") }
                if (lastSet != null) TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun AddExerciseDialog(state: UiState, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember(state.workouts) {
        (Progression(state.workouts).exerciseNames() + Exercises.starterLibrary).distinct()
    }
    val matches = all.filter { it.contains(query, ignoreCase = true) }.take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search or type a new name") }, singleLine = true)
                matches.forEach { name -> TextButton(onClick = { onPick(name) }) { Text(name) } }
            }
        },
        confirmButton = { TextButton(onClick = { if (query.isNotBlank()) onPick(query.trim()) }, enabled = query.isNotBlank()) { Text("Add \"$query\"") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Moves the typed weight one 2.5 kg step up or down, snapping odd values onto the 2.5 kg grid first. */
private fun stepWeight(text: String, direction: Int): String {
    val current = Progression.roundLoad(text.toDoubleOrNull() ?: 0.0)
    return Progression.fmt((current + direction * Progression.STEP_KG).coerceAtLeast(0.0))
}

/** The weight that gets saved: whatever was typed, rounded to the nearest 2.5 kg. */
private fun loggedWeight(text: String): Double = Progression.roundLoad(text.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
