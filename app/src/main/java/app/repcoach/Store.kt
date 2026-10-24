package app.repcoach

import android.content.Context
import app.repcoach.core.ClaudeCoach
import app.repcoach.core.SessionPlan
import app.repcoach.core.Workout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A workout being logged right now, saved after every set so nothing is lost if the app closes. */
@Serializable
data class ActiveWorkout(
    val plan: SessionPlan,
    val startMillis: Long,
    val energy: Int,
    /** Logged sets per exercise name, in plan order. */
    val logged: Map<String, List<app.repcoach.core.LoggedSet>> = emptyMap(),
    val restEndsAt: Long? = null,
)

data class Settings(
    val apiKey: String,
    val model: String,
    val notes: String,
    /** Hevy API key (Hevy Pro), for pulling workouts logged in Hevy and sending plans there. */
    val hevyKey: String = "",
)

/** Everything lives on the phone: workouts in a JSON file, settings in private preferences. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val workoutsFile = File(dir, "workouts.json")
    private val activeFile = File(dir, "active.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Workout.serializer())

    fun loadWorkouts(): List<Workout> =
        if (workoutsFile.exists()) json.decodeFromString(listSerializer, workoutsFile.readText()) else emptyList()

    fun saveWorkouts(workouts: List<Workout>) = writeAtomically(workoutsFile, json.encodeToString(listSerializer, workouts))

    fun exportJson(workouts: List<Workout>): String = json.encodeToString(listSerializer, workouts)

    fun parseBackup(text: String): List<Workout> = json.decodeFromString(listSerializer, text)

    fun loadActive(): ActiveWorkout? =
        if (activeFile.exists()) runCatching { json.decodeFromString(ActiveWorkout.serializer(), activeFile.readText()) }.getOrNull() else null

    fun saveActive(active: ActiveWorkout?) {
        if (active == null) activeFile.delete() else writeAtomically(activeFile, json.encodeToString(ActiveWorkout.serializer(), active))
    }

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

    /** A document the user picked once; every change to workouts is copied there. */
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
