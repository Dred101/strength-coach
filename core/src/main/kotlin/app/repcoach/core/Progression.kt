package app.repcoach.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

/** One session's worth of an exercise. */
data class Exposure(
    val startMillis: Long,
    val bestE1rm: Double,
    val topWeight: Double,
    /** Working sets (no warm-ups or drop sets), in order. */
    val workingSets: List<LoggedSet>,
)

data class Target(
    val exercise: String,
    val sets: Int,
    val repsMin: Int,
    val repsMax: Int,
    val weightKg: Double?,
    val targetRir: Int,
    val restSeconds: Int,
    val note: String,
)

data class Trend(
    val exercise: String,
    val exposures: Int,
    val bestRecentE1rm: Double,
    val bestPreviousE1rm: Double?,
    val changePercent: Double?,
    val plateau: Boolean,
)

/**
 * The deterministic half of the coach: estimated 1RM, double progression, plateau detection and
 * weekly volume. Claude chooses exercises; this decides the numbers.
 */
class Progression(workouts: List<Workout>, private val now: Long = System.currentTimeMillis()) {
    private val workouts = workouts.sortedBy { it.startMillis }

    val exposures: Map<String, List<Exposure>> = buildMap<String, MutableList<Exposure>> {
        for (w in this@Progression.workouts) for (ex in w.exercises) {
            val working = ex.sets.filter { it.type == SetType.NORMAL || it.type == SetType.FAILURE }
            if (working.isEmpty()) continue
            getOrPut(ex.name) { mutableListOf() }.add(
                Exposure(
                    startMillis = w.startMillis,
                    bestE1rm = working.maxOf { e1rm(it) },
                    topWeight = working.maxOf { it.weightKg },
                    workingSets = working,
                )
            )
        }
    }

    fun exerciseNames(): List<String> = exposures.keys.sortedByDescending { exposures.getValue(it).size }

    fun lastDone(exercise: String): Long? = exposures[exercise]?.lastOrNull()?.startMillis

    /** Next session's prescription for an exercise, adjusted for today's energy (1 to 10). */
    fun target(exercise: String, energy: Int = 7, baseSets: Int = 3): Target {
        val info = Exercises.info(exercise)
        val range = info.repRange
        val low = energy <= 4
        val high = energy >= 8
        val rir = if (low) 3 else 2
        val sets = (if (low) baseSets - 1 else baseSets).coerceAtLeast(2)
        val last = exposures[exercise]?.lastOrNull()
            ?: return Target(exercise, sets, range.first, range.last, null, rir, info.restSeconds,
                "New exercise: pick a weight you could lift for ${range.last + rir} reps.")

        val top = last.topWeight
        val atTop = last.workingSets.filter { it.weightKg >= top - 0.01 }
        val bestReps = atTop.maxOf { it.reps }
        val allHitTop = atTop.size >= 2 && atTop.all { it.reps >= range.last }

        return when {
            top <= 0.0 -> Target(exercise, sets, range.first, range.last, null, rir, info.restSeconds,
                "Bodyweight: aim for ${min(bestReps + 1, range.last + 5)} reps.")
            bestReps < range.first -> {
                // Last time was heavier than this rep range; set the load from estimated 1RM instead.
                val w = roundLoad(weightFor(last.bestE1rm, range.first, rir))
                Target(exercise, sets, range.first, range.last, w, rir, info.restSeconds,
                    "Working in the ${range.first} to ${range.last} rep range at ${fmt(w)} kg.")
            }
            allHitTop && !low -> {
                val w = roundLoad(top + info.incrementKg)
                Target(exercise, sets, range.first, range.last, w, rir, info.restSeconds,
                    "Hit ${range.last} reps on every set last time: up to ${fmt(w)} kg.")
            }
            else -> {
                val goal = min(bestReps + 1, range.last)
                val note = if (low) "Low energy: hold ${fmt(top)} kg and leave ${rir} reps in reserve."
                else "Same weight, aim for $goal reps on your first set" + (if (high) " and beat last time on the others." else ".")
                Target(exercise, sets, range.first, range.last, top, rir, info.restSeconds, note)
            }
        }
    }

    /**
     * Compares the best estimated 1RM over the last [windowWeeks] with the [windowWeeks] before it.
     * A lift that has been trained regularly but gained under 1% is flagged as a plateau.
     */
    fun trend(exercise: String, windowWeeks: Long = 8): Trend? {
        val list = exposures[exercise] ?: return null
        val recentFrom = now - ChronoUnit.WEEKS.duration.toMillis() * windowWeeks
        val previousFrom = recentFrom - ChronoUnit.WEEKS.duration.toMillis() * windowWeeks
        val recent = list.filter { it.startMillis >= recentFrom }
        val previous = list.filter { it.startMillis in previousFrom until recentFrom }
        if (recent.isEmpty()) return null
        val bestRecent = recent.maxOf { it.bestE1rm }
        val bestPrevious = previous.maxOfOrNull { it.bestE1rm }
        val change = bestPrevious?.let { (bestRecent - it) / it * 100 }
        val plateau = change != null && recent.size >= 4 && previous.size >= 4 && change < 1.0
        return Trend(exercise, list.size, bestRecent, bestPrevious, change, plateau)
    }

    fun plateaus(): List<Trend> = exerciseNames().mapNotNull { trend(it) }.filter { it.plateau }

    /** Hard sets per muscle in the last [days] days; secondary muscles count as half a set. */
    fun weeklyVolume(days: Long = 7): Map<Muscle, Double> {
        val from = now - ChronoUnit.DAYS.duration.toMillis() * days
        val volume = Muscle.entries.associateWith { 0.0 }.toMutableMap()
        for (w in workouts.filter { it.startMillis >= from }) for (ex in w.exercises) {
            val info = Exercises.info(ex.name)
            val hard = ex.sets.count { it.isWorking }.toDouble()
            volume[info.primary] = volume.getValue(info.primary) + hard
            for (m in info.secondary) volume[m] = volume.getValue(m) + hard / 2
        }
        return volume
    }

    /** Days since each muscle was last trained as a primary mover, or null if never. */
    fun daysSinceMuscle(): Map<Muscle, Int?> = Muscle.entries.associateWith { m ->
        workouts.lastOrNull { w -> w.exercises.any { Exercises.info(it.name).primary == m } }
            ?.let { daysBetween(it.startMillis, now) }
    }

    fun sessionsThisWeek(zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val from = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        return workouts.count { it.startMillis >= from }
    }

    fun recent(days: Long): List<Workout> {
        val from = now - ChronoUnit.DAYS.duration.toMillis() * days
        return workouts.filter { it.startMillis >= from }
    }

    companion object {
        /** Epley estimate, counting reps in reserve as reps the lifter could have done. */
        fun e1rm(set: LoggedSet): Double {
            if (set.reps <= 0 || set.weightKg <= 0) return 0.0
            val reps = set.reps + (set.rir ?: 0.0)
            return set.weightKg * (1 + reps / 30.0)
        }

        fun weightFor(e1rm: Double, reps: Int, rir: Int): Double = e1rm / (1 + (reps + rir) / 30.0)

        fun roundLoad(kg: Double): Double = (kg * 2).roundToInt() / 2.0

        fun daysBetween(from: Long, to: Long): Int = max(0, ((to - from) / ChronoUnit.DAYS.duration.toMillis()).toInt())

        fun fmt(kg: Double): String = if (kg % 1.0 == 0.0) kg.toInt().toString() else "%.1f".format(java.util.Locale.UK, kg)

        private fun min(a: Int, b: Int) = if (a < b) a else b
    }
}
