package app.repcoach.core

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProgrammeTest {
    private val hour = 3_600_000L
    // Friday 15 January 2027, 08:00 UTC.
    private val now = Instant.parse("2027-01-15T08:00:00Z").toEpochMilli()

    private fun workout(hoursAgo: Long, title: String, vararg exercises: String) = Workout(
        id = "w$hoursAgo", title = title, startMillis = now - hoursAgo * hour, endMillis = now - hoursAgo * hour + hour,
        exercises = exercises.map { ExerciseLog(it, List(3) { LoggedSet(60.0, 8, rir = 2.0) }) },
    )

    private val older = workout(24 * 30, "Old", "Squat (Barbell)", "Bench Press (Barbell)", "Deadlift (Barbell)",
        "Seated Cable Row - V Grip (Cable)", "Lat Pulldown (Cable)", "Leg Press (Machine)", "Preacher Curl (Machine)")

    private fun planner(vararg history: Workout, at: Long = now) = Progression(history.toList(), at).let { p -> Planner(p, Programme.design(p)) }

    @Test fun designsAandBFromLiftsAlreadyDone() {
        val prog = Programme.design(Progression(listOf(older), now))
        val a = prog.day("A")!!.exercises.map { it.name }
        val b = prog.day("B")!!.exercises.map { it.name }
        assertEquals(listOf("Bench Press (Barbell)", "Squat (Barbell)", "Romanian Deadlift (Barbell)"), a.take(3))
        assertTrue("Seated Cable Row - V Grip (Cable)" in a, "picks the row they already do")
        assertTrue("Preacher Curl (Machine)" in a)
        assertEquals("Deadlift (Barbell)", b.first())
        assertEquals(setOf("Squat (Barbell)", "Bench Press (Barbell)", "Romanian Deadlift (Barbell)", "Deadlift (Barbell)", "Overhead Press (Barbell)"), prog.anchors)
        // Every major muscle is a main target on A or B, so it's trained twice a week with the halves from assisting work.
        val primaries = prog.days.flatMap { d -> d.exercises.map { Exercises.info(it.name).primary } }.toSet()
        assertTrue(primaries.containsAll(listOf(Muscle.QUADS, Muscle.CHEST, Muscle.BACK, Muscle.HAMSTRINGS, Muscle.SHOULDERS, Muscle.BICEPS, Muscle.TRICEPS)))
    }

    @Test fun newBlockKeepsMainLiftsAndRotatesKnownAccessories() {
        val p = Progression(listOf(older, workout(24 * 20, "Old 2", "Hammer Curl (Dumbbell)", "Chest Supported Incline Row (Dumbbell)")), now)
        val first = Programme.design(p)
        val next = Programme.design(p, first)
        assertEquals(first.anchors, next.anchors)
        assertEquals(2, next.block)
        val curl = { prog: Programme -> prog.day("A")!!.exercises.first { Exercises.info(it.name).primary == Muscle.BICEPS }.name }
        assertNotEquals(curl(first), curl(next))
    }

    @Test fun weekRunsAthenBthenMixedSessions() {
        assertTrue(planner(older).plan(CheckIn(7, 75)).title.startsWith("Full Body A"))
        // A done on Tuesday: B is due and its muscles have recovered.
        val a = workout(72, "Full Body A: Bench and squat", "Squat (Barbell)", "Bench Press (Barbell)", "Romanian Deadlift (Barbell)")
        val b = planner(older, a).plan(CheckIn(7, 75))
        assertTrue(b.title.startsWith("Full Body B"), b.title)
        assertEquals(3, b.exercises.first { it.name == "Deadlift (Barbell)" }.sets)
        // Logged in Hevy from the sent routine, the title still counts.
        val bDone = workout(50, "Rep Coach: Full Body B: Deadlift and overhead press", "Deadlift (Barbell)", "Overhead Press (Barbell)")
        val mixed = planner(older, a, bDone).plan(CheckIn(7, 60))
        assertEquals("Mixed session", mixed.title)
        val prog = Programme.design(Progression(listOf(older), now))
        assertTrue(mixed.exercises.none { it.name in prog.allNames }, mixed.exercises.map { it.name }.toString())
    }

    @Test fun dueDayWaitsForItsMainMusclesToRecover() {
        val a = workout(100, "Full Body A: Bench and squat", "Squat (Barbell)", "Bench Press (Barbell)")
        val rows = workout(20, "Extra", "Lat Pulldown (Cable)", "Seated Cable Row - V Grip (Cable)")
        val plan = planner(older, a, rows).plan(CheckIn(7, 60))
        assertEquals("Mixed session", plan.title)
        assertTrue("Full Body B" in plan.summary && "is due" in plan.summary, plan.summary)
    }

    @Test fun mainLiftsStayWhenTimeIsShort() {
        val plan = planner(older).plan(CheckIn(7, 30))
        assertTrue(plan.exercises.map { it.name }.containsAll(listOf("Squat (Barbell)", "Bench Press (Barbell)", "Romanian Deadlift (Barbell)")))
        assertTrue(plan.exercises.size < 6)
    }

    @Test fun titlesNameTheirDay() {
        assertEquals("A", Planner.dayKey("Full Body A: Bench and squat"))
        assertEquals("B", Planner.dayKey("Rep Coach: Full Body B: Deadlift and overhead press"))
        assertEquals(null, Planner.dayKey("Full body A"))
    }

    @Test fun realExportAandBFitSeventyFiveMinutes() {
        val path = System.getenv("HEVY_CSV") ?: return
        val workouts = HevyCsv.parse(File(path).readText())
        // A fresh week, with everything recovered.
        val monday = Instant.parse("2026-11-02T08:00:00Z").toEpochMilli()
        val p = Progression(workouts, monday)
        val prog = Programme.design(p)
        for (day in prog.days) {
            val history = workouts + if (day.key == "B") listOf(workouts.last().copy(id = "a", title = "Full Body A", startMillis = monday - 100 * hour, endMillis = monday - 99 * hour, exercises = emptyList())) else emptyList()
            val plan = Planner(Progression(history, monday), prog).plan(CheckIn(7, Programme.MINUTES))
            println("${plan.title} (${plan.estimatedMinutes} min): ${plan.summary}")
            plan.exercises.forEach { println("  ${it.name}: ${it.sets} x ${it.repsMin}-${it.repsMax} @ ${it.weightKg} kg, RIR ${it.targetRir}, rest ${it.restSeconds}s | ${it.reason}") }
            assertEquals(day.exercises.size, plan.exercises.size, "the whole day fits in ${Programme.MINUTES} minutes")
            assertTrue(plan.estimatedMinutes!! <= Programme.MINUTES)
        }
    }

    @Test fun olderSavedProgrammeMovesBenchFirstAndPlanFollows() {
        val old = Programme(listOf(
            ProgrammeDay("A", "Full Body A: Squat and bench", listOf(
                ProgrammeExercise("Squat (Barbell)", 4, true), ProgrammeExercise("Bench Press (Barbell)", 4, true),
                ProgrammeExercise("Romanian Deadlift (Barbell)", 3, true))),
            ProgrammeDay("B", "Full Body B: Deadlift and overhead press", listOf(ProgrammeExercise("Deadlift (Barbell)", 3, true))),
        ), now - 24 * hour)
        val up = old.upgraded()
        assertEquals(listOf("Bench Press (Barbell)", "Squat (Barbell)", "Romanian Deadlift (Barbell)"), up.day("A")!!.exercises.map { it.name })
        assertEquals("Full Body A: Bench and squat", up.day("A")!!.title)
        assertEquals(old.anchors, up.anchors)
        assertEquals(up, up.upgraded())
        val plan = Planner(Progression(listOf(older), now), up).plan(CheckIn(energy = 7, minutes = 75), null)
        assertEquals("Bench Press (Barbell)", plan.exercises.first().name)
    }
}
