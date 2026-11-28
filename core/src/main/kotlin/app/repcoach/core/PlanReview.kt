package app.repcoach.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * A workout the coach recommended, kept with the conversation about it. The lifter trains and logs
 * in Hevy; once that workout arrives from Hevy, [PlanReview] puts it next to the plan.
 */
@Serializable
data class SavedPlan(
    val id: String,
    val createdMillis: Long,
    val energy: Int,
    val minutes: Int,
    val plan: SessionPlan,
    val chat: List<ChatTurn> = emptyList(),
)

/** How one exercise went against the plan. */
data class ExerciseComparison(
    val name: String,
    val planned: PlannedExercise?,
    val done: List<LoggedSet>,
    val verdict: String,
) {
    val planText: String get() = planned?.let {
        val reps = if (it.repsMin == it.repsMax) "${it.repsMin}" else "${it.repsMin}–${it.repsMax}"
        "${it.sets} × $reps @ ${it.weightKg?.let { w -> Progression.fmt(w) + " kg" } ?: "your choice"}"
    } ?: "not planned"

    val doneText: String get() = done.takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { "${Progression.fmt(it.weightKg)}×${it.reps}" } ?: "not done"
}

object PlanReview {
    /** A plan's workout must start within this long after the plan was made. */
    const val WINDOW_MILLIS = 4 * 24 * 3_600_000L
    /** Logging in Hevy can start a little before the plan was saved. */
    private const val EARLY_MILLIS = 2 * 3_600_000L

    /**
     * Pairs each plan with the logged workout that carried it out: the first workout after the plan
     * that shares its title (Send to Hevy names the routine after it) or at least half its exercises.
     * Each workout counts for one plan only, the most recent plan before it.
     */
    fun match(plans: List<SavedPlan>, workouts: List<Workout>): Map<String, Workout> {
        val result = mutableMapOf<String, Workout>()
        val used = mutableSetOf<String>()
        for (saved in plans.sortedByDescending { it.createdMillis }) {
            val names = saved.plan.exercises.map { it.name }.toSet()
            val found = workouts
                .filter { it.id !in used }
                .filter { it.startMillis >= saved.createdMillis - EARLY_MILLIS && it.startMillis <= saved.createdMillis + WINDOW_MILLIS }
                .sortedBy { it.startMillis }
                .firstOrNull { w ->
                    val shared = w.exercises.count { it.name in names }
                    saved.plan.title in w.title || (names.isNotEmpty() && shared * 2 >= names.size)
                } ?: continue
            result[saved.id] = found
            used += found.id
        }
        return result
    }

    fun compare(plan: SessionPlan, workout: Workout): List<ExerciseComparison> {
        val done = workout.exercises.associateBy { it.name }
        val planned = plan.exercises.map { p ->
            val sets = done[p.name]?.sets.orEmpty().filter { it.isWorking }
            ExerciseComparison(p.name, p, sets, verdict(p, sets))
        }
        val added = workout.exercises.filter { e -> plan.exercises.none { it.name == e.name } }.map { e ->
            ExerciseComparison(e.name, null, e.sets.filter { it.isWorking }, "Added: not in the plan")
        }
        return planned + added
    }

    private fun verdict(p: PlannedExercise, sets: List<LoggedSet>): String {
        if (sets.isEmpty()) return "Skipped"
        val notes = mutableListOf<String>()
        if (sets.size < p.sets) notes += "${sets.size} of ${p.sets} sets"
        if (sets.size > p.sets) notes += "${sets.size - p.sets} extra set${if (sets.size - p.sets == 1) "" else "s"}"
        val top = sets.maxOf { it.weightKg }
        p.weightKg?.let { target ->
            if (abs(top - target) >= 1.0) notes += if (top > target) "heavier than planned (${Progression.fmt(top)} kg)" else "lighter than planned (${Progression.fmt(top)} kg)"
        }
        val atWeight = sets.filter { it.weightKg >= top - 0.01 }
        notes += when {
            atWeight.size >= p.sets && atWeight.all { it.reps >= p.repsMax } -> "top of the range on every set: weight goes up next time"
            sets.any { it.reps < p.repsMin } -> "below the rep range on ${sets.count { it.reps < p.repsMin }} set${if (sets.count { it.reps < p.repsMin } == 1) "" else "s"}"
            else -> "in the rep range"
        }
        return notes.joinToString("; ").replaceFirstChar { it.uppercase() }
    }

    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    /** The plan, and how it went if it's been done, for the coach's briefing. */
    fun asText(saved: SavedPlan, workout: Workout?): String = buildString {
        val zone = ZoneId.systemDefault()
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(day)
        appendLine("## The recommended workout: ${saved.plan.title}")
        appendLine("Planned on ${date(saved.createdMillis)} for energy ${saved.energy}/10 and ${saved.minutes} minutes. ${saved.plan.summary}")
        appendLine(if (saved.plan.source == "claude") "Claude chose the exercises; the rules engine set weights and rep ranges." else "The built-in rules chose everything.")
        saved.plan.exercises.forEachIndexed { i, e ->
            appendLine("${i + 1}. ${e.name}: ${e.sets} x ${e.repsMin}-${e.repsMax} @ ${e.weightKg?.let { Progression.fmt(it) + " kg" } ?: "lifter's choice"}, ${e.targetRir} reps in reserve, rest ${e.restSeconds}s. ${e.reason}")
        }
        appendLine()
        if (workout == null) {
            appendLine("## How it went")
            appendLine("Not logged in Hevy yet.")
        } else {
            appendLine("## How it went: logged in Hevy on ${date(workout.startMillis)} as \"${workout.title}\", ${(workout.endMillis - workout.startMillis) / 60_000} min")
            compare(saved.plan, workout).forEach { c -> appendLine("${c.name}: planned ${c.planText}; did ${c.doneText}. ${c.verdict}.") }
        }
    }
}
