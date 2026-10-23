package app.repcoach.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One finding the coach's rules rest on, in the lifter's words, with where it comes from. */
data class Evidence(val rule: String, val finding: String, val source: String)

/**
 * Everything behind one suggestion: what it is, the facts and rules that produced it, and the
 * research those rules follow. Shown as is without a Claude key, and handed to Claude for "Ask why".
 */
data class WhyTopic(val title: String, val facts: List<String>, val evidence: List<Evidence>) {
    fun asText(): String = buildString {
        appendLine("## What the lifter is asking about: $title")
        facts.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Research behind these rules")
        evidence.forEach { appendLine("- ${it.rule}: ${it.finding} (${it.source})") }
    }
}

/** Builds [WhyTopic]s for a whole plan, one exercise, or one set. Mirrors docs/RESEARCH.md. */
object Why {
    val VOLUME = Evidence("At least 10 hard sets per muscle a week",
        "Size and strength rise with weekly sets, with diminishing returns that come sooner for strength.",
        "Pelland et al. 2026; Schoenfeld, Ogborn & Krieger 2017")
    val FREQUENCY = Evidence("Each muscle two or three times a week",
        "Each extra training day added a little strength; frequency barely affected growth once weekly sets were equal.",
        "Pelland et al. 2026; Schoenfeld, Ogborn & Krieger 2016")
    val RECOVERY = Evidence("48 hours before the same muscle works hard again",
        "After sets close to failure, strength and speed took 24 to 48 hours or more to come back; sets stopped short of failure recovered faster. Training on back-to-back days gave the same gains when weekly volume was equal.",
        "Morán-Navarro et al. 2017; Yang et al. 2018")
    val REPS = Evidence("Rep range by exercise",
        "Growth is similar from about 5 to 30 reps when sets end near failure; heavier loads build more maximal strength.",
        "Schoenfeld et al. 2017; Lopez et al. 2021")
    val REST = Evidence("Rest 1:30 to 2:30 by exercise",
        "Resting over 60 s helped growth a little with no clear gain past 90 s; in trained men 3 min beat 1 min for heavy work.",
        "Singer et al. 2024; Schoenfeld et al. 2016")
    val RIR = Evidence("1 to 2 reps in reserve",
        "Growth improves as sets end closer to failure while strength barely changes; going all the way to failure added nothing meaningful.",
        "Robinson et al. 2024; Refalo et al. 2023")
    val AUTOREG = Evidence("Re-plan each set from how hard the last felt",
        "Rating sets by reps in reserve is a valid way to judge and adjust effort during a session.",
        "Helms et al. 2016")
    val PROGRESSION = Evidence("Add 2.5 kg once every set reaches the top of the range",
        "Increase load by about 2 to 10% when the target reps can be beaten, in the smallest practical step.",
        "ACSM 2009")
    val DELOAD = Evidence("A lighter week when several lifts stall",
        "Deloads should cut volume and effort; a full week off slightly reduced strength gains in trained lifters.",
        "Bell et al. 2023; Coleman et al. 2024")
    val SLEEP = Evidence("Less volume after poor sleep",
        "Acute sleep loss lowered performance by about 7.6% on average, more later in the day.",
        "Craven et al. 2022")
    val PLATEAU = Evidence("Support work for a stalled lift",
        "Coaching practice rather than a tested rule, based on strength gains being specific to the movements trained.",
        "coaching practice")

    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(day)

    fun forPlan(plan: SessionPlan, p: Progression, recovery: Recovery? = null): WhyTopic {
        val muscles = MuscleRecovery(p.workouts, p.now)
        val low = p.weeklyVolume().filterValues { it < Progression.WEEKLY_SET_TARGET }.keys
        val facts = buildList {
            add("Today's plan: ${plan.title}. ${plan.summary}")
            add(if (plan.source == "claude") "Claude chose and ordered the exercises; the rules engine set the weights and rep ranges."
                else "The built-in rules chose everything.")
            add("Exercises: " + plan.exercises.joinToString("; ") { "${it.name} ${it.sets}×${it.repsMin}–${it.repsMax}" })
            add("Sessions so far this week: ${p.sessionsThisWeek()}.")
            p.workouts.lastOrNull()?.let { add("Last session: ${it.title} on ${date(it.startMillis)}.") }
            add("Recovery by muscle: " + Muscle.entries.joinToString("; ") { muscles.describe(it) })
            if (low.isNotEmpty()) add("Below ${Progression.WEEKLY_SET_TARGET} hard sets in the last 7 days: " + low.joinToString(", ") { it.label() })
            recovery?.sleepHours?.let { add("Sleep last night: ${it.oneDecimal()} h.") }
            add("Pace: " + Pace(p.workouts).describe())
        }
        val evidence = listOfNotNull(VOLUME, FREQUENCY, RECOVERY, if (p.plateaus().isNotEmpty()) DELOAD else null, if (recovery?.sleepHours != null) SLEEP else null)
        return WhyTopic("today's plan (${plan.title})", facts, evidence)
    }

    fun forExercise(e: PlannedExercise, p: Progression): WhyTopic {
        val info = Exercises.info(e.name)
        val muscles = MuscleRecovery(p.workouts, p.now)
        val trend = p.trend(e.name)
        val facts = buildList {
            add("Prescription: ${e.sets} sets of ${e.repsMin}–${e.repsMax} reps at ${e.weightKg?.let { Progression.fmt(it) + " kg" } ?: "a weight you choose"}, ${e.targetRir} reps in reserve, ${e.restSeconds} s rest.")
            if (e.reason.isNotBlank()) add("Coach's reason: ${e.reason}")
            add("Works the ${info.primary.label()}" + (if (info.secondary.isNotEmpty()) ", with help from the " + info.secondary.joinToString(", ") { it.label() } else "") + ". Type: ${info.kind.name.lowercase()}.")
            add("Rep range: ${info.repReason}.")
            add("Rest: ${SetPlan.restReason(e)}.")
            add("Reps in reserve: " + if (info.kind == Kind.ISOLATION) "single-joint work is safe to push, so it stops 1 rep short." else "big lifts stop 2 reps short, which builds strength just as well with less fatigue and risk.")
            add("Recovery: ${muscles.describe(info.primary)}.")
            add("Hard sets for the ${info.primary.label()} in the last 7 days: ${p.weeklyVolume().getValue(info.primary).oneDecimal()} (target ${Progression.WEEKLY_SET_TARGET}).")
            val past = p.exposures[e.name].orEmpty().takeLast(3)
            if (past.isEmpty()) add("No history for this exercise yet.")
            past.forEach { x -> add("${date(x.startMillis)}: " + x.workingSets.joinToString(", ") { s -> "${Progression.fmt(s.weightKg)}×${s.reps}" + (s.rir?.let { " (${it.toInt()} RIR)" } ?: "") }) }
            trend?.let { t ->
                add("Best estimated 1RM, last 8 weeks: ${t.bestRecentE1rm.toInt()} kg" + (t.changePercent?.let { " (%+.1f%% on the 8 weeks before)".format(Locale.UK, it) } ?: "") + if (t.plateau) ". Flagged as stalled." else ".")
            }
        }
        val evidence = listOfNotNull(REPS, RIR, REST, PROGRESSION, RECOVERY, VOLUME, if (trend?.plateau == true) PLATEAU else null)
        return WhyTopic(e.name, facts, evidence)
    }

    /** [number] counts working sets from 1. [logged] is what's been logged for this exercise today. */
    fun forSet(e: PlannedExercise, number: Int, suggestion: SetSuggestion, logged: List<LoggedSet>, p: Progression): WhyTopic {
        val today = logged.filter { it.type != SetType.WARMUP }
        val facts = buildList {
            add("Set $number of ${e.sets}: ${suggestion.weightKg?.let { Progression.fmt(it) + " kg" } ?: "your choice of weight"} for ${suggestion.reps} reps.")
            if (suggestion.note.isNotBlank()) add("Reason on the set: ${suggestion.note}.")
            add(if (today.isEmpty()) "Nothing logged yet today, so this comes from the plan."
                else "Logged today: " + today.joinToString(", ") { s -> "${Progression.fmt(s.weightKg)}×${s.reps}" + (s.rir?.let { " (${it.toInt()} RIR)" } ?: " (no RIR logged)") })
            add("How later sets are re-planned: 2 or more reps easier than planned adds 2.5 kg; a grind or missing the rep range drops the weight to 90% or 2.5 kg less; 1 rep closer to failure than planned aims 1 rep fewer; otherwise same again.")
        }
        val base = forExercise(e, p)
        return WhyTopic("set $number of ${e.name}", facts + base.facts, listOf(AUTOREG, RIR, PROGRESSION) + base.evidence.filter { it != RIR && it != PROGRESSION })
    }
}
