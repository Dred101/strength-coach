package app.repcoach.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import app.repcoach.AppViewModel
import app.repcoach.Settings
import app.repcoach.UiState
import app.repcoach.core.Progression
import app.repcoach.core.SetType

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
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var apiKey by remember(state.settings) { mutableStateOf(state.settings.apiKey) }
    var model by remember(state.settings) { mutableStateOf(state.settings.model) }
    var notes by remember(state.settings) { mutableStateOf(state.settings.notes) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::importHevy) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let(vm::exportTo) }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let(vm::restoreBackup) }
    val hevyExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? -> uri?.let(vm::exportForHevy) }
    val autoBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? -> uri?.let(vm::enableAutoBackup) }
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
        Text("Automatic backup: " + if (state.autoBackup) "on" else "off", fontWeight = FontWeight.SemiBold)
        Text(
            "Pick a file once, ideally in Google Drive, and the app rewrites it after every workout. " +
                "If the app is ever reinstalled, use Restore a backup and choose that file.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.autoBackup) OutlinedButton(onClick = { vm.disableAutoBackup() }) { Text("Turn off automatic backup") }
        else Button(onClick = { autoBackup.launch("repcoach-autobackup.json") }) { Text("Turn on automatic backup") }
        OutlinedButton(onClick = { exporter.launch("repcoach-backup.json") }) { Text("Save a one-off backup") }
        OutlinedButton(onClick = { restorer.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }) { Text("Restore a backup") }

        HorizontalDivider()
        Text("Going back to Hevy", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { hevyExporter.launch("repcoach-for-hevy.csv") }) { Text("Export workouts for Hevy") }
        Text(
            "Saves the workouts you logged here (not the ones imported from Hevy, which Hevy already has) as a Strong-format CSV. " +
                "In Hevy: Profile, Settings, Export & Import Data, Import data, Import Strong CSV. Hevy allows one import, so do this once, when you switch back.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
