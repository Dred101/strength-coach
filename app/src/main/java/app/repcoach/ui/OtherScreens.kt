package app.repcoach.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import app.repcoach.AppViewModel
import app.repcoach.Settings
import app.repcoach.UiState
import app.repcoach.core.Progression
import app.repcoach.core.SetType
import java.util.Locale

@Composable
fun HistoryScreen(state: UiState, vm: AppViewModel) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    val list = state.workouts.asReversed()
    if (list.isEmpty()) {
        Text("No workouts yet.", Modifier.padding(20.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
        items(list, key = { it.id }) { w ->
            Card(Modifier.fillMaxWidth().clickable { open = if (open == w.id) null else w.id }) {
                Column(Modifier.padding(16.dp)) {
                    Text(w.title, style = MaterialTheme.typography.titleMedium)
                    val minutes = ((w.endMillis - w.startMillis) / 60_000).coerceAtLeast(0)
                    Text("${formatDate(w.startMillis)} · $minutes min · ${w.exercises.size} exercises", style = MaterialTheme.typography.bodySmall)
                    if (open == w.id) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        w.exercises.forEach { ex ->
                            Text(ex.name, fontWeight = FontWeight.SemiBold)
                            Text(ex.sets.joinToString("   ") { s ->
                                "${Progression.fmt(s.weightKg)}×${s.reps}" + if (s.type == SetType.WARMUP) "w" else ""
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { confirmDelete = w.id }) { Text("Delete workout") }
                    }
                }
            }
        }
    }
    confirmDelete?.let { id ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this workout?") },
            confirmButton = { TextButton(onClick = { vm.deleteWorkout(id); confirmDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ProgressScreen(state: UiState) {
    val p = remember(state.workouts) { Progression(state.workouts) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text("Progress", style = MaterialTheme.typography.headlineSmall)
                Text("Best estimated one-rep max per session. Tap a lift for its chart.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(p.exerciseNames().take(40)) { name ->
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
                    if (selected == name && history.size >= 2) {
                        LineChart(history.map { it.bestE1rm }, Modifier.fillMaxWidth().height(160.dp).padding(top = 12.dp))
                        Text("${history.size} sessions since ${formatDate(history.first().startMillis).substringBefore(",")}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun LineChart(values: List<Double>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val min = values.min()
        val max = values.max().coerceAtLeast(min + 1)
        val stepX = size.width / (values.size - 1)
        fun y(v: Double) = (size.height - ((v - min) / (max - min) * size.height)).toFloat()
        listOf(0f, size.height / 2, size.height).forEach { drawLine(grid, Offset(0f, it), Offset(size.width, it)) }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v)) }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var apiKey by remember(state.settings) { mutableStateOf(state.settings.apiKey) }
    var model by remember(state.settings) { mutableStateOf(state.settings.model) }
    var notes by remember(state.settings) { mutableStateOf(state.settings.notes) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importHevy) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let(vm::exportTo) }
    val healthPermissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { vm.refreshHealth() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Text("AI coach", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, label = { Text("Claude API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Text("Create one at console.anthropic.com. It's stored only on this phone.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = notes, onValueChange = { notes = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            label = { Text("Notes for the coach") },
            placeholder = { Text("Equipment at your gym, injuries, exercises you dislike") },
        )
        Button(onClick = { vm.saveSettings(Settings(apiKey, model, notes)) }) { Text("Save") }

        HorizontalDivider()
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        Text(state.healthStatus)
        Text(
            "Reads sleep, resting heart rate, HRV, heart rate and steps from your band's app so the coach can adjust for recovery.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (vm.health.available) OutlinedButton(onClick = { healthPermissions.launch(vm.health.permissions) }) { Text("Connect Health Connect") }

        HorizontalDivider()
        Text("Your data", style = MaterialTheme.typography.titleMedium)
        Text("${state.workouts.size} workouts on this phone.")
        OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream", "*/*")) }) { Text("Import Hevy CSV") }
        Text("In Hevy: Profile, Settings, Export & Import Data, Export Workouts. Importing again replaces the earlier Hevy data.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { exporter.launch("repcoach-backup.json") }) { Text("Save a backup") }
    }
}
