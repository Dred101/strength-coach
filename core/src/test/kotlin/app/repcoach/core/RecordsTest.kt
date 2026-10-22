package app.repcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordsTest {
    private fun workout(id: String, day: Long, vararg sets: LoggedSet, name: String = "Bench Press (Barbell)") =
        Workout(id, "W", day * 86_400_000, day * 86_400_000 + 3_600_000, listOf(ExerciseLog(name, sets.toList())))

    private val history = listOf(
        workout("a", 1, LoggedSet(40.0, 10, SetType.WARMUP), LoggedSet(80.0, 5), LoggedSet(80.0, 5)),
        workout("b", 3, LoggedSet(85.0, 3), LoggedSet(70.0, 10), LoggedSet(50.0, 12, SetType.DROPSET)),
    )

    @Test fun findsRecordsAtEachLevel() {
        val r = Records.all(history).getValue("Bench Press (Barbell)").associateBy { it.title }
        assertEquals(85.0, r.getValue("Heaviest weight").value)
        assertEquals(85.0, r.getValue("1RM").value)
        assertEquals(85.0, r.getValue("3RM").value)
        assertEquals(80.0, r.getValue("5RM").value)
        assertEquals(70.0, r.getValue("10RM").value)
        // The 12-rep drop set doesn't count.
        assertTrue("12RM" !in r)
        assertEquals(10.0, r.getValue("Most reps in a set").value)
    }

    @Test fun sessionVolumeAndE1rm() {
        val r = Records.all(history).getValue("Bench Press (Barbell)").associateBy { it.title }
        assertEquals(955.0, r.getValue("Most volume in a session").value)
        assertEquals(93.5, r.getValue("Best estimated 1RM").value) // 85 x 3 -> 93.5 beats 70 x 10 -> 93.3
    }

    @Test fun detectsNewRecordsInAWorkout() {
        val next = workout("c", 5, LoggedSet(82.5, 5), LoggedSet(60.0, 15))
        val titles = Records.newIn(history + next, next).map { it.title }
        assertTrue("5RM" in titles)
        assertTrue("Most reps in a set" in titles)
        assertTrue("12RM" in titles)
        assertTrue("Heaviest weight" !in titles)
    }

    @Test fun firstSessionSetsNoRecords() {
        val first = workout("x", 1, LoggedSet(20.0, 10), name = "Face Pull")
        assertEquals(emptyList(), Records.newIn(history + first, first))
    }

    @Test fun flagsABrokenRecordForOneSet() {
        val current = Records.all(history).getValue("Bench Press (Barbell)")
        val broken = Records.brokenBy(LoggedSet(87.5, 2), "Bench Press (Barbell)", current).map { it.title }
        assertTrue("Heaviest weight" in broken)
        assertTrue("1RM" in broken)
        assertTrue("3RM" !in broken)
    }

    @Test fun chartMarksRecordSessions() {
        val points = Records.chart(history, "Bench Press (Barbell)", ChartMetric.TOP_WEIGHT)
        assertEquals(listOf(80.0, 85.0), points.map { it.value })
        assertTrue(points.all { it.isRecord })
    }

    @Test fun realExportRecords() {
        val path = System.getenv("HEVY_CSV") ?: return
        val workouts = HevyCsv.parse(File(path).readText())
        val all = Records.all(workouts)
        all["Bench Press (Barbell)"]?.forEach { println("${it.title}: ${it.detail}") }
        println(Records.weeklySets(workouts, 8))
        assertTrue(all.isNotEmpty())
    }
}
