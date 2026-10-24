package app.repcoach.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HevyApiTest {
    private val workoutJson = """
        {"id":"b459cba5","title":"Push","routine_id":"r1","description":"","start_time":"2026-10-23T17:00:00+00:00",
         "end_time":"2026-10-23T18:05:00+00:00","updated_at":"2026-10-23T18:06:00Z","created_at":"2026-10-23T18:06:00Z",
         "exercises":[
           {"index":0,"title":"Bench Press (Barbell)","notes":"good","exercise_template_id":"79D0BB3A","supersets_id":null,
            "sets":[{"index":0,"type":"warmup","weight_kg":40,"reps":8,"rpe":null},
                    {"index":1,"type":"normal","weight_kg":70,"reps":8,"rpe":8.5},
                    {"index":2,"type":"normal","weight_kg":70,"reps":7,"rpe":null}]},
           {"index":1,"title":"Plank","exercise_template_id":"C6C9B8A0","sets":[{"index":0,"type":"normal","duration_seconds":60}]}
         ]}
    """.trimIndent()

    /** Fake Hevy that answers by path and records what was sent. */
    private class FakeHevy(val routes: Map<String, Pair<Int, String>>) : HevyApi.Http {
        val sent = mutableListOf<Triple<String, String, String?>>()
        override fun send(method: String, url: String, apiKey: String, body: String?): Pair<Int, String> {
            sent += Triple(method, url, body)
            val path = url.removePrefix(HevyApi.BASE).substringBefore('?')
            val page = Regex("page=(\\d+)").find(url)?.groupValues?.get(1) ?: "1"
            return routes["$method $path $page"] ?: routes["$method $path"] ?: (404 to "{}")
        }
    }

    @Test fun readsWorkoutsAndDeletionsFromEvents() {
        val fake = FakeHevy(mapOf(
            "GET /v1/workouts/events 1" to (200 to """{"page":1,"page_count":2,"events":[{"type":"updated","workout":$workoutJson}]}"""),
            "GET /v1/workouts/events 2" to (200 to """{"page":2,"page_count":2,"events":[{"type":"deleted","id":"old1","deleted_at":"2026-10-22T10:00:00Z"}]}"""),
        ))
        val changes = HevyApi("key", fake).changesSince(Instant.EPOCH)
        val w = changes.updated.single()
        assertEquals("hevyapi-b459cba5", w.id)
        assertEquals(Instant.parse("2026-10-23T17:00:00Z").toEpochMilli(), w.startMillis)
        assertEquals(listOf("Bench Press (Barbell)"), w.exercises.map { it.name }, "timed sets are left out")
        val bench = w.exercises.single().sets
        assertEquals(SetType.WARMUP, bench[0].type)
        assertEquals(1.5, bench[1].rir)
        assertEquals(setOf("hevyapi-old1"), changes.deletedIds)
        assertEquals(2, fake.sent.size)
    }

    @Test fun firstSyncReplacesTheCsvImportAndKeepsAppWorkouts() {
        val csv = Workout("hevy-1-2", "Old", 1, 2, emptyList(), source = "hevy")
        val app = Workout("app-5", "Full body A", 5, 6, emptyList(), source = "app")
        val fromApi = HevyApi.parseWorkout(Json.parseToJsonElement(workoutJson).jsonObject)
        val first = HevyApi.merge(listOf(csv, app), HevyApi.Changes(listOf(fromApi), emptySet()), firstSync = true)
        assertEquals(listOf("app-5", "hevyapi-b459cba5"), first.map { it.id })
        // Later syncs edit and delete in place.
        val edited = fromApi.copy(title = "Push (edited)")
        val later = HevyApi.merge(first, HevyApi.Changes(listOf(edited), emptySet()), firstSync = false)
        assertEquals("Push (edited)", later.last().title)
        assertEquals(listOf("app-5"), HevyApi.merge(later, HevyApi.Changes(emptyList(), setOf(fromApi.id)), firstSync = false).map { it.id })
    }

    @Test fun plansBecomeHevyRoutinesWithEverySet() {
        val plan = SessionPlan("Full body B", "Why today.", listOf(
            PlannedExercise("Bench Press (Barbell)", 3, 5, 8, 70.0, 2, 150, "Same weight.", targetReps = 7),
            PlannedExercise("Mystery Machine", 3, 8, 12, 20.0, 2, 120),
        ), "rules")
        val (routine, skipped) = HevyApi.routineJson(plan, mapOf("Bench Press (Barbell)" to "X"))
        assertEquals(listOf("Mystery Machine"), skipped)
        val ex = routine["exercises"]!!.jsonArray.single().jsonObject
        assertEquals("X", ex["exercise_template_id"]!!.jsonPrimitive.content)
        val sets = ex["sets"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("warmup", "warmup", "normal", "normal", "normal"), sets.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("7", sets[2]["reps"]!!.jsonPrimitive.content)
        assertEquals("5", sets[2]["rep_range"]!!.jsonObject["start"]!!.jsonPrimitive.content)
        assertTrue(routine["title"]!!.jsonPrimitive.content.startsWith("Rep Coach"))
    }

    @Test fun sendingReplacesLastRoutineOrCreatesOne() {
        val templates = 200 to """{"page":1,"page_count":1,"exercise_templates":[{"id":"X","title":"Bench Press (Barbell)"}]}"""
        val plan = SessionPlan("Full body A", "s", listOf(PlannedExercise("Bench Press (Barbell)", 3, 5, 8, 70.0, 2, 150)), "rules")
        val updating = FakeHevy(mapOf("GET /v1/exercise_templates" to templates, "PUT /v1/routines/r9" to (200 to "{}")))
        assertEquals("r9", HevyApi("k", updating).sendRoutine(plan, "r9").routineId)
        val creating = FakeHevy(mapOf("GET /v1/exercise_templates" to templates, "POST /v1/routines" to (201 to """{"routine":[{"id":"new1"}]}""")))
        assertEquals("new1", HevyApi("k", creating).sendRoutine(plan, "gone").routineId)
        assertTrue(creating.sent.last().third!!.contains("\"folder_id\":null"))
    }

    @Test fun badKeyExplainsHevyPro() {
        val fake = FakeHevy(mapOf("GET /v1/workouts/events" to (401 to "{}")))
        val e = runCatching { HevyApi("bad", fake).changesSince(Instant.EPOCH) }.exceptionOrNull()
        assertTrue(e is HevyApi.HevyException && "Hevy Pro" in e.message!!)
    }
}
