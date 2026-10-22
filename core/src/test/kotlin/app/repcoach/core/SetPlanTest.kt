package app.repcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetPlanTest {
    private val bench = PlannedExercise("Bench Press (Barbell)", sets = 3, repsMin = 5, repsMax = 8, weightKg = 80.0, targetRir = 2, restSeconds = 150, targetReps = 6)

    @Test fun laysOutEverySetUpFront() {
        val plan = SetPlan.remaining(bench, emptyList())
        assertEquals(List(3) { 80.0 to 6 }, plan.map { it.weightKg to it.reps })
        assertTrue(plan.all { it.note.isNotEmpty() })
        assertEquals(listOf(40.0, 60.0), SetPlan.warmups(bench).map { it.weightKg })
    }

    @Test fun warmupsDontCountAsWorkingSets() {
        val plan = SetPlan.remaining(bench, listOf(LoggedSet(40.0, 8, SetType.WARMUP)))
        assertEquals(3, plan.size)
    }

    @Test fun easySetStepsUp() {
        val plan = SetPlan.remaining(bench, listOf(LoggedSet(80.0, 7, SetType.NORMAL, rir = 4.0)))
        assertEquals(2, plan.size)
        assertEquals(82.5, plan[0].weightKg)
        assertEquals(6, plan[0].reps)
        assertTrue(plan[0].note.startsWith("Last set felt easy"))
        assertTrue(plan[1].note.startsWith("Last set:"))
    }

    @Test fun onTargetHoldsWeightAndReps() {
        val plan = SetPlan.remaining(bench, listOf(LoggedSet(80.0, 6, SetType.NORMAL, rir = 2.0)))
        assertEquals(80.0 to 6, plan[0].weightKg to plan[0].reps)
        assertEquals("On target: same again", plan[0].note)
    }

    @Test fun grindDropsLoad() {
        val plan = SetPlan.remaining(bench, listOf(LoggedSet(80.0, 4, SetType.NORMAL, rir = 0.0)))
        assertEquals(72.5, plan[0].weightKg)
        assertEquals(5, plan[0].reps)
    }

    @Test fun nothingLeftOnceAllSetsLogged() {
        val sets = List(3) { LoggedSet(80.0, 6, SetType.NORMAL, rir = 2.0) }
        assertTrue(SetPlan.remaining(bench, sets).isEmpty())
    }

    @Test fun movesOnToTheNextUnfinishedExercise() {
        val row = bench.copy(name = "Seated Cable Row")
        val curl = bench.copy(name = "Hammer Curl (Dumbbell)")
        val done = List(3) { LoggedSet(80.0, 6) }
        assertEquals("Hammer Curl (Dumbbell)", SetPlan.nextUnfinished(listOf(bench, row, curl), mapOf("Bench Press (Barbell)" to done, "Seated Cable Row" to done), "Seated Cable Row")?.name)
        assertEquals("Seated Cable Row", SetPlan.nextUnfinished(listOf(bench, row, curl), mapOf("Hammer Curl (Dumbbell)" to done, "Bench Press (Barbell)" to done), "Hammer Curl (Dumbbell)")?.name)
        assertEquals(null, SetPlan.nextUnfinished(listOf(bench), mapOf("Bench Press (Barbell)" to done), "Bench Press (Barbell)"))
    }

    @Test fun isolationStopsOneRepShort() {
        assertEquals(1, Exercises.info("Lateral Raise (Dumbbell)").targetRir)
        assertEquals(2, Exercises.info("Squat (Barbell)").targetRir)
    }

    @Test fun exportsStrongCsvForHevy() {
        val w = Workout("x", "Full body A", 1_760_000_000_000, 1_760_003_600_000,
            listOf(ExerciseLog("Bench Press (Barbell)", listOf(LoggedSet(80.0, 6, SetType.NORMAL, rir = 2.0), LoggedSet(80.0, 5, SetType.NORMAL)), "felt \"good\"")))
        val lines = StrongCsv.export(listOf(w)).trim().lines()
        assertEquals("\"Workout #\";\"Date\";\"Workout Name\";\"Duration (sec)\";\"Exercise Name\";\"Set Order\";\"Weight (kg)\";\"Reps\";\"RPE\";\"Distance (meters)\";\"Seconds\";\"Notes\";\"Workout Notes\"", lines[0])
        assertEquals("\"1\";\"2025-10-09 08:53:20\";\"Full body A\";\"3600\";\"Bench Press (Barbell)\";\"1\";\"80.0\";\"6\";\"8.0\";\"\";\"\";\"felt \"\"good\"\"\";\"\"", lines[1])
        assertEquals(3, lines.size)
    }
}
