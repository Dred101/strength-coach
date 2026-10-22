package app.repcoach.core

import kotlin.math.roundToInt

/**
 * Builds a session without the AI: used as the offline fallback, and as the draft and the
 * progression targets that Claude works from.
 */
class Planner(private val progression: Progression) {
    data class Slot(val muscle: Muscle, val kind: Kind?)

    private val fullBody = listOf(
        // A: squat and flat bench focus
        listOf(Slot(Muscle.QUADS, Kind.COMPOUND), Slot(Muscle.CHEST, Kind.COMPOUND), Slot(Muscle.BACK, Kind.ACCESSORY),
            Slot(Muscle.HAMSTRINGS, null), Slot(Muscle.SHOULDERS, Kind.ISOLATION), Slot(Muscle.TRICEPS, null), Slot(Muscle.BICEPS, null)),
        // B: incline and rows, machine legs
        listOf(Slot(Muscle.CHEST, Kind.ACCESSORY), Slot(Muscle.QUADS, Kind.ACCESSORY), Slot(Muscle.BACK, Kind.ACCESSORY),
            Slot(Muscle.SHOULDERS, Kind.ACCESSORY), Slot(Muscle.GLUTES, null), Slot(Muscle.REAR_DELTS, null), Slot(Muscle.BICEPS, null)),
        // C: bench again, pull focus, posterior chain
        listOf(Slot(Muscle.CHEST, Kind.COMPOUND), Slot(Muscle.BACK, Kind.COMPOUND), Slot(Muscle.QUADS, null),
            Slot(Muscle.HAMSTRINGS, null), Slot(Muscle.CHEST, Kind.ISOLATION), Slot(Muscle.TRICEPS, null), Slot(Muscle.CORE, null)),
    )

    fun plan(checkIn: CheckIn, recovery: Recovery? = null): SessionPlan {
        val energy = adjustedEnergy(checkIn.energy, recovery)
        val extraSession = progression.sessionsThisWeek() >= 3
        val used = mutableSetOf<String>()
        val lastSession = progression.recent(10).lastOrNull()?.exercises?.map { it.name }?.toSet().orEmpty()

        val slots = if (extraSession) varietySlots() else fullBody[progression.recent(21).size % fullBody.size]
        val picks = slots.mapNotNull { slot -> pick(slot, used, avoid = if (extraSession) lastSession else emptySet()) }
            .onEach { used += it }
            .toMutableList()

        // Add a supporting lift for the first plateaued compound that isn't already covered.
        val stalledCompounds = progression.plateaus().filter { Exercises.info(it.exercise).kind == Kind.COMPOUND }
        val stalled = stalledCompounds.firstOrNull()
        if (stalled != null) supportFor(stalled.exercise).firstOrNull { it !in used }?.let { picks.add(2.coerceAtMost(picks.size), it) }

        val budget = (checkIn.minutes - 5).coerceAtLeast(15) * 60 // five minutes to warm up
        val targets = mutableListOf<Target>()
        var spent = 0
        for (name in picks) {
            val t = progression.target(name, energy)
            val cost = t.sets * (40 + t.restSeconds) + 60 // plus a minute for warm-up sets and setup
            if (spent + cost > budget && targets.size >= 3) break
            targets += t
            spent += cost
        }

        val title = if (extraSession) "Variety session" else "Full body ${"ABC"[progression.recent(21).size % 3]}"
        val summary = buildString {
            append(if (extraSession) "You've already done 3 sessions this week, so today mixes things up." else "Full-body day.")
            if (energy <= 4) append(" Energy is low, so volume is trimmed and nothing goes up in weight.")
            if (stalled != null) append(" ${stalled.exercise} has stalled, so there's extra support work for it.")
            // Deloads cut volume and effort rather than stopping training: a full week off slightly
            // blunted strength gains in Coleman et al. 2024. See docs/RESEARCH.md.
            if (stalledCompounds.size >= 2) append(" Several big lifts have stalled. If you also feel run down, make this a lighter week: about half the sets, same weights, 3 or more reps in reserve.")
        }
        return SessionPlan(title, summary, targets.map { it.toPlanned() }, source = "rules")
    }

    /** Lowers effective energy when recovery data shows poor sleep or an elevated resting HR. */
    fun adjustedEnergy(energy: Int, recovery: Recovery?): Int {
        var e = energy
        recovery?.sleepHours?.let { if (it < 6.0) e -= 1 }
        val rhr = recovery?.restingHr
        val base = recovery?.restingHrBaseline
        if (rhr != null && base != null && rhr > base + 5) e -= 1
        return e.coerceIn(1, 10)
    }

    private fun varietySlots(): List<Slot> {
        val volume = progression.weeklyVolume()
        val neglected = volume.entries.sortedBy { it.value }.map { it.key }.filter { it != Muscle.CORE }.take(5)
        return neglected.map { Slot(it, null) } + Slot(Muscle.CORE, null)
    }

    private fun pick(slot: Slot, used: Set<String>, avoid: Set<String>): String? {
        val history = progression.exerciseNames()
        val fits = { name: String ->
            val info = Exercises.info(name)
            info.primary == slot.muscle && (slot.kind == null || info.kind == slot.kind) && name !in used
        }
        // Variety sessions prefer what hasn't been done for longest; normal days prefer the staple.
        val fromHistory = history.filter(fits).filter { it !in avoid }
        val chosen = if (avoid.isNotEmpty()) fromHistory.minByOrNull { progression.lastDone(it) ?: 0L } else fromHistory.firstOrNull()
        return chosen ?: Exercises.starterLibrary.firstOrNull { fits(it) && it !in avoid }
    }

    companion object {
        /** Accessories that commonly help a stalled compound lift. */
        fun supportFor(exercise: String): List<String> {
            val n = exercise.lowercase()
            return when {
                "bench" in n -> listOf("Close Grip Bench Press (Barbell)", "Incline Bench Press (Dumbbell)", "Triceps Rope Pushdown", "Chest Supported Row (Dumbbell)")
                "squat" in n -> listOf("Bulgarian Split Squat", "Leg Press (Machine)", "Romanian Deadlift (Barbell)")
                "deadlift" in n -> listOf("Romanian Deadlift (Barbell)", "Hip Thrust (Barbell)", "Seated Cable Row")
                "press" in n -> listOf("Lateral Raise (Dumbbell)", "Triceps Rope Pushdown", "Face Pull")
                "pull" in n || "row" in n -> listOf("Chest Supported Row (Dumbbell)", "Hammer Curl (Dumbbell)", "Face Pull")
                else -> emptyList()
            }
        }
    }
}

fun Target.toPlanned() = PlannedExercise(
    name = exercise, sets = sets, repsMin = repsMin, repsMax = repsMax,
    weightKg = weightKg, targetRir = targetRir, restSeconds = restSeconds, reason = note, targetReps = targetReps,
)

fun Double.oneDecimal(): Double = (this * 10).roundToInt() / 10.0
