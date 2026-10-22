package app.repcoach.core

import kotlin.math.roundToInt

/** Total weight moved in a workout's working sets (weight × reps), warm-ups left out. */
fun Workout.volumeKg(): Double = exercises.sumOf { ex -> ex.sets.filter { it.isWorking }.sumOf { it.weightKg * it.reps } }

/**
 * Learns how quickly this lifter actually gets through a session. The planner's textbook estimate
 * (about 40 s per set plus its rest, a minute per exercise to set up, five minutes to warm up) is
 * compared with the real duration of past sessions, and the typical ratio scales future plans so a
 * "45 minutes" check-in produces a session that really fits in 45 minutes.
 */
class Pace(workouts: List<Workout>) {
    /** Actual minutes ÷ estimated minutes for each usable past session, newest last. */
    val ratios: List<Double> = workouts
        .filter { w ->
            val minutes = (w.endMillis - w.startMillis) / 60_000.0
            minutes in 10.0..180.0 && w.exercises.sumOf { ex -> ex.sets.count { it.isWorking } } >= 4
        }
        .takeLast(SESSIONS)
        .map { w -> (w.endMillis - w.startMillis) / 1000.0 / estimateSeconds(w) }

    /** How much slower (above 1) or faster (below 1) than the textbook estimate the lifter trains. */
    val factor: Double = if (ratios.size < 3) 1.0 else ratios.sorted()[ratios.size / 2].coerceIn(0.6, 1.8)

    val learned: Boolean get() = ratios.size >= 3

    /** Seconds one exercise will take at this lifter's pace. */
    fun exerciseSeconds(sets: Int, restSeconds: Int): Int = ((sets * (SET_SECONDS + restSeconds) + SETUP_SECONDS) * factor).roundToInt()

    /** Seconds available for exercises once the general warm-up is taken out. */
    fun budgetSeconds(minutes: Int): Int = ((minutes - WARMUP_MINUTES).coerceAtLeast(15) * 60)

    fun planMinutes(exercises: List<PlannedExercise>): Int =
        WARMUP_MINUTES + exercises.sumOf { exerciseSeconds(it.sets, it.restSeconds) } / 60

    /** One line on the lifter's pace, for the plan summary and Claude's briefing. */
    fun describe(): String = when {
        !learned -> "Not enough timed sessions yet to learn your pace, so timings use standard estimates."
        factor > 1.1 -> "Your sessions run about ${((factor - 1) * 100).roundToInt()}% longer than standard estimates, so plans allow for that."
        factor < 0.9 -> "You train about ${((1 - factor) * 100).roundToInt()}% faster than standard estimates, so plans fit in more."
        else -> "Your pace matches standard estimates."
    }

    private fun estimateSeconds(w: Workout): Double = WARMUP_MINUTES * 60.0 + w.exercises.sumOf { ex ->
        val working = ex.sets.count { it.isWorking }
        if (working == 0) 0 else working * (SET_SECONDS + Exercises.info(ex.name).restSeconds) + SETUP_SECONDS
    }

    companion object {
        const val SET_SECONDS = 40
        const val SETUP_SECONDS = 60
        const val WARMUP_MINUTES = 5
        /** Only recent sessions count, so the pace follows the lifter as habits change. */
        const val SESSIONS = 20
    }
}
