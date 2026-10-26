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
        val ramp = "Ramp up so the first working set feels familiar, without tiring you"
        return when (Exercises.info(e.name).kind) {
            Kind.COMPOUND -> if (w >= 40) listOf(SetSuggestion(Progression.roundLoad(w * 0.5), 8, ramp), SetSuggestion(Progression.roundLoad(w * 0.75), 3, ramp)) else emptyList()
            Kind.ACCESSORY -> if (w >= 30) listOf(SetSuggestion(Progression.roundLoad(w * 0.6), 6, ramp)) else emptyList()
            Kind.ISOLATION -> emptyList()
        }
    }

    /** Why rest is this long, in a sentence the lifter reads between sets. */
    fun restReason(e: PlannedExercise): String = when (Exercises.info(e.name).kind) {
        Kind.COMPOUND -> "Heavy lifts lose reps on short rest, so take the full time"
        Kind.ACCESSORY -> "Enough rest to repeat your reps; past this adds little"
        Kind.ISOLATION -> "Small muscles recover fast; over 90 s adds little"
    }

    /** Suggestions for the working sets not yet logged, in order, each with a reason. */
    fun remaining(e: PlannedExercise, logged: List<LoggedSet>): List<SetSuggestion> {
        val working = logged.filter { it.type != SetType.WARMUP }
        val left = (e.sets - working.size).coerceAtLeast(0)
        if (left == 0) return emptyList()
        val last = working.lastOrNull()
        val next = if (last == null) {
            SetSuggestion(e.weightKg, e.targetReps ?: e.repsMax, "Opening set: stop with ${e.targetRir} in reserve")
        } else {
            // The lifter may log any weight; suggestions always land on the 2.5 kg grid.
            replan(e, last).let { it.copy(weightKg = it.weightKg?.let(Progression::roundLoad)) }
        }
        return List(left) { i ->
            val setNumber = working.size + i + 1
            when {
                i == 0 && setNumber == e.sets && last != null -> next.copy(note = next.note + ". " + lastSetNote(e))
                i == 0 -> next
                setNumber == e.sets -> next.copy(note = lastSetNote(e))
                else -> next.copy(note = "Same weight; losing a rep as you tire is normal")
            }
        }
    }

    private fun lastSetNote(e: PlannedExercise) = "Last set: get ${e.repsMax} on every set and the weight goes up next time"

    private fun replan(e: PlannedExercise, last: LoggedSet): SetSuggestion {
        val range = e.repsMin..e.repsMax
        val easier = (last.rir?.toInt() ?: e.targetRir) - e.targetRir
        val weight = last.weightKg.takeIf { it > 0 }
        val step = Progression.STEP_KG
        return when {
            weight == null -> SetSuggestion(e.weightKg?.takeIf { it > 0 }, last.reps.coerceAtLeast(1), "Match your last set")
            easier >= 2 || (easier >= 1 && last.reps >= e.repsMax) ->
                SetSuggestion(weight + step, (last.reps - 1).coerceIn(range), "Last set felt easy: up ${Progression.fmt(step)} kg")
            last.reps < e.repsMin || easier <= -2 ->
                SetSuggestion(min(Progression.roundLoad(weight * 0.9), weight - step).coerceAtLeast(0.0), e.repsMin, "Last set was a grind: lighter to stay in range")
            easier == -1 -> SetSuggestion(weight, (last.reps - 1).coerceIn(range), "Closer to failure than planned: aim one rep fewer")
            last.rir == null -> SetSuggestion(weight, last.reps.coerceIn(range), "Same again; log reps in reserve so the coach can adjust")
            else -> SetSuggestion(weight, last.reps.coerceIn(range), "On target: same again")
        }
    }

    /** The exercise to move on to once [finished] is done: the next unfinished one, wrapping round, or null. */
    fun nextUnfinished(exercises: List<PlannedExercise>, logged: Map<String, List<LoggedSet>>, finished: String): PlannedExercise? {
        val i = exercises.indexOfFirst { it.name == finished }
        val order = exercises.drop(i + 1) + exercises.take(i.coerceAtLeast(0))
        return order.firstOrNull { ex -> logged[ex.name].orEmpty().count { it.type != SetType.WARMUP } < ex.sets }
    }
}
