package app.repcoach.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Hevy's public API (needs Hevy Pro and a key from hevy.com/settings?developer). Pulls workouts
 * logged in Hevy so Rep Coach plans from them, and sends a plan to Hevy as a routine to log there.
 */
class HevyApi(private val apiKey: String, private val http: Http = UrlHttp) {
    /** One HTTP call; returns the status code and body. Swappable so tests run without a network. */
    fun interface Http {
        fun send(method: String, url: String, apiKey: String, body: String?): Pair<Int, String>
    }

    class HevyException(message: String) : Exception(message)

    /** What changed in Hevy since a moment: workouts added or edited, and ids of deleted ones. */
    data class Changes(val updated: List<Workout>, val deletedIds: Set<String>)

    fun changesSince(since: Instant): Changes {
        val updated = mutableListOf<Workout>()
        val deleted = mutableSetOf<String>()
        var page = 1
        while (true) {
            val (code, body) = call("GET", "/v1/workouts/events?page=$page&pageSize=10&since=" + URLEncoder.encode(since.toString(), "UTF-8"))
            // Hevy answers 404 for a page past the end.
            if (code == 404 && page > 1) break
            check(code, body)
            val root = json.parseToJsonElement(body).jsonObject
            for (e in root["events"]?.jsonArray.orEmpty()) {
                val o = e.jsonObject
                when (o.str("type")) {
                    "updated" -> o["workout"]?.jsonObject?.let { updated += parseWorkout(it) }
                    "deleted" -> o.str("id")?.let { deleted += ID_PREFIX + it }
                }
            }
            val pages = root["page_count"]?.jsonPrimitive?.intOrNull ?: 1
            if (page >= pages) break
            page++
        }
        return Changes(updated, deleted)
    }

    /** Hevy's exercise ids by exercise title, which is the name Rep Coach uses. */
    fun templateIds(): Map<String, String> {
        val ids = mutableMapOf<String, String>()
        var page = 1
        while (true) {
            val (code, body) = call("GET", "/v1/exercise_templates?page=$page&pageSize=100")
            if (code == 404 && page > 1) break
            check(code, body)
            val root = json.parseToJsonElement(body).jsonObject
            for (t in root["exercise_templates"]?.jsonArray.orEmpty()) {
                val o = t.jsonObject
                val title = o.str("title") ?: continue
                val id = o.str("id") ?: continue
                ids.putIfAbsent(title, id)
            }
            val pages = root["page_count"]?.jsonPrimitive?.intOrNull ?: 1
            if (page >= pages) break
            page++
        }
        return ids
    }

    data class Sent(val routineId: String, val skipped: List<String>)

    /**
     * Writes [plan] to Hevy as one routine, replacing the routine sent last time ([routineId]) so the
     * routine list doesn't fill up. Exercises Hevy doesn't know by name are left out and listed.
     */
    fun sendRoutine(plan: SessionPlan, routineId: String?): Sent {
        val (routine, skipped) = routineJson(plan, templateIds())
        if (routineId != null) {
            val (code, body) = call("PUT", "/v1/routines/$routineId", buildJsonObject { put("routine", routine) }.toString())
            if (code in 200..299) return Sent(routineId, skipped)
            if (code != 404) check(code, body)
        }
        val created = buildJsonObject {
            put("routine", JsonObject(routine + ("folder_id" to JsonNull)))
        }
        val (code, body) = call("POST", "/v1/routines", created.toString())
        check(code, body)
        val root = json.parseToJsonElement(body).jsonObject
        // The response wraps the routine, in a list on some API versions.
        val r = when (val v = root["routine"]) {
            is JsonArray -> v.firstOrNull()?.jsonObject
            is JsonObject -> v
            else -> root
        }
        return Sent(r?.str("id") ?: throw HevyException("Hevy didn't return the new routine"), skipped)
    }

    private fun call(method: String, path: String, body: String? = null) = runCatching { http.send(method, BASE + path, apiKey, body) }
        .getOrElse { throw HevyException("Couldn't reach Hevy (${it.message ?: it.javaClass.simpleName})") }

    private fun check(code: Int, body: String) {
        when {
            code in 200..299 -> return
            code == 401 || code == 403 -> throw HevyException("Hevy didn't accept the API key. It needs Hevy Pro; copy it again from hevy.com/settings?developer")
            code == 429 -> throw HevyException("Hevy says too many requests; try again in a minute")
            else -> throw HevyException("Hevy error $code: ${body.take(200)}")
        }
    }

    companion object {
        const val BASE = "https://api.hevyapp.com"
        /** Workout ids from the API, so they never clash with CSV-imported or app-logged ones. */
        const val ID_PREFIX = "hevyapi-"
        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun time(s: String?): Long = s?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.getOrElse { _ -> Instant.parse(it) }.toEpochMilli()
        } ?: 0L

        fun parseWorkout(o: JsonObject): Workout {
            val exercises = o["exercises"]?.jsonArray.orEmpty().mapNotNull { e ->
                val ex = e.jsonObject
                val sets = ex["sets"]?.jsonArray.orEmpty().mapNotNull { s ->
                    val set = s.jsonObject
                    // Timed or distance sets have no reps; the coach only tracks weight × reps.
                    val reps = set["reps"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    LoggedSet(
                        weightKg = set["weight_kg"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        reps = reps,
                        type = when (set.str("type")) {
                            "warmup" -> SetType.WARMUP
                            "dropset" -> SetType.DROPSET
                            "failure" -> SetType.FAILURE
                            else -> SetType.NORMAL
                        },
                        rir = set["rpe"]?.jsonPrimitive?.doubleOrNull?.let { (10.0 - it).coerceAtLeast(0.0) },
                    )
                }
                if (sets.isEmpty()) null else ExerciseLog(ex.str("title") ?: "Unknown exercise", sets, ex.str("notes").orEmpty())
            }
            return Workout(
                id = ID_PREFIX + (o.str("id") ?: ""),
                title = o.str("title") ?: "Hevy workout",
                startMillis = time(o.str("start_time")),
                endMillis = time(o.str("end_time")),
                exercises = exercises,
                source = "hevy",
            )
        }

        /**
         * Applies [changes] to the workouts on the phone. On the first sync the API's full history
         * replaces any Hevy CSV import, so nothing is counted twice.
         */
        fun merge(existing: List<Workout>, changes: Changes, firstSync: Boolean): List<Workout> {
            val kept = existing.filter { w ->
                if (firstSync && w.source == "hevy") false else w.id !in changes.deletedIds
            }
            val incoming = changes.updated.associateBy { it.id }
            return (kept.filter { it.id !in incoming } + incoming.values).sortedBy { it.startMillis }
        }

        /** The routine body Hevy expects, plus the exercises it couldn't match. */
        fun routineJson(plan: SessionPlan, templates: Map<String, String>): Pair<JsonObject, List<String>> {
            val lower = templates.mapKeys { it.key.lowercase() }
            val skipped = mutableListOf<String>()
            val exercises = buildJsonArray {
                for (e in plan.exercises) {
                    val id = templates[e.name] ?: lower[e.name.lowercase()]
                    if (id == null) { skipped += e.name; continue }
                    add(buildJsonObject {
                        put("exercise_template_id", id)
                        put("superset_id", JsonNull)
                        put("rest_seconds", e.restSeconds)
                        put("notes", "${e.targetRir} in reserve. ${e.reason}".take(NOTE_LIMIT))
                        put("sets", buildJsonArray {
                            SetPlan.warmups(e).forEach { s -> add(set("warmup", s.weightKg, s.reps, null)) }
                            SetPlan.remaining(e, emptyList()).forEach { s -> add(set("normal", s.weightKg, s.reps, e.repsMin..e.repsMax)) }
                        })
                    })
                }
            }
            val routine = buildJsonObject {
                put("title", "Rep Coach: ${plan.title}")
                put("notes", plan.summary.take(NOTE_LIMIT))
                put("exercises", exercises)
            }
            return routine to skipped
        }

        private const val NOTE_LIMIT = 500

        private fun set(type: String, weight: Double?, reps: Int, range: IntRange?): JsonElement = buildJsonObject {
            put("type", type)
            if (weight != null) put("weight_kg", weight) else put("weight_kg", JsonNull)
            put("reps", reps)
            if (range != null) putJsonObject("rep_range") { put("start", range.first); put("end", range.last) }
        }
    }

    /** Plain JDK HTTP, which also works on Android. */
    object UrlHttp : Http {
        override fun send(method: String, url: String, apiKey: String, body: String?): Pair<Int, String> {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("api-key", apiKey)
                c.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                return code to text
            } finally {
                c.disconnect()
            }
        }
    }
}
