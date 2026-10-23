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

        // The engine has the final say on load for anything with history, and keeps muscles that
        // are still recovering from hard sets in the last 48 hours on easy work.
        val tired = MuscleRecovery(progression.workouts, progression.now).recovering()
        val exercises = parsed.exercises.map { ex ->
            val target = progression.target(ex.name, energy, ex.sets)
            val engineWeight = target.weightKg
            val easy = Exercises.info(ex.name).primary in tired
            ex.copy(
                sets = if (easy) ex.sets.coerceAtMost(2) else ex.sets,
                targetRir = if (easy) maxOf(ex.targetRir, target.targetRir + 1) else ex.targetRir,
                weightKg = engineWeight ?: ex.weightKg?.let(Progression::roundLoad),
                repsMin = if (engineWeight != null) target.repsMin else ex.repsMin,
                repsMax = if (engineWeight != null) target.repsMax else ex.repsMax,
                targetReps = if (engineWeight != null) target.targetReps else null,
                restSeconds = ex.restSeconds.coerceIn(45, 300),
            )
        }
        val minutes = Pace(progression.workouts).planMinutes(exercises)
        return SessionPlan(parsed.title, "${parsed.summary} About $minutes minutes at your usual pace.", exercises, source = "claude", estimatedMinutes = minutes)
    }

    /**
     * Answers the lifter's questions about one suggestion, from [briefing] (the rules, facts and
     * research behind it plus their training summary). [turns] alternate, starting with the lifter.
     */
    fun ask(briefing: String, turns: List<ChatTurn>): String {
        require(turns.isNotEmpty() && turns.first().fromLifter)
        fun build(withFallbacks: Boolean): MessageCreateParams {
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .systemOfTextBlockParams(listOf(
                    TextBlockParam.builder().text(EXPLAIN_PROMPT).cacheControl(CacheControlEphemeral.builder().build()).build()
                ))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            turns.forEachIndexed { i, t ->
                val text = if (i == 0) "$briefing\n\n## Question\n${t.text}" else t.text
                if (t.fromLifter) builder.addUserMessage(text) else builder.addAssistantMessage(text)
            }
            if (withFallbacks) {
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            return builder.build()
        }
        val reply = try {
            client.messages().create(build(withFallbacks = !model.startsWith("claude-haiku")))
        } catch (e: BadRequestException) {
            client.messages().create(build(withFallbacks = false))
        }
        return textOf(reply)
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
            error("Claude declined to answer")
        }
        return message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
            .ifBlank { error("Claude returned an empty reply") }
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
              one minute per exercise for warm-up sets and setup, and five minutes overall to warm up, then
              multiply by the lifter's pace factor in the briefing, which is learned from how long their
              past sessions really took. The draft already fits; keep its size unless you have a reason.
            - Lower energy or poor recovery means fewer sets and more reps in reserve, not a different programme.
            - Check "Muscle recovery" in the briefing. A muscle marked recovering did hard sets in the last
              48 hours: don't give it hard work today. Train the recovered muscles instead, and if most of
              the body is recovering, say rest is the best choice and offer only an easy session. Never
              repeat the previous session when it was less than 48 hours ago.
            - Where a lift is flagged as plateaued, include one or two accessories that address the usual
              limiters for that lift (for bench: triceps lockout, upper-back stability, paused or close-grip work).
            - Prefer exercises the lifter already uses, spelled exactly as in their history, so progress tracks.
              You may introduce a new exercise when variety or a plateau calls for it.
            - Use the draft's weights, rep ranges and rest times for exercises that have them. For a new
              exercise, set weightKg to null and say in the reason how to choose a starting weight.
            - All weights are in kilograms and move in steps of 2.5 kg.
            - Each reason is one short sentence the lifter reads in the gym, saying why this exercise and this
              load today: the muscle it tops up, what changed since last time, or the stalled lift it supports.
              The summary is two sentences at most, saying what today is and why.
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
            - Pick reps by exercise: growth is similar across 5 to 30 reps when sets end near failure, but
              heavier loads build more strength. So heavy compounds 5-8, machine and dumbbell compounds
              8-12, single-joint work 10-15, and small muscles (side and rear delts, calves, abs) 12-20.
              Keep the draft's rep ranges for exercises that have them (Schoenfeld 2017; Lopez 2021).
            - Strength and power take 24 to 48 hours or more to recover after sets near failure, less when
              sets stop short; training on consecutive days is fine when it hits different muscles or
              weekly volume stays the same (Morán-Navarro 2017; Yang 2018).
            - Progress load once the lifter beats the top of the rep range, in the smallest step available
              (ACSM 2009).
            - When several lifts stall and recovery is poor, suggest a lighter week with roughly half the
              sets and more reps in reserve rather than time off; a full week off slightly reduced strength
              gains in trained lifters (Bell 2023; Coleman 2024).
            - A short or late night measurably lowers performance, especially later in the day, so trim
              volume rather than load after poor sleep (Craven 2022).
        """.trimIndent()

        val EXPLAIN_PROMPT = """
            You are the lifter's strength coach, explaining a suggestion the coaching app made: a whole
            session, one exercise, or one set. The briefing gives the rules that produced it, the facts
            they used from the lifter's own history, the research behind each rule, and their recent
            training. The app's rules engine sets weights, reps and rest; an AI model may have chosen the
            exercises.

            How to answer:
            - Lead with the direct answer in plain words, then the reasons: their own numbers first
              (dates, weights, reps, sets this week, hours since a muscle was trained), then the rule, then
              the research by author and year from the briefing.
            - Keep it short: a few sentences or a short list. The lifter reads this in the gym.
            - Be honest about the strength of the evidence. Say when a rule is coaching practice or a
              rule of thumb, and don't invent studies, numbers or history that aren't in the briefing.
            - If the lifter challenges a suggestion, take it seriously. If they're right, or the rule
              doesn't fit their situation, say so plainly and say what you'd do instead and how to change
              it in the app (adjust the weight, swap the exercise, skip a set, add a note in Settings).
              If the rule still holds, explain why without being defensive.
            - Never give medical advice beyond suggesting they stop and see a professional if something hurts.
            - Use kilograms. No headings, no tables.
        """.trimIndent()
    }
}

/** One message in an "Ask why" conversation. */
@Serializable
data class ChatTurn(val fromLifter: Boolean, val text: String)

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

        val pace = Pace(p.workouts)
        appendLine("Pace factor: %.2f (actual session time ÷ standard estimate). ".format(Locale.UK, pace.factor) + pace.describe())

        appendLine()
        appendLine("## Last 14 days")
        val recent = p.recent(14)
        if (recent.isEmpty()) appendLine("No sessions.")
        for (w in recent) {
            appendLine("${date(w.startMillis)}, ${w.title}, ${(w.endMillis - w.startMillis) / 60_000} min, ${w.volumeKg().toInt()} kg total:")
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
        val muscles = MuscleRecovery(p.workouts, p.now)
        appendLine("Muscle recovery (hard sets in the last ${MuscleRecovery.READY_HOURS} h mean not ready for hard work): " +
            Muscle.entries.joinToString("; ") { muscles.describe(it) })
        muscles.recovering().takeIf { it.isNotEmpty() }?.let { r ->
            appendLine("Recovering, so no hard sets today: " + r.joinToString(", ") { it.label() })
        }

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
