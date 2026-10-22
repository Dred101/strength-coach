package app.repcoach.core

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.BadRequestException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Asks Claude to choose today's session. The [Progression] engine supplies the numbers; Claude
 * picks and orders the exercises, keeps extra sessions varied and explains its reasoning.
 */
class ClaudeCoach(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
) {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    fun plan(workouts: List<Workout>, checkIn: CheckIn, recovery: Recovery?, equipmentNotes: String = ""): SessionPlan {
        val progression = Progression(workouts)
        val planner = Planner(progression)
        val draft = planner.plan(checkIn, recovery)
        val energy = planner.adjustedEnergy(checkIn.energy, recovery)
        val context = CoachContext.build(progression, checkIn, recovery, draft, equipmentNotes)

        val reply = try {
            // Haiku has no server-side refusal fallback, so only ask for one on Sonnet and Opus.
            client.messages().create(request(context, withFallbacks = !model.startsWith("claude-haiku")))
        } catch (e: BadRequestException) {
            // Fallbacks are a beta feature; if this account or model rejects them, ask again without.
            client.messages().create(request(context, withFallbacks = false))
        }
        val text = textOf(reply)
        val parsed = json.decodeFromString(ClaudePlan.serializer(), text)

        // The engine has the final say on load for anything with history.
        val exercises = parsed.exercises.map { ex ->
            val target = progression.target(ex.name, energy, ex.sets)
            val engineWeight = target.weightKg
            ex.copy(
                weightKg = engineWeight ?: ex.weightKg?.let(Progression::roundLoad),
                repsMin = if (engineWeight != null) target.repsMin else ex.repsMin,
                repsMax = if (engineWeight != null) target.repsMax else ex.repsMax,
                targetReps = if (engineWeight != null) target.targetReps else null,
                restSeconds = ex.restSeconds.coerceIn(45, 300),
            )
        }
        return SessionPlan(parsed.title, parsed.summary, exercises, source = "claude")
    }

    private fun request(context: String, withFallbacks: Boolean): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .systemOfTextBlockParams(listOf(
                TextBlockParam.builder()
                    .text(SYSTEM_PROMPT)
                    .cacheControl(CacheControlEphemeral.builder().build())
                    .build()
            ))
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.MEDIUM)
                    .format(JsonOutputFormat.builder().schema(planSchema()).build())
                    .build()
            )
            .addUserMessage(context)
        if (withFallbacks) {
            // If a safety classifier declines, the server retries on a suitable model instead of stopping.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun textOf(message: Message): String {
        if (message.stopReason().map { it.toString() }.orElse("") == "refusal") {
            error("Claude declined to plan this session")
        }
        return message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
            .ifBlank { error("Claude returned an empty plan") }
    }

    @Serializable
    private data class ClaudePlan(val title: String, val summary: String, val exercises: List<PlannedExercise>)

    companion object {
        /** Cheapest current model; plenty for planning one session from a short briefing. */
        const val DEFAULT_MODEL = "claude-haiku-5-5"

        /** Models offered in Settings, cheapest first, with a rough cost per planned session. */
        val MODELS = listOf(
            "claude-haiku-5-5" to "Haiku: cheapest, under 1p a plan",
            "claude-sonnet-5-5" to "Sonnet: middle, about 3p a plan",
            "claude-opus-5-5" to "Opus: best, about 7p a plan",
        )

        private val json = Json { ignoreUnknownKeys = true }

        private fun planSchema(): JsonOutputFormat.Schema {
            val exercise = mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("name", "sets", "repsMin", "repsMax", "weightKg", "targetRir", "restSeconds", "reason"),
                "properties" to mapOf(
                    "name" to mapOf("type" to "string"),
                    "sets" to mapOf("type" to "integer"),
                    "repsMin" to mapOf("type" to "integer"),
                    "repsMax" to mapOf("type" to "integer"),
                    "weightKg" to mapOf("type" to listOf("number", "null")),
                    "targetRir" to mapOf("type" to "integer"),
                    "restSeconds" to mapOf("type" to "integer"),
                    "reason" to mapOf("type" to "string"),
                ),
            )
            return JsonOutputFormat.Schema.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .putAdditionalProperty("required", JsonValue.from(listOf("title", "summary", "exercises")))
                .putAdditionalProperty("properties", JsonValue.from(mapOf(
                    "title" to mapOf("type" to "string"),
                    "summary" to mapOf("type" to "string"),
                    "exercises" to mapOf("type" to "array", "items" to exercise),
                )))
                .build()
        }

        val SYSTEM_PROMPT = """
            You are a strength coach planning one gym session for a single, experienced recreational lifter.
            You receive their check-in (energy out of 10 and minutes available), recovery data from their
            watch, a summary of recent training, per-lift trends, and a draft session built by a rules engine.

            How to plan:
            - The lifter's default week is three full-body sessions. If they have already trained three or more
              times this week, today is a variety session: use different machines or variations from the last
              session and favour muscle groups with the lowest volume this week.
            - Fit the session to the minutes available. Budget about 40 seconds per working set plus its rest,
              one minute per exercise for warm-up sets and setup, and five minutes overall to warm up.
            - Lower energy or poor recovery means fewer sets and more reps in reserve, not a different programme.
            - Where a lift is flagged as plateaued, include one or two accessories that address the usual
              limiters for that lift (for bench: triceps lockout, upper-back stability, paused or close-grip work).
            - Prefer exercises the lifter already uses, spelled exactly as in their history, so progress tracks.
              You may introduce a new exercise when variety or a plateau calls for it.
            - Use the draft's weights, rep ranges and rest times for exercises that have them. For a new
              exercise, set weightKg to null and say in the reason how to choose a starting weight.
            - All weights are in kilograms and move in steps of 2.5 kg.
            - Each reason is one short sentence the lifter reads in the gym. The summary is two sentences at
              most, saying what today is and why.
            - Order the session with the most demanding compound lifts first.

            Evidence to plan by (sources in the app's docs/RESEARCH.md; don't cite them to the lifter):
            - Weekly volume matters most for growth: aim for at least 10 hard sets per muscle per week,
              counting a set as half for muscles that only assist. More sets still help but with
              diminishing returns, and strength gains flatten sooner than size (Schoenfeld 2017; Pelland 2026).
            - Training each muscle two or three times a week helps strength more than size, which is why
              the week is full-body (Schoenfeld 2016; Pelland 2026).
            - Rest more than 60 seconds between sets; past 90 seconds adds little for growth, but heavy
              compounds deserve two to three minutes (Singer 2024; Schoenfeld 2016).
            - Stopping 1 to 3 reps short of failure works for strength; growth benefits from getting closer,
              so isolation work can go to 1 rep in reserve while big lifts stay at 2 (Robinson 2024; Refalo 2023).
            - Progress load once the lifter beats the top of the rep range, in the smallest step available
              (ACSM 2009).
            - When several lifts stall and recovery is poor, suggest a lighter week with roughly half the
              sets and more reps in reserve rather than time off; a full week off slightly reduced strength
              gains in trained lifters (Bell 2023; Coleman 2024).
            - A short or late night measurably lowers performance, especially later in the day, so trim
              volume rather than load after poor sleep (Craven 2022).
        """.trimIndent()
    }
}

/** Turns training history into the compact briefing Claude plans from. */
object CoachContext {
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    fun build(p: Progression, checkIn: CheckIn, recovery: Recovery?, draft: SessionPlan, equipmentNotes: String): String = buildString {
        val zone = ZoneId.systemDefault()
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(day)

        appendLine("## Today")
        appendLine("Date: ${date(System.currentTimeMillis())}")
        appendLine("Energy: ${checkIn.energy}/10. Time available: ${checkIn.minutes} minutes.")
        appendLine("Sessions already done this week: ${p.sessionsThisWeek()}.")
        recovery?.let { r ->
            appendLine("Recovery: " + listOfNotNull(
                r.sleepHours?.let { "slept ${it.oneDecimal()} h" },
                r.restingHr?.let { "resting HR ${it.toInt()}" + (r.restingHrBaseline?.let { b -> " (30-day average ${b.toInt()})" } ?: "") },
                r.hrvMs?.let { "HRV ${it.toInt()} ms" + (r.hrvBaseline?.let { b -> " (average ${b.toInt()})" } ?: "") },
                r.stepsYesterday?.let { "$it steps yesterday" },
            ).joinToString(", ").ifEmpty { "no data" })
        }
        if (equipmentNotes.isNotBlank()) appendLine("Notes from the lifter: $equipmentNotes")

        appendLine()
        appendLine("## Last 14 days")
        val recent = p.recent(14)
        if (recent.isEmpty()) appendLine("No sessions.")
        for (w in recent) {
            appendLine("${date(w.startMillis)}, ${w.title}:")
            for (ex in w.exercises) {
                val sets = ex.sets.filter { it.isWorking }.joinToString(", ") { "${Progression.fmt(it.weightKg)}x${it.reps}" }
                appendLine("  ${ex.name}: $sets")
            }
        }

        appendLine()
        appendLine("## Hard sets per muscle, last 7 days")
        appendLine(p.weeklyVolume().entries.joinToString(", ") { "${it.key.name.lowercase()} ${it.value.oneDecimal()}" })
        p.weeklyVolume().filterValues { it < Progression.WEEKLY_SET_TARGET }.keys.takeIf { it.isNotEmpty() }?.let { low ->
            appendLine("Below ${Progression.WEEKLY_SET_TARGET} sets so far: " + low.joinToString(", ") { it.name.lowercase() })
        }
        appendLine("Days since trained: " + p.daysSinceMuscle().entries.joinToString(", ") {
            "${it.key.name.lowercase()} ${it.value ?: "never"}"
        })

        appendLine()
        appendLine("## Lift trends (best estimated 1RM, last 8 weeks vs the 8 before)")
        for (name in p.exerciseNames().take(25)) {
            val t = p.trend(name) ?: continue
            val change = t.changePercent?.let { "%+.1f%%".format(Locale.UK, it) } ?: "new"
            appendLine("$name: ${t.bestRecentE1rm.toInt()} kg ($change)${if (t.plateau) " PLATEAU" else ""}")
        }

        appendLine()
        appendLine("## Exercises in their history, most used first")
        appendLine(p.exerciseNames().take(40).joinToString("; "))

        appendLine()
        appendLine("## Draft from the rules engine: ${draft.title}")
        for (e in draft.exercises) {
            appendLine("${e.name}: ${e.sets} x ${e.repsMin}-${e.repsMax} @ ${e.weightKg?.let { Progression.fmt(it) + " kg" } ?: "choose"}, RIR ${e.targetRir}, rest ${e.restSeconds}s. ${e.reason}")
        }
    }
}
