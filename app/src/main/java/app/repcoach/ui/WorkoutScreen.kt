@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.repcoach.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.repcoach.AppViewModel
import app.repcoach.UiState
import app.repcoach.core.Exercises
import app.repcoach.core.LoggedSet
import app.repcoach.core.PlannedExercise
import app.repcoach.core.Progression
import app.repcoach.core.SetType
import kotlinx.coroutines.delay

@Composable
fun WorkoutScreen(state: UiState, vm: AppViewModel) {
    val active = state.active ?: return
    var confirmFinish by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(active.plan.title, style = MaterialTheme.typography.headlineSmall)
                    Text("Started ${formatDate(active.startMillis)}", style = MaterialTheme.typography.bodySmall)
                }
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
                    onLog = { vm.logSet(e.name, it, e.restSeconds) },
                    onUndo = { vm.removeLastSet(e.name) },
                    onSwap = { vm.swapExercise(e.name) },
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
            text = { Text("${active.logged.values.sumOf { it.size }} sets logged.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; vm.finishWorkout() }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFinish = false; vm.discardWorkout() }) { Text("Discard") }
                    TextButton(onClick = { confirmFinish = false }) { Text("Keep going") }
                }
            },
        )
    }

    if (adding) AddExerciseDialog(state, onPick = { adding = false; vm.addExercise(it) }, onDismiss = { adding = false })
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
) {
    val lastSet = logged.lastOrNull()
    var weight by remember(e.name) { mutableStateOf(e.weightKg?.let { Progression.fmt(it) } ?: "") }
    var reps by remember(e.name) { mutableStateOf(e.repsMax.toString()) }
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
                Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (logged.isEmpty()) TextButton(onClick = onSwap) { Text("Swap") }
            }
            Text("Target: ${prescription(e)}", fontWeight = FontWeight.SemiBold)
            if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodySmall)

            logged.forEachIndexed { i, s ->
                Text("Set ${i + 1}: ${Progression.fmt(s.weightKg)} kg × ${s.reps}" + (s.rir?.let { ", ${it.toInt()} in reserve" } ?: "") +
                    if (s.type == SetType.WARMUP) " (warm-up)" else "")
                badges.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                    Text("🏆 New record: $it", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight, onValueChange = { weight = it.replace(',', '.') }, label = { Text("kg") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
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
                        onLog(LoggedSet(weightKg = weight.toDoubleOrNull() ?: 0.0, reps = r, type = SetType.NORMAL, rir = rir.toDouble()))
                    },
                    enabled = reps.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Log set") }
                OutlinedButton(
                    onClick = {
                        val r = reps.toIntOrNull() ?: return@OutlinedButton
                        onLog(LoggedSet(weightKg = weight.toDoubleOrNull() ?: 0.0, reps = r, type = SetType.WARMUP))
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
