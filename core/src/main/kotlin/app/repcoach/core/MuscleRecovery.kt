package app.repcoach.core

import kotlin.math.max

/**
 * How recovered each muscle is from its last hard sets. Performance takes 24 to 48 hours or more to
 * come back after sets taken close to failure (Morán-Navarro et al. 2017), so a muscle that did real
 * work in the last 48 hours isn't given hard sets again. Training on back-to-back days is fine in
 * itself when weekly volume is the same (Yang et al. 2018): what matters is not hitting the same
 * muscle hard before it's back. See docs/RESEARCH.md.
 */
class MuscleRecovery(workouts: List<Workout>, private val now: Long = System.currentTimeMillis()) {
    data class Status(
        val muscle: Muscle,
        /** Hours since this muscle last did any hard sets, or null if never. */
        val hoursSince: Int?,
        /** Hard sets in the last [READY_HOURS], counting assisting muscles as half a set. */
        val recentSets: Double,
    ) {
        val recovering: Boolean get() = hoursSince != null && hoursSince < READY_HOURS && recentSets >= MIN_SETS

        /** Hours until it's ready again; 0 when it already is. */
        val hoursLeft: Int get() = if (recovering) READY_HOURS - (hoursSince ?: READY_HOURS) else 0
    }

    val status: Map<Muscle, Status> = run {
        val sets = Muscle.entries.associateWith { 0.0 }.toMutableMap()
        val lastHit = mutableMapOf<Muscle, Long>()
        val windowStart = now - READY_HOURS * HOUR
        for (w in workouts) for (ex in w.exercises) {
            val hard = ex.sets.count { it.isWorking }.toDouble()
            if (hard == 0.0) continue
            val info = Exercises.info(ex.name)
            val at = max(w.startMillis, w.endMillis)
            for ((m, share) in listOf(info.primary to 1.0) + info.secondary.map { it to 0.5 }) {
                lastHit[m] = max(lastHit[m] ?: 0L, at)
                if (at >= windowStart && at <= now) sets[m] = sets.getValue(m) + hard * share
            }
        }
        Muscle.entries.associateWith { m ->
            Status(m, lastHit[m]?.let { ((now - it) / HOUR).toInt().coerceAtLeast(0) }, sets.getValue(m))
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
            s.hoursSince < READY_HOURS -> "$name: ready, only light work ${ago(s.hoursSince)}"
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
            h < READY_HOURS -> "$name only did light work ${ago(h)}, so ${if (be == "is") "it's" else "they're"} ready."
            else -> "$name $be recovered: last trained ${ago(h)}."
        }
    }

    companion object {
        /** Hard work on a muscle this recently means it isn't ready for hard sets again. */
        const val READY_HOURS = 48
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
