package app.repcoach

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.repcoach.core.ChatTurn
import app.repcoach.core.CheckIn
import app.repcoach.core.ClaudeCoach
import app.repcoach.core.CoachContext
import app.repcoach.core.HevyApi
import app.repcoach.core.HevyCsv
import app.repcoach.core.PlanReview
import app.repcoach.core.Planner
import app.repcoach.core.Programme
import app.repcoach.core.Progression
import app.repcoach.core.Recovery
import app.repcoach.core.SavedPlan
import app.repcoach.core.Why
import app.repcoach.core.WhyTopic
import app.repcoach.core.Workout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

enum class Screen { HOME, CHECK_IN, PLAN, PROGRAMME, COACH, HISTORY, PROGRESS, RECORDS, SETTINGS }

/** Which conversation a question belongs to: the general coach chat, or one recommended workout's. */
const val COACH_CHAT = "coach"

data class UiState(
    val screen: Screen = Screen.HOME,
    val workouts: List<Workout> = emptyList(),
    val settings: Settings = Settings("", ClaudeCoach.DEFAULT_MODEL, ""),
    val checkIn: CheckIn = CheckIn(energy = 7, minutes = Programme.MINUTES),
    /** Recommended workouts, newest first, each with its chat. */
    val plans: List<SavedPlan> = emptyList(),
    val openPlanId: String? = null,
    val coachChat: List<ChatTurn> = emptyList(),
    /** The chat waiting on the coach: [COACH_CHAT] or a plan id. */
    val chatBusy: String? = null,
    val chatError: String? = null,
    /** Text to put in a chat's input box, from an "Ask about this" button. */
    val draft: Pair<String, String>? = null,
    /** The facts and research behind one suggestion, shown on request. */
    val why: WhyTopic? = null,
    val busy: String? = null,
    val autoBackup: Boolean = false,
    val message: String? = null,
    val hevyStatus: String? = null,
    val programme: Programme? = null,
) {
    val openPlan: SavedPlan? get() = plans.firstOrNull { it.id == openPlanId }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val (workouts, plans, chat) = withContext(Dispatchers.IO) { Triple(store.loadWorkouts(), store.loadPlans(), store.loadCoachChat()) }
            _state.update {
                it.copy(
                    workouts = workouts,
                    plans = plans,
                    coachChat = chat,
                    settings = store.loadSettings(),
                    autoBackup = store.autoBackupUri != null,
                    programme = store.programme,
                )
            }
            syncHevyNow(quiet = true)
        }
    }

    fun go(screen: Screen) = _state.update { it.copy(screen = screen, message = null, chatError = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- Planning ----

    fun setCheckIn(checkIn: CheckIn) = _state.update { it.copy(checkIn = checkIn) }

    fun makePlan() = viewModelScope.launch {
        // Plan from everything logged in Hevy up to now.
        if (_state.value.settings.hevyKey.isNotBlank()) {
            _state.update { it.copy(busy = "Getting your latest workouts from Hevy…", message = null) }
            syncHevyNow(quiet = true)
        }
        ensureProgramme()
        val s = _state.value
        _state.update { it.copy(busy = "Planning your session…") }
        val recovery: Recovery? = null
        val result = withContext(Dispatchers.IO) {
            if (s.settings.apiKey.isBlank()) {
                Result.success(Planner(Progression(s.workouts), s.programme).plan(s.checkIn, recovery) to
                    "No Claude API key yet, so this plan comes from the built-in rules. Add a key in Settings for AI coaching.")
            } else runCatching {
                ClaudeCoach(s.settings.apiKey, s.settings.model.ifBlank { ClaudeCoach.DEFAULT_MODEL })
                    .plan(s.workouts, s.checkIn, recovery, s.settings.notes, s.programme) to null
            }.recoverCatching { e ->
                Planner(Progression(s.workouts), s.programme).plan(s.checkIn, recovery) to
                    "Couldn't reach Claude (${e.message ?: e.javaClass.simpleName}), so this plan comes from the built-in rules."
            }
        }
        val (plan, note) = result.getOrThrow()
        val now = System.currentTimeMillis()
        val saved = SavedPlan("plan-$now", now, s.checkIn.energy, s.checkIn.minutes, plan)
        savePlans(listOf(saved) + _state.value.plans)
        _state.update { it.copy(busy = null, openPlanId = saved.id, message = note, screen = Screen.PLAN) }
    }

    fun openPlan(id: String) = _state.update { it.copy(openPlanId = id, screen = Screen.PLAN, message = null, chatError = null) }

    fun deletePlan(id: String) {
        savePlans(_state.value.plans.filterNot { it.id == id })
        _state.update { it.copy(openPlanId = null, screen = Screen.HOME) }
    }

    // ---- Programme ----

    /** Designs Full Body A and B from the history the first time they're needed, then keeps them for the block. */
    fun ensureProgramme() {
        if (_state.value.programme != null || _state.value.workouts.isEmpty()) return
        val p = Programme.design(Progression(_state.value.workouts))
        store.programme = p
        _state.update { it.copy(programme = p) }
    }

    /** Starts a new block: same main lifts, supporting lifts moved on to the next variation. */
    fun newBlock() {
        val p = Programme.design(Progression(_state.value.workouts), _state.value.programme)
        store.programme = p
        _state.update { it.copy(programme = p, message = "New block started. Main lifts stay; supporting lifts have changed.") }
    }

    // ---- Hevy ----

    fun saveHevyKey(key: String) {
        store.saveHevyKey(key)
        _state.update { it.copy(settings = store.loadSettings()) }
        if (key.isBlank()) _state.update { it.copy(message = "Hevy sync is off.") } else syncHevy()
    }

    fun syncHevy() = viewModelScope.launch {
        _state.update { it.copy(busy = "Syncing from Hevy…") }
        syncHevyNow(quiet = false)
    }

    /**
     * Pulls workouts added, edited or deleted in Hevy since the last sync. The first sync takes the
     * whole Hevy history in place of any CSV import. Quiet syncs only speak up when something changed.
     */
    private suspend fun syncHevyNow(quiet: Boolean) {
        val key = _state.value.settings.hevyKey
        if (key.isBlank()) return
        val last = store.hevyLastSync
        val started = Instant.now()
        val result = withContext(Dispatchers.IO) {
            runCatching { HevyApi(key).changesSince(last?.let(Instant::parse) ?: Instant.EPOCH) }
        }
        result.onSuccess { changes ->
            val before = _state.value.workouts
            val merged = HevyApi.merge(before, changes, firstSync = last == null)
            if (merged != before) saveWorkouts(merged)
            // Overlap the next window a little; repeats are matched by id.
            store.hevyLastSync = started.minusSeconds(120).toString()
            val newCount = merged.map { it.id }.toSet().minus(before.map { it.id }.toSet()).size
            val note = when {
                last == null -> "Connected to Hevy: ${merged.count { it.source == "hevy" }} workouts synced."
                newCount > 0 -> "Synced $newCount new workout${if (newCount == 1) "" else "s"} from Hevy."
                changes.updated.isNotEmpty() || changes.deletedIds.isNotEmpty() -> "Synced your Hevy changes."
                else -> null
            }
            _state.update { it.copy(busy = null, hevyStatus = "Last synced ${formatTime(started)}", message = note ?: if (quiet) it.message else "Hevy is up to date.") }
        }.onFailure { e ->
            _state.update { it.copy(busy = null, hevyStatus = "Last sync failed: ${e.message}", message = if (quiet) it.message else e.message) }
        }
    }

    /** Sends the current plan to Hevy as a routine, so it can be followed and logged there. */
    fun sendPlanToHevy() = viewModelScope.launch {
        val s = _state.value
        val plan = s.openPlan?.plan ?: return@launch
        _state.update { it.copy(busy = "Sending to Hevy…") }
        val result = withContext(Dispatchers.IO) { runCatching { HevyApi(s.settings.hevyKey).sendRoutine(plan, store.hevyRoutineId) } }
        result.onSuccess { sent ->
            store.hevyRoutineId = sent.routineId
            val missing = if (sent.skipped.isEmpty()) "" else " Hevy doesn't have ${sent.skipped.joinToString(", ")}, so add ${if (sent.skipped.size == 1) "it" else "them"} there by hand."
            _state.update { it.copy(busy = null, message = "Sent to Hevy as the routine \"Rep Coach: ${plan.title}\". In Hevy, open Workout and start it.$missing") }
        }.onFailure { e ->
            _state.update { it.copy(busy = null, message = "Couldn't send to Hevy: ${e.message}") }
        }
    }

    private fun formatTime(t: Instant): String =
        java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.UK).format(t.atZone(java.time.ZoneId.systemDefault()))

    // ---- Talking to the coach ----

    fun showWhy(topic: WhyTopic?) = _state.update { it.copy(why = topic) }

    /** Opens a chat with a question started, for "Ask about this" buttons. */
    fun startQuestion(chat: String, text: String) = _state.update {
        it.copy(draft = chat to text, why = null, screen = if (chat == COACH_CHAT) Screen.COACH else Screen.PLAN,
            openPlanId = if (chat == COACH_CHAT) it.openPlanId else chat)
    }

    fun draftUsed() = _state.update { it.copy(draft = null) }

    fun clearCoachChat() {
        _state.update { it.copy(coachChat = emptyList(), chatError = null) }
        persist { store.saveCoachChat(emptyList()) }
    }

    /** The general chat: questions about training, the programme or progress. */
    fun askCoach(question: String) {
        val s = _state.value
        val q = question.trim()
        if (q.isBlank() || s.chatBusy != null) return
        val before = s.coachChat
        val turns = before + ChatTurn(fromLifter = true, text = q)
        _state.update { it.copy(coachChat = turns, chatBusy = COACH_CHAT, chatError = null) }
        viewModelScope.launch {
            val answer = withContext(Dispatchers.IO) {
                runCatching {
                    val briefing = CoachContext.overview(Progression(s.workouts), s.programme, s.settings.notes)
                    coach(s).ask(briefing, recentTurns(turns))
                }
            }
            answer.onSuccess { text ->
                val all = turns + ChatTurn(fromLifter = false, text = text)
                _state.update { it.copy(coachChat = all, chatBusy = null) }
                persist { store.saveCoachChat(all) }
            }.onFailure { e ->
                // Drop the unanswered question so the conversation still alternates, and say what failed.
                _state.update { it.copy(coachChat = before, chatBusy = null, chatError = failure(e, q)) }
            }
        }
    }

    /** A question about one recommended workout. The coach may revise the workout if asked. */
    fun askAboutPlan(planId: String, question: String) {
        val s = _state.value
        val saved = s.plans.firstOrNull { it.id == planId } ?: return
        val q = question.trim()
        if (q.isBlank() || s.chatBusy != null) return
        val turns = saved.chat + ChatTurn(fromLifter = true, text = q)
        updatePlan(planId) { it.copy(chat = turns) }
        _state.update { it.copy(chatBusy = planId, chatError = null) }
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    val p = Progression(s.workouts)
                    val done = PlanReview.match(s.plans, s.workouts)[planId]
                    val briefing = buildString {
                        append(PlanReview.asText(saved, done))
                        appendLine()
                        appendLine("## The app's reasoning for each exercise")
                        saved.plan.exercises.forEach { e -> appendLine("### ${e.name}"); Why.forExercise(e, p).facts.forEach { appendLine("- $it") } }
                        appendLine()
                        append(Why.forPlan(saved.plan, p).asText())
                        appendLine()
                        append(CoachContext.programmeText(s.programme, p))
                        if (s.settings.notes.isNotBlank()) appendLine("Notes from the lifter: ${s.settings.notes}")
                        appendLine()
                        append(CoachContext.history(p))
                    }
                    coach(s).discussPlan(briefing, recentTurns(turns), saved.plan, s.workouts)
                }
            }
            reply.onSuccess { r ->
                updatePlan(planId) { it.copy(plan = r.revised ?: it.plan, chat = turns + ChatTurn(fromLifter = false, text = r.answer)) }
                _state.update { it.copy(chatBusy = null, message = if (r.revised != null) "The coach updated this workout." else it.message) }
            }.onFailure { e ->
                updatePlan(planId) { it.copy(chat = saved.chat) }
                _state.update { it.copy(chatBusy = null, chatError = failure(e, q)) }
            }
        }
    }

    private fun coach(s: UiState) = ClaudeCoach(s.settings.apiKey, s.settings.model.ifBlank { ClaudeCoach.DEFAULT_MODEL })

    /** The last part of a long conversation, starting with the lifter, to keep each question cheap. */
    private fun recentTurns(turns: List<ChatTurn>): List<ChatTurn> {
        if (turns.size <= MAX_TURNS) return turns
        val tail = turns.takeLast(MAX_TURNS)
        return if (tail.first().fromLifter) tail else tail.drop(1)
    }

    private fun failure(e: Throwable, question: String) =
        "Couldn't reach Claude (${e.message ?: e.javaClass.simpleName}). Your question was: \"$question\""

    private fun updatePlan(id: String, change: (SavedPlan) -> SavedPlan) =
        savePlans(_state.value.plans.map { if (it.id == id) change(it) else it })

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
            // With Hevy sync on, the next sync swaps this import for the API's copy so nothing doubles up.
            if (_state.value.settings.hevyKey.isNotBlank()) store.hevyLastSync = null
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
            val added = restored.workouts.filter { it.id !in existing }
            saveWorkouts(_state.value.workouts + added)
            val known = _state.value.plans.map { it.id }.toSet()
            val plans = restored.plans.filter { it.id !in known }
            if (plans.isNotEmpty()) savePlans((_state.value.plans + plans).sortedByDescending { it.createdMillis })
            if (_state.value.coachChat.isEmpty() && restored.coachChat.isNotEmpty()) {
                _state.update { it.copy(coachChat = restored.coachChat) }
                persist { store.saveCoachChat(restored.coachChat) }
            }
            _state.update { it.copy(message = "Restored ${added.size} workouts and ${plans.size} recommended workouts from the backup.") }
        }.onFailure { e ->
            _state.update { it.copy(message = "Couldn't read that backup: ${e.message}") }
        }
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(store.exportJson(backup())) }
            }.isSuccess
        }
        _state.update { it.copy(message = if (ok) "Backup saved." else "Couldn't save the backup.") }
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
            val written = writeAutoBackup()
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

    private fun backup() = _state.value.let { Backup(it.workouts, it.plans, it.coachChat) }

    private fun writeAutoBackup(): Boolean {
        val uri = store.autoBackupUri ?: return true
        return runCatching {
            getApplication<Application>().contentResolver.openOutputStream(Uri.parse(uri), "wt")!!
                .bufferedWriter().use { it.write(store.exportJson(backup())) }
        }.isSuccess
    }

    /** Saves to the phone off the main thread, then refreshes the automatic backup. */
    private fun persist(write: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            write()
            if (!writeAutoBackup()) _state.update { it.copy(message = "Couldn't update your automatic backup. Check it in Settings.") }
        }
    }

    private fun saveWorkouts(workouts: List<Workout>) {
        val sorted = workouts.sortedBy { it.startMillis }
        _state.update { it.copy(workouts = sorted) }
        persist { store.saveWorkouts(sorted) }
    }

    private fun savePlans(plans: List<SavedPlan>) {
        _state.update { it.copy(plans = plans) }
        persist { store.savePlans(plans) }
    }

    companion object {
        /** Messages sent with each question; older ones in a long chat stay on screen but aren't sent. */
        const val MAX_TURNS = 20
    }
}
