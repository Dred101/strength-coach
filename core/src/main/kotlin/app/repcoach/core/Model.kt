package app.repcoach.core

import kotlinx.serialization.Serializable

@Serializable
enum class SetType { WARMUP, NORMAL, DROPSET, FAILURE }

@Serializable
data class LoggedSet(
    val weightKg: Double,
    val reps: Int,
    val type: SetType = SetType.NORMAL,
    /** Reps in reserve, if logged. Hevy's RPE is converted as RIR = 10 - RPE. */
    val rir: Double? = null,
) {
    val isWorking: Boolean get() = type != SetType.WARMUP
}

@Serializable
data class ExerciseLog(
    val name: String,
    val sets: List<LoggedSet>,
    val notes: String = "",
)

@Serializable
data class Workout(
    val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val exercises: List<ExerciseLog>,
    val energy: Int? = null,
    val source: String = "app",
)

@Serializable
data class CheckIn(
    /** 1 to 10. */
    val energy: Int,
    val minutes: Int,
)

/** Recovery signals read from Health Connect. Any of them may be missing. */
@Serializable
data class Recovery(
    val sleepHours: Double? = null,
    val restingHr: Double? = null,
    val restingHrBaseline: Double? = null,
    val hrvMs: Double? = null,
    val hrvBaseline: Double? = null,
    val stepsYesterday: Long? = null,
)

@Serializable
data class PlannedExercise(
    val name: String,
    val sets: Int,
    val repsMin: Int,
    val repsMax: Int,
    val weightKg: Double?,
    val targetRir: Int,
    val restSeconds: Int,
    val reason: String = "",
    /** Reps to aim for on the first working set; null means the top of the range. */
    val targetReps: Int? = null,
)

@Serializable
data class SessionPlan(
    val title: String,
    val summary: String,
    val exercises: List<PlannedExercise>,
    val source: String,
    /** How long the session should take at the lifter's learned pace. */
    val estimatedMinutes: Int? = null,
)
