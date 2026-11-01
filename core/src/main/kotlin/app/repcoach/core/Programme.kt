package app.repcoach.core

import kotlinx.serialization.Serializable

/**
 * The lifter's two fixed full-body days, done once each per week, built mostly on compound lifts.
 * The big lifts stay the same for a whole block so progressive overload is easy to track; any extra
 * gym days in between rotate exercises freely. Fixed and varied selection gave similar strength and
 * size gains when progression was tracked, variety helped motivation, and too much rotation can
 * blunt progress (Baz-Valle et al. 2019; Kassiano et al. 2022). See docs/RESEARCH.md.
 */
@Serializable
data class Programme(
    val days: List<ProgrammeDay>,
    /** When this block started; the anchors stay fixed for [BLOCK_WEEKS] weeks. */
    val startMillis: Long,
    /** Counts blocks, so a new block can rotate the accessories. */
    val block: Int = 1,
) {
    /** Compound lifts kept for the whole block. */
    val anchors: Set<String> get() = days.flatMap { d -> d.exercises.filter { it.anchor }.map { it.name } }.toSet()

    val allNames: Set<String> get() = days.flatMap { d -> d.exercises.map { it.name } }.toSet()

    fun day(key: String): ProgrammeDay? = days.firstOrNull { it.key == key }

    /** Weeks since the block started, counting from 1. */
    fun week(now: Long): Int = ((now - startMillis) / WEEK).toInt() + 1

    companion object {
        const val BLOCK_WEEKS = 8
        const val MINUTES = 75
        private const val WEEK = 7 * 24 * 3_600_000L

        /** Each slot lists the lifts that fit it, in order of preference. */
        private class Slot(val sets: Int, val anchor: Boolean, val options: List<String>)

        private val templates = listOf(
            Triple("A", "Squat and bench", listOf(
                Slot(4, true, listOf("Squat (Barbell)", "Leg Press (Machine)")),
                Slot(4, true, listOf("Bench Press (Barbell)", "Bench Press (Dumbbell)")),
                Slot(3, true, listOf("Romanian Deadlift (Barbell)")),
                Slot(3, false, listOf("Chest Supported Incline Row (Dumbbell)", "Seated Cable Row - V Grip (Cable)", "Single Arm Cable Row", "Seated Cable Row", "Dumbbell Row")),
                Slot(3, false, listOf("Single Arm Lateral Raise (Cable)", "Lateral Raise (Dumbbell)")),
                Slot(3, false, listOf("Hammer Curl (Dumbbell)", "Preacher Curl (Machine)", "Seated Incline Curl (Dumbbell)")),
            )),
            Triple("B", "Deadlift and overhead press", listOf(
                Slot(3, true, listOf("Deadlift (Barbell)")),
                Slot(3, true, listOf("Overhead Press (Barbell)", "Shoulder Press (Dumbbell)")),
                Slot(3, false, listOf("Lat Pulldown (Cable)", "Reverse Grip Lat Pulldown (Cable)", "Lat Pulldown (Machine)", "Pull Up")),
                Slot(3, false, listOf("Leg Press (Machine)", "Leg Press Horizontal (Machine)", "Bulgarian Split Squat")),
                Slot(3, false, listOf("Incline Bench Press (Smith Machine)", "Incline Bench Press (Dumbbell)")),
                Slot(3, false, listOf("Seated Leg Curl (Machine)", "Lying Leg Curl (Machine)", "Leg Curl (Machine)")),
                Slot(3, false, listOf("Triceps Pushdown", "Triceps Rope Pushdown", "Overhead Triceps Extension (Cable)")),
            )),
        )

        /**
         * Builds A and B from the lifts this lifter already does: in each slot, the first option they
         * have history with, otherwise the first option. A new block keeps the anchors and moves each
         * accessory on to the next option they know, for fresh angles without losing the big lifts.
         */
        fun design(p: Progression, previous: Programme? = null): Programme {
            val known = p.exerciseNames().toSet()
            val block = (previous?.block ?: 0) + 1
            val days = templates.map { (key, focus, slots) ->
                val used = mutableSetOf<String>()
                val exercises = slots.mapNotNull { slot ->
                    val familiar = slot.options.filter { it in known && it !in used }
                    val prior = previous?.day(key)?.exercises?.firstOrNull { it.name in slot.options }?.name
                    val name = when {
                        slot.anchor && prior != null -> prior
                        !slot.anchor && prior != null && familiar.size > 1 -> familiar[(familiar.indexOf(prior) + 1).mod(familiar.size)]
                        else -> familiar.firstOrNull() ?: slot.options.firstOrNull { it !in used }
                    } ?: return@mapNotNull null
                    used += name
                    ProgrammeExercise(name, slot.sets, slot.anchor)
                }
                ProgrammeDay(key, "Full Body $key: $focus", exercises)
            }
            return Programme(days, p.now, block)
        }
    }
}

@Serializable
data class ProgrammeDay(val key: String, val title: String, val exercises: List<ProgrammeExercise>)

@Serializable
data class ProgrammeExercise(val name: String, val sets: Int, val anchor: Boolean)
