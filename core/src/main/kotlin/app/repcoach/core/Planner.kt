package app.repcoach.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Builds a session without the AI: used as the offline fallback, and as the draft and the
 * progression targets that Claude works from.
 */
class Planner(private val progression: Progression, programme: Programme? = null) {
    data class Slot(val muscle: Muscle, val kind: Kind?)

    /** The lifter's Full Body A and B; designed from their history if none has been saved yet. */
    val programme: Programme = programme ?: Programme.design(progression)

    /**
     * The programme day still to do this week (Monday to Sunday), alternating with the last one
     * done, or null once A and B are both done.
     */
    fun dueDay(zone: ZoneId = ZoneId.systemDefault()): ProgrammeDay? {
        val today = Instant.ofEpochMilli(progression.now).atZone(zone).toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val done = progression.workouts.filter { it.startMillis >= monday }.mapNotNull { dayKey(it.title) }.toSet()
        val left = programme.days.filter { it.key !in done }
        val lastKey = progression.workouts.lastOrNull { dayKey(it.title) != null }?.let { dayKey(it.title) }
        return left.firstOrNull { it.key != lastKey } ?: left.firstOrNull()
    }

    fun plan(checkIn: CheckIn, recovery: Recovery? = null): SessionPlan {
        var energy = adjustedEnergy(checkIn.energy, recovery)
        val muscles = MuscleRecovery(progression.workouts, progression.now)
        val tired = muscles.recovering()

        // A or B when it's due and its main lifts' muscles have recovered from the last 48 hours.
        val due = dueDay()
        val dueBlocked = due?.exercises.orEmpty().filter { it.anchor }.map { Exercises.info(it.name).primary }.filter { it in tired }.distinct()
        if (due != null && dueBlocked.isEmpty()) return programmeDay(due, checkIn, energy, muscles)

        // Otherwise an in-between day: exercises rotate freely, away from A and B's lifts.
        val used = mutableSetOf<String>()
        val lastSession = progression.recent(10).lastOrNull()?.exercises?.map { it.name }?.toSet().orEmpty()
        val tiredMajor = tired.intersect(MuscleRecovery.MAJOR)
        val base = varietySlots()
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
        val avoid = lastSession + programme.allNames
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

        val title = if (restDay) "Recovery day" else "Mixed session"
        val summary = buildString {
            append(
                when {
                    due != null -> "${due.title} is due, but your ${dueBlocked.joinToString(", ") { it.label() }} trained hard in the last 48 hours, so do it in a day or two. "
                    else -> "Full Body A and B are done this week. "
                } + when {
                    restDay -> "Your ${tiredMajor.sortedBy { it.ordinal }.joinToString(", ") { it.label() }} did hard sets in the last 48 hours and aren't recovered yet, so resting today is the best choice. If you'd still like to train, this is an easy session at the same weights, well short of failure."
                    else -> "Today is a mixed session: different exercises from A and B, for the muscles with the fewest sets this week."
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

    /** Full Body A or B exactly as designed: the same lifts every week, each one progressed. */
    private fun programmeDay(day: ProgrammeDay, checkIn: CheckIn, energy: Int, muscles: MuscleRecovery): SessionPlan {
        val pace = Pace(progression.workouts)
        val budget = pace.budgetSeconds(checkIn.minutes)
        val week = programme.week(progression.now)
        val targets = mutableListOf<Target>()
        var spent = 0
        for (ex in day.exercises) {
            var t = progression.target(ex.name, energy, ex.sets)
            val role = if (ex.anchor) "Main lift, fixed for the block: beat last time's weight or reps." else "Supporting lift for this block."
            t = t.copy(note = "$role ${t.note} ${muscles.readyNote(Exercises.info(ex.name).primary)}")
            val cost = pace.exerciseSeconds(t.sets, t.restSeconds)
            // Short on time: keep every main lift and drop supporting lifts that don't fit.
            if (!ex.anchor && spent + cost > budget) continue
            targets += t
            spent += cost
        }
        val stalled = progression.plateaus().filter { it.exercise in programme.anchors }
        val summary = buildString {
            append("Week ${week.coerceAtMost(99)} of ${Programme.BLOCK_WEEKS} of this block. ")
            append("The main lifts stay the same all block so each session can beat the last, and A plus B train every muscle twice a week.")
            if (week > Programme.BLOCK_WEEKS) append(" This block has run its course: start a new block in Settings to freshen the supporting lifts.")
            if (energy <= 4) append(" Energy is low, so volume is trimmed and nothing goes up in weight.")
            if (stalled.isNotEmpty()) append(" ${stalled.joinToString(", ") { it.exercise }} ${if (stalled.size == 1) "has" else "have"} stalled; Ask why for options.")
        }
        val planned = targets.map { it.toPlanned() }
        val minutes = pace.planMinutes(planned)
        return SessionPlan(day.title, "$summary About $minutes minutes at your usual pace.", planned, source = "rules", estimatedMinutes = minutes)
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
        /** "A" for any title naming Full Body A, including Hevy's "Rep Coach: Full Body A: …". */
        fun dayKey(title: String): String? = Regex("""Full Body ([A-Z])\b""").find(title)?.groupValues?.get(1)

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
