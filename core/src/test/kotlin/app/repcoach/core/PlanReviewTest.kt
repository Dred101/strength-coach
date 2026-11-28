package app.repcoach.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanReviewTest {
    private val hour = 3_600_000L
    private val t0 = 1_790_000_000_000L

    private fun planned(name: String, kg: Double, sets: Int = 3, min: Int = 5, max: Int = 8) =
        PlannedExercise(name, sets, min, max, kg, 2, 150)

    private val plan = SessionPlan("Full Body A: Bench and squat", "", listOf(
        planned("Bench Press (Barbell)", 80.0),
        planned("Squat (Barbell)", 100.0),
        planned("Hammer Curl (Dumbbell)", 16.0, min = 10, max = 15),
    ), "rules")

    private fun saved(id: String, at: Long, p: SessionPlan = plan) = SavedPlan(id, at, 7, 75, p)

    private fun workout(id: String, at: Long, title: String, vararg ex: Pair<String, List<LoggedSet>>) =
        Workout(id, title, at, at + hour, ex.map { ExerciseLog(it.first, it.second) }, source = "hevy")

    @Test fun matchesTheWorkoutLoggedAfterThePlan() {
        val before = workout("old", t0 - 24 * hour, "Full Body A: Bench and squat", "Bench Press (Barbell)" to listOf(LoggedSet(77.5, 8)))
        val after = workout("new", t0 + 3 * hour, "Morning workout",
            "Bench Press (Barbell)" to List(3) { LoggedSet(80.0, 8) },
            "Squat (Barbell)" to List(3) { LoggedSet(100.0, 6) })
        val match = PlanReview.match(listOf(saved("p", t0)), listOf(before, after))
        assertEquals("new", match["p"]?.id)
    }

    @Test fun ignoresUnrelatedOrLateWorkouts() {
        val other = workout("x", t0 + 2 * hour, "Legs", "Leg Press (Machine)" to listOf(LoggedSet(150.0, 10)))
        val late = workout("y", t0 + 5 * 24 * hour, "Full Body A: Bench and squat", "Bench Press (Barbell)" to listOf(LoggedSet(80.0, 8)))
        assertNull(PlanReview.match(listOf(saved("p", t0)), listOf(other, late))["p"])
    }

    @Test fun eachWorkoutGoesToTheLatestPlanBeforeIt() {
        val w = workout("w", t0 + 30 * hour, "Rep Coach: Full Body A: Bench and squat", "Bench Press (Barbell)" to listOf(LoggedSet(80.0, 8)))
        val match = PlanReview.match(listOf(saved("first", t0), saved("second", t0 + 24 * hour)), listOf(w))
        assertEquals("w", match["second"]?.id)
        assertNull(match["first"])
    }

    @Test fun comparesEachExerciseWithThePlan() {
        val w = workout("w", t0 + hour, "Full Body A",
            "Bench Press (Barbell)" to listOf(LoggedSet(60.0, 10, SetType.WARMUP)) + List(3) { LoggedSet(80.0, 8) },
            "Squat (Barbell)" to listOf(LoggedSet(95.0, 6), LoggedSet(95.0, 4)),
            "Lateral Raise (Dumbbell)" to listOf(LoggedSet(10.0, 15)))
        val c = PlanReview.compare(plan, w).associateBy { it.name }
        assertTrue("goes up next time" in c.getValue("Bench Press (Barbell)").verdict, c.toString())
        assertEquals("80×8, 80×8, 80×8", c.getValue("Bench Press (Barbell)").doneText)
        val squat = c.getValue("Squat (Barbell)").verdict
        assertTrue("2 of 3 sets" in squat && "lighter than planned (95 kg)" in squat && "below the rep range on 1 set" in squat, squat)
        assertEquals("Skipped", c.getValue("Hammer Curl (Dumbbell)").verdict)
        assertTrue(c.getValue("Lateral Raise (Dumbbell)").verdict.startsWith("Added"))
        val text = PlanReview.asText(saved("p", t0), w)
        assertTrue("How it went: logged in Hevy" in text && "Skipped" in text, text)
    }
}
