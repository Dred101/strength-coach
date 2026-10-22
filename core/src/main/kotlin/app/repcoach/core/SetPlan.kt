package app.repcoach.core

import kotlin.math.min

/** One suggested set. A null weight means bodyweight or "your choice". */
data class SetSuggestion(val weightKg: Double?, val reps: Int, val note: String = "")

/**
 * Lays out every set of an exercise up front, then re-plans the sets still to do from how the last
 * one went. This is RIR-based autoregulation (Helms et al. 2016): a set that felt two or more reps
 * easier than planned earns a 2.5 kg step up, and one that missed the rep range or went closer to
 * failure than planned brings the load down.
 */
object SetPlan {
    fun warmups(e: PlannedExercise): List<SetSuggestion> {
        val w = e.weightKg ?: return emptyList()
        return when (Exercises.info(e.name).kind) {
            Kind.COMPOUND -> if (w >= 40) listOf(SetSuggestion(Progression.roundLoad(w * 0.5), 8), SetSuggestion(Progression.roundLoad(w * 0.75), 3)) else emptyList()
            Kind.ACCESSORY -> if (w >= 30) listOf(SetSuggestion(Progression.roundLoad(w * 0.6), 6)) else emptyList()
            Kind.ISOLATION -> emptyList()
        }
    }

    /** Suggestions for the working sets not yet logged, in order. */
    fun remaining(e: PlannedExercise, logged: List<LoggedSet>): List<SetSuggestion> {
        val working = logged.filter { it.type != SetType.WARMUP }
        val left = (e.sets - working.size).coerceAtLeast(0)
        if (left == 0) return emptyList()
        val last = working.lastOrNull()
            ?: return List(left) { SetSuggestion(e.weightKg, e.targetReps ?: e.repsMax) }

        val range = e.repsMin..e.repsMax
        val easier = (last.rir?.toInt() ?: e.targetRir) - e.targetRir
        val weight = last.weightKg.takeIf { it > 0 }
        val step = Progression.STEP_KG

        val next = when {
            weight == null -> SetSuggestion(e.weightKg?.takeIf { it > 0 }, last.reps.coerceAtLeast(1))
            easier >= 2 || (easier >= 1 && last.reps >= e.repsMax) ->
                SetSuggestion(weight + step, (last.reps - 1).coerceIn(range), "Last set felt easy: up ${Progression.fmt(step)} kg")
            last.reps < e.repsMin || easier <= -2 ->
                SetSuggestion(min(Progression.roundLoad(weight * 0.9), weight - step).coerceAtLeast(0.0), e.repsMin, "Last set was a grind: lighter to stay in range")
            easier == -1 -> SetSuggestion(weight, (last.reps - 1).coerceIn(range), "Closer to failure than planned: aim one rep fewer")
            else -> SetSuggestion(weight, last.reps.coerceIn(range))
        }
        return List(left) { if (it == 0) next else next.copy(note = "") }
    }
}
