package app.repcoach

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.repcoach.core.CheckIn
import app.repcoach.core.ClaudeCoach
import app.repcoach.core.ExerciseLog
import app.repcoach.core.Exercises
import app.repcoach.core.HevyCsv
import app.repcoach.core.LoggedSet
import app.repcoach.core.Planner
import app.repcoach.core.Progression
import app.repcoach.core.Recovery
import app.repcoach.core.Records
import app.repcoach.core.SessionPlan
import app.repcoach.core.StrongCsv
import app.repcoach.core.Workout
import app.repcoach.core.toPlanned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, CHECK_IN, PLAN, WORKOUT, HISTORY, PROGRESS, RECORDS, SETTINGS }

data class UiState(
    val screen: Screen = Screen.HOME,
    val workouts: List<Workout> = emptyList(),
    val settings: Settings = Settings("", ClaudeCoach.DEFAULT_MODEL, ""),
    val recovery: Recovery? = null,
    val healthStatus: String = "Not connected",
    val checkIn: CheckIn = CheckIn(energy = 7, minutes = 60),
    val plan: SessionPlan? = null,
    val active: ActiveWorkout? = null,
    val busy: String? = null,
    val autoBackup: Boolean = false,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val health = Health(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (workouts, active) = withContext(Dispatchers.IO) { store.loadWorkouts() to store.loadActive() }
            _state.update {
                it.copy(
                    workouts = workouts,
                    settings = store.loadSettings(),
                    autoBackup = store.autoBackupUri != null,
                    active = active,
                    screen = if (active != null) Screen.WORKOUT else Screen.HOME,
                )
            }
            refreshHealth()
        }
    }

    fun go(screen: Screen) = _state.update { it.copy(screen = screen, message = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- Health Connect ----

    fun refreshHealth() = viewModelScope.launch {
        val status = when {
            !health.available -> "Health Connect isn't available on this phone"
            else -> {
                val granted = runCatching { health.grantedCount() }.getOrDefault(0)
                if (granted == 0) "Not connected" else "Connected ($granted of ${health.permissions.size} data types)"
            }
        }
        val recovery = if (health.available) runCatching { health.recovery() }.getOrNull() else null
        _state.update { it.copy(healthStatus = status, recovery = recovery) }
    }

    // ---- Planning ----

    fun setCheckIn(checkIn: CheckIn) = _state.update { it.copy(checkIn = checkIn) }

    fun makePlan() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(busy = "Planning your session…", message = null) }
        val recovery = runCatching { if (health.available) health.recovery() else null }.getOrNull()
        val result = withContext(Dispatchers.IO) {
            if (s.settings.apiKey.isBlank()) {
                Result.success(Planner(Progression(s.workouts)).plan(s.checkIn, recovery) to
                    "No Claude API key yet, so this plan comes from the built-in rules. Add a key in Settings for AI coaching.")
            } else runCatching {
                ClaudeCoach(s.settings.apiKey, s.settings.model.ifBlank { ClaudeCoach.DEFAULT_MODEL })
                    .plan(s.workouts, s.checkIn, recovery, s.settings.notes) to null
            }.recoverCatching { e ->
                Planner(Progression(s.workouts)).plan(s.checkIn, recovery) to
                    "Couldn't reach Claude (${e.message ?: e.javaClass.simpleName}), so this plan comes from the built-in rules."
            }
        }
        val (plan, note) = result.getOrThrow()
        _state.update { it.copy(busy = null, plan = plan, recovery = recovery, message = note, screen = Screen.PLAN) }
    }

    // ---- Logging ----

    fun startWorkout() {
        val plan = _state.value.plan ?: return
        saveActive(ActiveWorkout(plan, System.currentTimeMillis(), _state.value.checkIn.energy))
        go(Screen.WORKOUT)
    }

    fun startEmptyWorkout() {
        saveActive(ActiveWorkout(SessionPlan("Workout", "", emptyList(), "manual"), System.currentTimeMillis(), _state.value.checkIn.energy))
        go(Screen.WORKOUT)
    }

    fun logSet(exercise: String, set: LoggedSet, restSeconds: Int) {
        val a = _state.value.active ?: return
        val before = a.logged[exercise].orEmpty()
        val broken = brokenRecords(exercise, before, set)
        saveActive(a.copy(logged = a.logged + (exercise to before + set), restEndsAt = System.currentTimeMillis() + restSeconds * 1000L))
        if (broken.isNotEmpty()) _state.update { it.copy(message = "New record! $exercise: " + broken.joinToString(", ") { r -> r.title }) }
    }

    /** Records this set breaks, measured against history plus the sets already logged today. */
    fun brokenRecords(exercise: String, earlierToday: List<LoggedSet>, set: LoggedSet): List<app.repcoach.core.PersonalRecord> {
        val history = _state.value.workouts.mapNotNull { w ->
            w.exercises.filter { it.name == exercise }.takeIf { it.isNotEmpty() }?.let { w.copy(exercises = it) }
        }
        if (history.isEmpty()) return emptyList()
        val today = Workout("today", "", Long.MAX_VALUE, Long.MAX_VALUE, listOf(ExerciseLog(exercise, earlierToday)))
        val current = Records.all(if (earlierToday.isEmpty()) history else history + today)[exercise].orEmpty()
        return Records.brokenBy(set, exercise, current)
    }

    fun removeLastSet(exercise: String) {
        val a = _state.value.active ?: return
        val sets = a.logged[exercise].orEmpty().dropLast(1)
        saveActive(a.copy(logged = a.logged + (exercise to sets)))
    }

    fun skipRest() {
        val a = _state.value.active ?: return
        saveActive(a.copy(restEndsAt = null))
    }

    fun addRest(seconds: Int) {
        val a = _state.value.active ?: return
        saveActive(a.copy(restEndsAt = (a.restEndsAt ?: System.currentTimeMillis()) + seconds * 1000L))
    }

    fun addExercise(name: String) {
        val a = _state.value.active ?: return
        if (a.plan.exercises.any { it.name == name }) return
        val target = Progression(_state.value.workouts).target(name, a.energy)
        val planned = target.toPlanned()
        saveActive(a.copy(plan = a.plan.copy(exercises = a.plan.exercises + planned)))
    }

    /** Swaps an exercise for another hitting the same muscle, e.g. when a machine is taken. */
    fun swapExercise(old: String) {
        val a = _state.value.active ?: return
        val info = Exercises.info(old)
        val inPlan = a.plan.exercises.map { it.name }.toSet()
        val p = Progression(_state.value.workouts)
        val candidates = (p.exerciseNames() + Exercises.starterLibrary).distinct()
            .filter { it !in inPlan && Exercises.info(it).primary == info.primary }
        val next = candidates.firstOrNull { Exercises.info(it).kind == info.kind } ?: candidates.firstOrNull() ?: return
        val t = p.target(next, a.energy)
        val replacement = t.toPlanned().copy(reason = "Swapped in for $old. ${t.note}")
        saveActive(a.copy(plan = a.plan.copy(exercises = a.plan.exercises.map { if (it.name == old) replacement else it })))
    }

    fun finishWorkout() {
        val a = _state.value.active ?: return
        val exercises = a.plan.exercises.map { it.name }.plus(a.logged.keys).distinct()
            .mapNotNull { name -> a.logged[name]?.takeIf { it.isNotEmpty() }?.let { ExerciseLog(name, it) } }
        if (exercises.isNotEmpty()) {
            val workout = Workout(
                id = "app-${a.startMillis}",
                title = a.plan.title,
                startMillis = a.startMillis,
                endMillis = System.currentTimeMillis(),
                exercises = exercises,
                energy = a.energy,
            )
            val records = Records.newIn(_state.value.workouts + workout, workout)
            saveWorkouts(_state.value.workouts + workout)
            saveActive(null)
            val summary = if (records.isEmpty()) "Workout saved." else
                "Workout saved with ${records.size} new record${if (records.size == 1) "" else "s"}: " +
                    records.take(6).joinToString("; ") { "${it.exercise} ${it.title}" }
            _state.update { it.copy(plan = null, screen = if (records.isEmpty()) Screen.HISTORY else Screen.RECORDS, message = summary) }
            return
        }
        saveActive(null)
        _state.update { it.copy(plan = null, screen = Screen.HISTORY, message = "Nothing was logged, so the workout wasn't saved.") }
    }

    fun discardWorkout() {
        saveActive(null)
        go(Screen.HOME)
    }

    fun deleteWorkout(id: String) = saveWorkouts(_state.value.workouts.filterNot { it.id == id })

    // ---- Settings and data ----

    fun saveSettings(settings: Settings) {
        store.saveSettings(settings)
        _state.update { it.copy(settings = store.loadSettings(), message = "Settings saved.") }
    }

    fun importHevy(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = "Importing from Hevy…") }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                HevyCsv.parse(text)
            }
        }
        result.onSuccess { imported ->
            // Re-importing replaces earlier Hevy data and keeps everything logged in this app.
            val kept = _state.value.workouts.filter { it.source != "hevy" }
            saveWorkouts(kept + imported)
            _state.update { it.copy(busy = null, message = "Imported ${imported.size} workouts from Hevy.") }
        }.onFailure { e ->
            _state.update { it.copy(busy = null, message = "Import failed: ${e.message}") }
        }
    }

    /** Adds workouts from a backup made with "Save a backup", skipping any already on the phone. */
    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                store.parseBackup(text)
            }
        }
        result.onSuccess { restored ->
            val existing = _state.value.workouts.map { it.id }.toSet()
            val added = restored.filter { it.id !in existing }
            saveWorkouts(_state.value.workouts + added)
            _state.update { it.copy(message = "Restored ${added.size} workouts from the backup.") }
        }.onFailure { e ->
            _state.update { it.copy(message = "Couldn't read that backup: ${e.message}") }
        }
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(store.exportJson(_state.value.workouts)) }
            }.isSuccess
        }
        _state.update { it.copy(message = if (ok) "Backup saved." else "Couldn't save the backup.") }
    }

    /** Writes the workouts logged in this app (not ones imported from Hevy) as a CSV Hevy can import. */
    fun exportForHevy(uri: Uri) = viewModelScope.launch {
        val mine = _state.value.workouts.filter { it.source != "hevy" }
        val ok = mine.isNotEmpty() && withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(StrongCsv.export(mine)) }
            }.isSuccess
        }
        _state.update {
            it.copy(message = when {
                mine.isEmpty() -> "No workouts logged in Rep Coach yet, so there's nothing to send to Hevy."
                ok -> "Saved ${mine.size} workouts for Hevy."
                else -> "Couldn't save the file."
            })
        }
    }

    /** Turns on automatic backup to a document the user just created, and writes it straight away. */
    fun enableAutoBackup(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val ok = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.isSuccess
        if (!ok) {
            _state.update { it.copy(message = "That location can't be used for automatic backups. Try a folder on the phone or in Google Drive.") }
            return
        }
        store.autoBackupUri = uri.toString()
        _state.update { it.copy(autoBackup = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val written = writeAutoBackup(_state.value.workouts)
            _state.update { it.copy(message = if (written) "Automatic backup is on. It updates after every change." else "Automatic backup is on, but the first save failed.") }
        }
    }

    fun disableAutoBackup() {
        store.autoBackupUri?.let { uri ->
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        store.autoBackupUri = null
        _state.update { it.copy(autoBackup = false, message = "Automatic backup is off.") }
    }

    private fun writeAutoBackup(workouts: List<Workout>): Boolean {
        val uri = store.autoBackupUri ?: return true
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(Uri.parse(uri), "wt")!!
                .bufferedWriter().use { it.write(store.exportJson(workouts)) }
        }.isSuccess
    }

    private fun saveWorkouts(workouts: List<Workout>) {
        val sorted = workouts.sortedBy { it.startMillis }
        _state.update { it.copy(workouts = sorted) }
        viewModelScope.launch(Dispatchers.IO) {
            store.saveWorkouts(sorted)
            if (!writeAutoBackup(sorted)) _state.update { it.copy(message = "Couldn't update your automatic backup. Check it in Settings.") }
        }
    }

    private fun saveActive(active: ActiveWorkout?) {
        _state.update { it.copy(active = active) }
        store.saveActive(active)
    }
}
