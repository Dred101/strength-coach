package app.repcoach.core

import kotlin.math.max

/**
 * How recovered each muscle is from its last hard sets. After sets taken to failure, strength and
 * speed took 24 to 48 hours or more to come back; sets stopped short of failure were back within
 * about 24 hours (Morán-Navarro et al. 2017). So the gap depends on how hard the muscle worked: a
 * normal dose (3 to 5 hard sets, short of failure) needs [READY_HOURS], and only a big dose (6 or
 * more sets) or sets taken to failure need the full [HEAVY_HOURS]. Training on back-to-back days is
 * fine in itself when weekly volume is the same (Yang et al. 2018). See docs/RESEARCH.md.
 */
class MuscleRecovery(workouts: List<Workout>, private val now: Long = System.currentTimeMillis()) {
    data class Status(
        val muscle: Muscle,
        /** Hours since this muscle last did any hard sets, or null if never. */
        val hoursSince: Int?,
        /** Hard sets in the last [HEAVY_HOURS], counting assisting muscles as half a set. */
        val recentSets: Double,
        /** Whether any of those sets went to failure (logged as failure, or 0 reps in reserve). */
        val toFailure: Boolean = false,
    ) {
        /** How long this muscle needs after that work: longer only for a big dose or sets to failure. */
        val needsHours: Int get() = if (recentSets >= HEAVY_SETS || toFailure) HEAVY_HOURS else READY_HOURS

        val recovering: Boolean get() = hoursSince != null && hoursSince < needsHours && recentSets >= MIN_SETS

        /** Hours until it's ready again; 0 when it already is. */
        val hoursLeft: Int get() = if (recovering) needsHours - (hoursSince ?: needsHours) else 0
    }

    val status: Map<Muscle, Status> = run {
        val sets = Muscle.entries.associateWith { 0.0 }.toMutableMap()
        val lastHit = mutableMapOf<Muscle, Long>()
        val failed = mutableSetOf<Muscle>()
        val windowStart = now - HEAVY_HOURS * HOUR
        for (w in workouts) for (ex in w.exercises) {
            val hard = ex.sets.count { it.isWorking }.toDouble()
            if (hard == 0.0) continue
            val toFailure = ex.sets.any { it.isWorking && (it.type == SetType.FAILURE || (it.rir != null && it.rir <= 0.5)) }
            val info = Exercises.info(ex.name)
            val at = max(w.startMillis, w.endMillis)
            for ((m, share) in listOf(info.primary to 1.0) + info.secondary.map { it to 0.5 }) {
                lastHit[m] = max(lastHit[m] ?: 0L, at)
                if (at >= windowStart && at <= now) {
                    sets[m] = sets.getValue(m) + hard * share
                    if (toFailure && share == 1.0) failed += m
                }
            }
        }
        Muscle.entries.associateWith { m ->
            Status(m, lastHit[m]?.let { ((now - it) / HOUR).toInt().coerceAtLeast(0) }, sets.getValue(m), m in failed)
        }
    }

    fun recovering(): Set<Muscle> = status.values.filter { it.recovering }.map { it.muscle }.toSet()

    /** One line for a muscle, e.g. "chest: 6 hard sets 20 h ago, ready in about 28 h". */
    fun describe(m: Muscle): String {
        val s = status.getValue(m)
        val name = m.label()
        return when {
            s.hoursSince == null -> "$name: not trained yet"
            s.recovering -> "$name: ${s.recentSets.oneDecimal().fmtSets()} hard sets ${s.hoursSince} h ago, ready in about ${s.hoursLeft} h"
            s.hoursSince < HEAVY_HOURS && s.recentSets < MIN_SETS -> "$name: ready, only light work ${ago(s.hoursSince)}"
            else -> "$name: recovered, last trained ${ago(s.hoursSince)}"
        }
    }

    /** A short reason for the exercise card. */
    fun readyNote(m: Muscle): String {
        val s = status.getValue(m)
        val name = m.label().replaceFirstChar { it.uppercase() }
        val be = m.verb()
        val h = s.hoursSince ?: return "$name $be fresh: no hard sets logged yet."
        return when {
            s.recovering -> "$name $be still recovering (trained ${ago(h)}), so keep this easy."
            h < HEAVY_HOURS && s.recentSets < MIN_SETS -> "$name only did light work ${ago(h)}, so ${if (be == "is") "it's" else "they're"} ready."
            else -> "$name $be recovered: last trained ${ago(h)}."
        }
    }

    companion object {
        /** A normal dose of hard sets on a muscle this recently means it isn't ready for hard sets again. */
        const val READY_HOURS = 36
        /** The gap after a big dose ([HEAVY_SETS] or more) or sets taken to failure. */
        const val HEAVY_HOURS = 48
        /** This many hard sets on one muscle in one go counts as a big dose. */
        const val HEAVY_SETS = 6.0
        /** Fewer hard sets than this (say, a couple of assisting sets) doesn't need a recovery gap. */
        const val MIN_SETS = 3.0
        private const val HOUR = 3_600_000L

        /** The big muscle groups. When most of these are recovering, a rest day is the best plan. */
        val MAJOR = setOf(Muscle.CHEST, Muscle.BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.SHOULDERS)

        fun ago(hours: Int): String = when {
            hours < 36 -> "$hours hours ago"
            else -> "${(hours + 12) / 24} days ago"
        }

        private fun Double.fmtSets(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()
    }
}

fun Muscle.label(): String = name.lowercase().replace('_', ' ')

/** "is" or "are", so "quads are" and "chest is" read right. */
fun Muscle.verb(): String = if (this == Muscle.CHEST || this == Muscle.BACK || this == Muscle.CORE) "is" else "are"
