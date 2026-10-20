package app.repcoach.core

import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoreTest {
    private val sample = """
        "title","start_time","end_time","description","exercise_title","superset_id","exercise_notes","set_index","set_type","weight_kg","reps","distance_km","duration_seconds","rpe"
        "Full body","1 Oct 2026, 10:00","1 Oct 2026, 11:00","","Bench Press (Barbell)",,"",0,"warmup",40,10,,,
        "Full body","1 Oct 2026, 10:00","1 Oct 2026, 11:00","","Bench Press (Barbell)",,"felt ""heavy"" today",1,"normal",70,8,,,8
        "Full body","1 Oct 2026, 10:00","1 Oct 2026, 11:00","","Bench Press (Barbell)",,"",2,"normal",70,8,,,9
        "Full body","1 Oct 2026, 10:00","1 Oct 2026, 11:00","","Leg Extension (Machine)",,"",0,"normal",50,12,,,
        "Pull","3 Oct 2026, 09:00","3 Oct 2026, 10:00","","Lat Pulldown (Cable)",,"",0,"dropset",45,10,,,
    """.trimIndent()

    @Test fun parsesHevyExport() {
        val workouts = HevyCsv.parse(sample, ZoneOffset.UTC)
        assertEquals(2, workouts.size)
        val bench = workouts[0].exercises[0]
        assertEquals(3, bench.sets.size)
        assertEquals(SetType.WARMUP, bench.sets[0].type)
        assertEquals(2.0, bench.sets[1].rir)
        assertEquals("felt \"heavy\" today", bench.notes)
        assertEquals(SetType.DROPSET, workouts[1].exercises[0].sets[0].type)
    }

    @Test fun progressesWhenTopOfRangeHit() {
        val workouts = HevyCsv.parse(sample, ZoneOffset.UTC)
        val t = Progression(workouts).target("Bench Press (Barbell)", energy = 7)
        assertEquals(72.5, t.weightKg)
        assertEquals(5, t.repsMin)
    }

    @Test fun holdsWeightOnLowEnergy() {
        val workouts = HevyCsv.parse(sample, ZoneOffset.UTC)
        val t = Progression(workouts).target("Bench Press (Barbell)", energy = 3)
        assertEquals(70.0, t.weightKg)
        assertEquals(2, t.sets)
    }

    @Test fun classifiesExercises() {
        assertEquals(Muscle.CHEST, Exercises.info("Incline Bench Press (Smith Machine)").primary)
        assertEquals(Kind.COMPOUND, Exercises.info("Squat (Barbell)").kind)
        assertEquals(Muscle.REAR_DELTS, Exercises.info("Face Pull").primary)
        assertEquals(Muscle.TRICEPS, Exercises.info("Triceps Rope Pushdown").primary)
    }

    /** Runs against a real export when HEVY_CSV points at one; skipped otherwise. */
    @Test fun realExportPlans() {
        val path = System.getenv("HEVY_CSV") ?: return
        val workouts = HevyCsv.parse(File(path).readText())
        assertTrue(workouts.size > 10)
        val p = Progression(workouts)
        val plan = Planner(p).plan(CheckIn(energy = 7, minutes = 60))
        println("${workouts.size} workouts, ${p.exerciseNames().size} exercises")
        println("Plateaus: " + p.plateaus().joinToString { it.exercise })
        println(plan.title + ": " + plan.summary)
        plan.exercises.forEach { println("  ${it.name} ${it.sets}x${it.repsMin}-${it.repsMax} @ ${it.weightKg} (${it.reason})") }
        println(CoachContext.build(p, CheckIn(7, 60), null, plan, "").take(3000))
        assertTrue(plan.exercises.size >= 3)
    }
}
