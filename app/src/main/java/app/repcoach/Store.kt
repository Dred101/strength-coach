package app.repcoach

import android.content.Context
import app.repcoach.core.ChatTurn
import app.repcoach.core.ClaudeCoach
import app.repcoach.core.Programme
import app.repcoach.core.SavedPlan
import app.repcoach.core.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Everything a backup holds: workouts from Hevy, recommended workouts with their chats, and the coach chat. */
@Serializable
data class Backup(
    val workouts: List<Workout> = emptyList(),
    val plans: List<SavedPlan> = emptyList(),
    val coachChat: List<ChatTurn> = emptyList(),
)

data class Settings(
    val apiKey: String,
    val model: String,
    val notes: String,
    /** Hevy API key (Hevy Pro), for pulling workouts logged in Hevy and sending plans there. */
    val hevyKey: String = "",
)

/** Everything lives on the phone: workouts, plans and chats in JSON files, settings in private preferences. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val workoutsFile = File(dir, "workouts.json")
    private val plansFile = File(dir, "plans.json")
    private val chatFile = File(dir, "coach-chat.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Workout.serializer())
    private val plansSerializer = ListSerializer(SavedPlan.serializer())
    private val chatSerializer = ListSerializer(ChatTurn.serializer())

    init {
        // Workouts being logged in the app, from before logging moved to Hevy.
        File(dir, "active.json").delete()
    }

    fun loadWorkouts(): List<Workout> =
        if (workoutsFile.exists()) json.decodeFromString(listSerializer, workoutsFile.readText()) else emptyList()

    fun saveWorkouts(workouts: List<Workout>) = writeAtomically(workoutsFile, json.encodeToString(listSerializer, workouts))

    fun loadPlans(): List<SavedPlan> =
        if (plansFile.exists()) runCatching { json.decodeFromString(plansSerializer, plansFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    fun savePlans(plans: List<SavedPlan>) = writeAtomically(plansFile, json.encodeToString(plansSerializer, plans))

    fun loadCoachChat(): List<ChatTurn> =
        if (chatFile.exists()) runCatching { json.decodeFromString(chatSerializer, chatFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    fun saveCoachChat(turns: List<ChatTurn>) = writeAtomically(chatFile, json.encodeToString(chatSerializer, turns))

    fun exportJson(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** Reads a backup; older backups were a plain list of workouts. */
    fun parseBackup(text: String): Backup =
        if (text.trimStart().startsWith("[")) Backup(workouts = json.decodeFromString(listSerializer, text))
        else json.decodeFromString(Backup.serializer(), text)

    fun loadSettings() = Settings(
        apiKey = prefs.getString("apiKey", "") ?: "",
        // Before the model picker, saving settings stored the old Opus default; move that to the new default.
        model = prefs.getString("model", null)
            ?.takeUnless { it == "claude-opus-5-5" && !prefs.getBoolean("modelPicked", false) }
            ?: ClaudeCoach.DEFAULT_MODEL,
        notes = prefs.getString("notes", "") ?: "",
        hevyKey = prefs.getString("hevyKey", "") ?: "",
    )

    /** When the last Hevy sync started, as an ISO instant; null means never, so the next sync is a full one. */
    var hevyLastSync: String?
        get() = prefs.getString("hevyLastSync", null)
        set(value) { prefs.edit().putString("hevyLastSync", value).apply() }

    /** The Hevy routine the last plan was sent to, reused so Hevy's routine list doesn't fill up. */
    var hevyRoutineId: String?
        get() = prefs.getString("hevyRoutineId", null)
        set(value) { prefs.edit().putString("hevyRoutineId", value).apply() }

    fun saveHevyKey(key: String) {
        val k = key.trim()
        // A different key may be a different Hevy account: start again with a full sync.
        if (k != prefs.getString("hevyKey", "")) prefs.edit().putString("hevyKey", k).remove("hevyLastSync").remove("hevyRoutineId").apply()
    }

    /** The lifter's Full Body A and B for the current block. */
    var programme: Programme?
        get() = prefs.getString("programme", null)?.let { runCatching { json.decodeFromString(Programme.serializer(), it) }.getOrNull()?.upgraded() }
        set(value) { prefs.edit().putString("programme", value?.let { json.encodeToString(Programme.serializer(), it) }).apply() }

    /** A document the user picked once; every change to workouts, plans or chats is copied there. */
    var autoBackupUri: String?
        get() = prefs.getString("autoBackupUri", null)
        set(value) { prefs.edit().putString("autoBackupUri", value).apply() }

    fun saveSettings(s: Settings) {
        prefs.edit().putString("apiKey", s.apiKey.trim()).putString("model", s.model.trim()).putBoolean("modelPicked", true).putString("notes", s.notes).apply()
    }

    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
