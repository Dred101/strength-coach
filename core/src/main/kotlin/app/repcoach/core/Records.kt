package app.repcoach.core

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields

enum class RecordKind(val label: String) {
    HEAVIEST("Heaviest weight"),
    E1RM("Best estimated 1RM"),
    REP_MAX("Rep max"),
    SET_VOLUME("Best set (weight × reps)"),
    SESSION_VOLUME("Most volume in a session"),
    MOST_REPS("Most reps in a set"),
}

/** One personal record: [value] is kg for weight records and reps for [RecordKind.MOST_REPS]. */
data class PersonalRecord(
    val exercise: String,
    val kind: RecordKind,
    /** For rep maxes: the rep count, e.g. 5 for a 5RM. */
    val reps: Int? = null,
    val value: Double,
    val weightKg: Double,
    val setReps: Int,
    val achievedMillis: Long,
) {
    val title: String get() = if (kind == RecordKind.REP_MAX) "${reps}RM" else kind.label

    val detail: String get() = when (kind) {
        RecordKind.E1RM -> "${Progression.fmt(value)} kg, from ${Progression.fmt(weightKg)} kg × $setReps"
        RecordKind.SET_VOLUME, RecordKind.SESSION_VOLUME -> "${Progression.fmt(value)} kg" +
            if (kind == RecordKind.SET_VOLUME) " (${Progression.fmt(weightKg)} kg × $setReps)" else ""
        RecordKind.MOST_REPS -> "$setReps reps" + if (weightKg > 0) " at ${Progression.fmt(weightKg)} kg" else ""
        else -> "${Progression.fmt(weightKg)} kg × $setReps"
    }

    /** Identifies the slot this record holds, so a new record can replace an old one. */
    val key: String get() = "$exercise|$kind|${reps ?: ""}"
}

/** A point on a progress chart: one session's value for an exercise. */
data class ChartPoint(val millis: Long, val value: Double, val isRecord: Boolean)

enum class ChartMetric(val label: String, val unit: String) {
    E1RM("Estimated 1RM", "kg"),
    TOP_WEIGHT("Top weight", "kg"),
    VOLUME("Session volume", "kg"),
}

/**
 * Personal records across several levels per lift: heaviest weight, estimated 1RM, rep maxes at
 * 1, 3, 5, 8, 10 and 12 reps, best set and session volume, and most reps. Warm-ups and drop sets
 * don't count.
 */
object Records {
    val repMaxLevels = listOf(1, 3, 5, 8, 10, 12)

    private fun countable(set: LoggedSet) = (set.type == SetType.NORMAL || set.type == SetType.FAILURE) && set.reps > 0

    /** Current all-time records for every exercise, newest workouts winning ties by being later. */
    fun all(workouts: List<Workout>): Map<String, List<PersonalRecord>> {
        val best = LinkedHashMap<String, PersonalRecord>()
        for (w in workouts.sortedBy { it.startMillis }) for (pr in candidates(w)) {
            val current = best[pr.key]
            if (current == null || pr.value > current.value + 1e-9) best[pr.key] = pr
        }
        return best.values.groupBy { it.exercise }.mapValues { (_, list) -> list.sortedWith(order) }
    }

    /** Records [workout] sets compared with everything before it. */
    fun newIn(history: List<Workout>, workout: Workout): List<PersonalRecord> {
        val before = all(history.filter { it.startMillis < workout.startMillis && it.id != workout.id })
            .values.flatten().associateBy { it.key }
        return candidates(workout)
            .groupBy { it.key }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.value } }
            .filter { pr ->
                val old = before[pr.key]
                // Only exercises with history can set a record; the first time doesn't count.
                before.keys.any { it.startsWith(pr.exercise + "|") } && (old == null || pr.value > old.value + 1e-9)
            }
            .sortedWith(order)
    }

    /** Which records a single set would break, for the in-workout alert. */
    fun brokenBy(set: LoggedSet, exercise: String, records: List<PersonalRecord>): List<PersonalRecord> {
        if (!countable(set) || records.isEmpty()) return emptyList()
        val probe = Workout("probe", "", 0, 0, listOf(ExerciseLog(exercise, listOf(set))))
        val byKey = records.associateBy { it.key }
        return candidates(probe)
            .filter { it.kind != RecordKind.SESSION_VOLUME }
            .filter { pr -> byKey[pr.key]?.let { pr.value > it.value + 1e-9 } ?: (pr.kind == RecordKind.REP_MAX && pr.weightKg > 0) }
            .sortedWith(order)
    }

    private val order = compareBy<PersonalRecord>({ it.kind.ordinal }, { it.reps ?: 0 })

    private fun candidates(w: Workout): List<PersonalRecord> = buildList {
        for (ex in w.exercises) {
            val sets = ex.sets.filter(::countable)
            if (sets.isEmpty()) continue
            fun add(kind: RecordKind, value: Double, set: LoggedSet, reps: Int? = null) =
                add(PersonalRecord(ex.name, kind, reps, value, set.weightKg, set.reps, w.startMillis))

            val weighted = sets.filter { it.weightKg > 0 }
            weighted.maxByOrNull { it.weightKg }?.let { add(RecordKind.HEAVIEST, it.weightKg, it) }
            weighted.maxByOrNull { Progression.e1rm(it) }?.let { add(RecordKind.E1RM, Progression.e1rm(it).oneDecimal(), it) }
            for (n in repMaxLevels) {
                weighted.filter { it.reps >= n }.maxByOrNull { it.weightKg }?.let { add(RecordKind.REP_MAX, it.weightKg, it, n) }
            }
            weighted.maxByOrNull { it.weightKg * it.reps }?.let { add(RecordKind.SET_VOLUME, it.weightKg * it.reps, it) }
            val sessionVolume = weighted.sumOf { it.weightKg * it.reps }
            if (sessionVolume > 0) add(PersonalRecord(ex.name, RecordKind.SESSION_VOLUME, null, sessionVolume, 0.0, 0, w.startMillis))
            sets.maxByOrNull { it.reps }?.let { add(RecordKind.MOST_REPS, it.reps.toDouble(), it) }
        }
    }

    /** Per-session values for a chart, flagging the sessions that set a new best for that metric. */
    fun chart(workouts: List<Workout>, exercise: String, metric: ChartMetric, fromMillis: Long = 0): List<ChartPoint> {
        var best = Double.NEGATIVE_INFINITY
        val points = mutableListOf<ChartPoint>()
        for (w in workouts.sortedBy { it.startMillis }) {
            val sets = w.exercises.filter { it.name == exercise }.flatMap { it.sets }.filter { countable(it) && it.weightKg > 0 }
            if (sets.isEmpty()) continue
            val value = when (metric) {
                ChartMetric.E1RM -> sets.maxOf { Progression.e1rm(it) }
                ChartMetric.TOP_WEIGHT -> sets.maxOf { it.weightKg }
                ChartMetric.VOLUME -> sets.sumOf { it.weightKg * it.reps }
            }
            val record = value > best + 1e-9
            if (record) best = value
            if (w.startMillis >= fromMillis) points += ChartPoint(w.startMillis, value, record)
        }
        return points
    }

    /** Hard sets per ISO week, oldest first, for the weekly training chart. */
    fun weeklySets(workouts: List<Workout>, weeks: Int, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): List<Pair<String, Int>> {
        fun weekKey(ms: Long): Pair<Int, Int> {
            val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            return d.get(IsoFields.WEEK_BASED_YEAR) to d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        }
        val counts = workouts.groupBy { weekKey(it.startMillis) }
            .mapValues { (_, ws) -> ws.sumOf { w -> w.exercises.sumOf { ex -> ex.sets.count { it.isWorking } } } }
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (weeks - 1 downTo 0).map { back ->
            val d = today.minusWeeks(back.toLong())
            val key = d.get(IsoFields.WEEK_BASED_YEAR) to d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val monday = d.minusDays((d.dayOfWeek.value - 1).toLong())
            "${monday.dayOfMonth}/${monday.monthValue}" to (counts[key] ?: 0)
        }
    }
}
