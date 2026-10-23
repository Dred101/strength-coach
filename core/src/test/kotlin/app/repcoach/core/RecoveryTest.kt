package app.repcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RecoveryTest {
    private val hour = 3_600_000L
    private val now = 1_800_000_000_000L

    private fun workout(hoursAgo: Long, title: String, vararg exercises: String) = Workout(
        id = "w$hoursAgo", title = title, startMillis = now - hoursAgo * hour, endMillis = now - hoursAgo * hour + hour,
        exercises = exercises.map { ExerciseLog(it, List(3) { LoggedSet(60.0, 8, rir = 2.0) }) },
    )

    private val fullBodyA = arrayOf("Squat (Barbell)", "Bench Press (Barbell)", "Seated Cable Row", "Leg Curl (Machine)",
        "Lateral Raise (Dumbbell)", "Triceps Rope Pushdown", "Hammer Curl (Dumbbell)")

    @Test fun musclesTrainedHardInTheLast48HoursAreRecovering() {
        val r = MuscleRecovery(listOf(workout(20, "Full body A", "Bench Press (Barbell)")), now)
        assertTrue(Muscle.CHEST in r.recovering())
        // Triceps only assisted: 1.5 sets isn't enough to need a recovery gap.
        assertFalse(Muscle.TRICEPS in r.recovering())
        assertEquals(29, r.status.getValue(Muscle.CHEST).hoursLeft)
        assertTrue(MuscleRecovery(listOf(workout(50, "Full body A", "Bench Press (Barbell)")), now).recovering().isEmpty())
    }

    @Test fun dayAfterAFullBodySessionIsARestDay() {
        val history = listOf(workout(100, "Full body C", *fullBodyA), workout(20, "Full body A", *fullBodyA))
        val plan = Planner(Progression(history, now)).plan(CheckIn(7, 60))
        assertEquals("Recovery day", plan.title)
        assertTrue("resting today" in plan.summary, plan.summary)
        assertTrue(plan.exercises.all { it.sets <= 2 }, plan.exercises.toString())
        assertTrue(plan.exercises.all { it.weightKg == null || it.weightKg!! <= 60.0 }, "no weight increases")
    }

    @Test fun partlyTiredDaysTrainOnlyRecoveredMuscles() {
        val history = listOf(workout(200, "Full body C", *fullBodyA), workout(20, "Push", "Bench Press (Barbell)", "Triceps Rope Pushdown"))
        val plan = Planner(Progression(history, now)).plan(CheckIn(7, 60))
        val primaries = plan.exercises.map { Exercises.info(it.name).primary }
        assertFalse(Muscle.CHEST in primaries, plan.exercises.toString())
        assertFalse(Muscle.TRICEPS in primaries, plan.exercises.toString())
        assertTrue("chest" in plan.summary && "recovered" in plan.summary, plan.summary)
        assertTrue(plan.exercises.all { listOf("recovered", "fresh", "ready").any { w -> w in it.reason } }, plan.exercises.map { it.reason }.toString())
    }

    @Test fun fullBodyDaysRotateAfterTheLastOneDone() {
        val history = listOf(workout(72, "Full body A", *fullBodyA))
        val plan = Planner(Progression(history, now)).plan(CheckIn(7, 60))
        assertEquals("Full body B", plan.title)
        val again = Planner(Progression(history + workout(-1, "Full body B", *fullBodyA), now + 80 * hour)).plan(CheckIn(7, 60))
        assertEquals("Full body C", again.title)
    }

    @Test fun whyCarriesRulesFactsAndResearch() {
        val history = listOf(workout(30, "Full body A", *fullBodyA))
        val p = Progression(history, now)
        val bench = p.target("Bench Press (Barbell)").toPlanned()
        val topic = Why.forExercise(bench, p)
        assertTrue(topic.facts.any { "Heavy 5–8" in it })
        assertTrue(topic.facts.any { "chest:" in it && "ready in about" in it }, topic.facts.toString())
        assertTrue(topic.evidence.any { "Morán-Navarro" in it.source })
        val set = Why.forSet(bench, 2, SetSuggestion(62.5, 7, "Last set felt easy: up 2.5 kg"), listOf(LoggedSet(60.0, 8, rir = 4.0)), p)
        assertTrue(set.asText().contains("felt easy") && set.asText().contains("Helms et al. 2016"))
        val plan = Why.forPlan(Planner(p).plan(CheckIn(7, 60)), p)
        assertTrue(plan.facts.any { it.startsWith("Recovery by muscle") })
    }

    @Test fun realExportNeverRepeatsYesterdaysMuscles() {
        val path = System.getenv("HEVY_CSV") ?: return
        val workouts = HevyCsv.parse(File(path).readText())
        val last = workouts.last()
        val p = Progression(workouts, last.endMillis + 20 * hour)
        val plan = Planner(p).plan(CheckIn(7, 60))
        val tired = MuscleRecovery(workouts, p.now).recovering()
        println("Next day after ${last.title}: ${plan.title}. ${plan.summary}")
        plan.exercises.forEach { println("  ${it.name} ${it.sets}x${it.repsMin}-${it.repsMax} @ ${it.weightKg}: ${it.reason}") }
        assertNotEquals(last.exercises.map { it.name }.toSet(), plan.exercises.map { it.name }.toSet())
        assertTrue(plan.exercises.filter { Exercises.info(it.name).primary in tired }.all { it.sets <= 2 })
    }
}
