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
        var energy = adjustedEnergy(checkIn.energy, recovery)
        val extraSession = progression.sessionsThisWeek() >= 3
        val used = mutableSetOf<String>()
        val lastSession = progression.recent(10).lastOrNull()?.exercises?.map { it.name }?.toSet().orEmpty()

        // Muscles that did hard sets in the last 48 hours aren't ready to be trained hard again.
        val muscles = MuscleRecovery(progression.workouts, progression.now)
        val tired = muscles.recovering()
        val tiredMajor = tired.intersect(MuscleRecovery.MAJOR)
        val day = nextDay()
        val base = if (extraSession) varietySlots() else fullBody[day]
        val fresh = base.filter { it.muscle !in tired }
        // Most of the body is still recovering: rest is the best plan, with an easy option if they want one.
        val restDay = tiredMajor.size >= 4 && fresh.count { it.muscle in MuscleRecovery.MAJOR } < 2
        val slots = when {
            restDay -> (fresh + varietySlots(exclude = tired + fresh.map { it.muscle })).take(4).ifEmpty { base.take(4) }
            tired.isEmpty() -> base
            // Swap tired muscles for the recovered ones with the fewest sets this week.
            else -> fresh + varietySlots(exclude = tired + fresh.map { it.muscle }).take(base.size - fresh.size)
        }
        if (restDay) energy = energy.coerceAtMost(4)
        val avoid = if (extraSession || tired.isNotEmpty()) lastSession else emptySet()
        val picks = slots.mapNotNull { slot -> pick(slot, used, avoid = avoid) }
            .onEach { used += it }
            .toMutableList()

        // Add a supporting lift for the first plateaued compound that isn't already covered.
        val stalledCompounds = progression.plateaus().filter { Exercises.info(it.exercise).kind == Kind.COMPOUND }
        val stalled = stalledCompounds.firstOrNull()?.takeUnless { restDay }
        if (stalled != null) supportFor(stalled.exercise).firstOrNull { it !in used && Exercises.info(it).primary !in tired }
            ?.let { picks.add(2.coerceAtMost(picks.size), it) }

        // Fit the time available at the pace this lifter actually trains, learned from past sessions.
        val pace = Pace(progression.workouts)
        val budget = pace.budgetSeconds(checkIn.minutes)
        val targets = mutableListOf<Target>()
        var spent = 0
        for (name in picks) {
            val muscle = Exercises.info(name).primary
            var t = progression.target(name, energy)
            if (muscle in tired) t = t.copy(sets = 2, targetRir = t.targetRir + 1)
            t = t.copy(note = "${t.note} ${muscles.readyNote(muscle)}")
            val cost = pace.exerciseSeconds(t.sets, t.restSeconds)
            // Skip what doesn't fit, so a shorter exercise further down can still fill the time.
            if (spent + cost > budget && targets.size >= 3) continue
            targets += t
            spent += cost
        }

        val title = when {
            restDay -> "Recovery day"
            extraSession -> "Variety session"
            else -> "Full body ${"ABC"[day]}"
        }
        val summary = buildString {
            append(
                when {
                    restDay -> "Your ${tiredMajor.sortedBy { it.ordinal }.joinToString(", ") { it.label() }} did hard sets in the last 48 hours and aren't recovered yet, so resting today is the best choice. If you'd still like to train, this is an easy session at the same weights, well short of failure."
                    extraSession -> "You've already done 3 sessions this week, so today mixes things up and tops up the muscles with the fewest sets."
                    else -> "Full-body day ${progression.sessionsThisWeek() + 1} of 3: training every muscle two or three times a week helps strength most."
                }
            )
            if (!restDay && tired.isNotEmpty()) append(" Your ${tired.sortedBy { it.ordinal }.joinToString(", ") { it.label() }} trained hard in the last 48 hours, so today works muscles that have recovered.")
            if (energy <= 4 && !restDay) append(" Energy is low, so volume is trimmed and nothing goes up in weight.")
            if (stalled != null) append(" ${stalled.exercise} has stalled, so there's extra support work for it.")
            // Deloads cut volume and effort rather than stopping training: a full week off slightly
            // blunted strength gains in Coleman et al. 2024. See docs/RESEARCH.md.
            if (stalledCompounds.size >= 2 && !restDay) append(" Several big lifts have stalled. If you also feel run down, make this a lighter week: about half the sets, same weights, 3 or more reps in reserve.")
        }
        val planned = targets.map { it.toPlanned() }
        val minutes = pace.planMinutes(planned)
        return SessionPlan(title, "$summary About $minutes minutes at your usual pace.", planned, source = "rules", estimatedMinutes = minutes)
    }

    /** The full-body day after the last one done; by session count if no day is named in the history. */
    private fun nextDay(): Int {
        val letter = progression.workouts.lastOrNull { it.title.startsWith("Full body ") }?.title?.removePrefix("Full body ")?.firstOrNull()
        val i = if (letter == null) -1 else "ABC".indexOf(letter)
        return if (i >= 0) (i + 1) % fullBody.size else progression.recent(21).size % fullBody.size
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

    private fun varietySlots(exclude: Set<Muscle> = emptySet()): List<Slot> {
        val volume = progression.weeklyVolume()
        val neglected = volume.entries.sortedBy { it.value }.map { it.key }.filter { it != Muscle.CORE && it !in exclude }.take(5)
        return neglected.map { Slot(it, null) } + listOf(Slot(Muscle.CORE, null)).filter { Muscle.CORE !in exclude }
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
