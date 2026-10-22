package app.repcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaceTest {
    private fun session(i: Int, minutes: Int) = Workout(
        "w$i", "Full body", i * 86_400_000L, i * 86_400_000L + minutes * 60_000L,
        listOf(ExerciseLog("Bench Press (Barbell)", List(4) { LoggedSet(80.0, 6) }), ExerciseLog("Hammer Curl (Dumbbell)", List(3) { LoggedSet(15.0, 12) })),
    )

    @Test fun totalsWorkingVolume() {
        val w = session(1, 40).let { it.copy(exercises = it.exercises + ExerciseLog("Squat (Barbell)", listOf(LoggedSet(60.0, 5, SetType.WARMUP)))) }
        assertEquals(4 * 480.0 + 3 * 180.0, w.volumeKg())
    }

    @Test fun learnsSlowerPaceFromHistory() {
        // Standard estimate for each session: 300 + 4×(40+150)+60 + 3×(40+90)+60 = 1570 s.
        val pace = Pace(List(5) { session(it, 35) })
        assertTrue(pace.learned)
        assertEquals(35 * 60 / 1570.0, pace.factor, 0.01)
        assertTrue(pace.describe().contains("longer"))
    }

    @Test fun ignoresSessionsThatCantBeTimed() {
        val pace = Pace(List(5) { session(it, 3) })
        assertEquals(1.0, pace.factor)
    }

    @Test fun slowerPaceMeansShorterPlans() {
        val history = List(6) { session(it, 60) }
        val fast = Planner(Progression(List(6) { session(it, 20) })).plan(CheckIn(7, 45))
        val slow = Planner(Progression(history)).plan(CheckIn(7, 45))
        assertTrue(slow.exercises.sumOf { it.sets } <= fast.exercises.sumOf { it.sets })
    }

    /** Runs against a real export when HEVY_CSV points at one; skipped otherwise. */
    @Test fun realExportPace() {
        val path = System.getenv("HEVY_CSV") ?: return
        val workouts = HevyCsv.parse(File(path).readText())
        val pace = Pace(workouts)
        println("Pace factor ${pace.factor} from ${pace.ratios.size} sessions: ${pace.describe()}")
        println(Planner(Progression(workouts)).plan(CheckIn(7, 45)).summary)
    }
}
