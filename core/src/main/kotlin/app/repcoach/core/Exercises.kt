package app.repcoach.core

enum class Muscle { CHEST, BACK, SHOULDERS, REAR_DELTS, BICEPS, TRICEPS, QUADS, HAMSTRINGS, GLUTES, CALVES, CORE }

enum class Kind { COMPOUND, ACCESSORY, ISOLATION }

data class ExerciseInfo(
    val name: String,
    val primary: Muscle,
    val secondary: Set<Muscle> = emptySet(),
    val kind: Kind,
) {
    /** Small muscles worked in isolation, where heavy low-rep sets mostly load the joints. */
    private val smallMuscleIsolation: Boolean
        get() = kind == Kind.ISOLATION && primary in setOf(Muscle.SHOULDERS, Muscle.REAR_DELTS, Muscle.CALVES, Muscle.CORE)

    /**
     * Growth is similar from about 5 to 30 reps when sets end near failure, while heavier loads build
     * more maximal strength (Schoenfeld et al. 2017; Lopez et al. 2021). So the big lifts you want to
     * get stronger on stay heavy, and smaller or riskier movements use lighter loads for more reps.
     */
    val repRange: IntRange
        get() = when {
            kind == Kind.COMPOUND -> 5..8
            kind == Kind.ACCESSORY -> 8..12
            smallMuscleIsolation -> 12..20
            else -> 10..15
        }

    /** Why this exercise uses its rep range, in a sentence the lifter reads in the gym. */
    val repReason: String
        get() = when {
            kind == Kind.COMPOUND -> "Heavy 5–8: big lifts are where strength is built, and heavier loads build it best"
            kind == Kind.ACCESSORY -> "Moderate 8–12: grows muscle as well as heavy work, with less joint stress and fatigue"
            smallMuscleIsolation -> "Light 12–20: small muscles grow as well with light loads near failure, and joints stay happy"
            else -> "10–15: single-joint work grows muscle well with lighter loads taken close to failure"
        }

    /** Smallest sensible jump in load once the top of the rep range is reached. */
    val incrementKg: Double
        get() = Progression.STEP_KG

    /**
     * Rest between working sets. Growth benefits from resting more than 60 s with no clear gain past
     * 90 s (Singer et al. 2024); heavy compounds get longer so strength sets aren't cut short
     * (Schoenfeld et al. 2016). See docs/RESEARCH.md.
     */
    val restSeconds: Int
        get() = when (kind) {
            Kind.COMPOUND -> 150
            Kind.ACCESSORY -> 120
            Kind.ISOLATION -> 90
        }

    /**
     * Reps in reserve on a normal day. Growth improves as sets end closer to failure while strength
     * barely changes (Robinson et al. 2024), so isolation work, which is safe to push, stops one rep
     * short and compounds stop two short.
     */
    val targetRir: Int
        get() = if (kind == Kind.ISOLATION) 1 else 2
}

/**
 * Classifies exercises by name, so anything imported from Hevy gets muscle groups and a rep range
 * without a hand-maintained table for every machine.
 */
object Exercises {
    private data class Rule(val keywords: List<String>, val primary: Muscle, val secondary: Set<Muscle>, val kind: Kind)

    // Order matters: the first rule whose keywords all appear in the name wins.
    private val rules = listOf(
        Rule(listOf("romanian"), Muscle.HAMSTRINGS, setOf(Muscle.GLUTES, Muscle.BACK), Kind.COMPOUND),
        Rule(listOf("deadlift"), Muscle.BACK, setOf(Muscle.HAMSTRINGS, Muscle.GLUTES), Kind.COMPOUND),
        Rule(listOf("hip thrust"), Muscle.GLUTES, setOf(Muscle.HAMSTRINGS), Kind.ACCESSORY),
        Rule(listOf("glute"), Muscle.GLUTES, emptySet(), Kind.ISOLATION),
        Rule(listOf("hip abduct"), Muscle.GLUTES, emptySet(), Kind.ISOLATION),
        Rule(listOf("hip adduct"), Muscle.QUADS, emptySet(), Kind.ISOLATION),
        Rule(listOf("leg curl"), Muscle.HAMSTRINGS, emptySet(), Kind.ISOLATION),
        Rule(listOf("leg extension"), Muscle.QUADS, emptySet(), Kind.ISOLATION),
        Rule(listOf("leg press"), Muscle.QUADS, setOf(Muscle.GLUTES), Kind.ACCESSORY),
        Rule(listOf("lunge"), Muscle.QUADS, setOf(Muscle.GLUTES), Kind.ACCESSORY),
        Rule(listOf("split squat"), Muscle.QUADS, setOf(Muscle.GLUTES), Kind.ACCESSORY),
        Rule(listOf("hack squat"), Muscle.QUADS, setOf(Muscle.GLUTES), Kind.ACCESSORY),
        Rule(listOf("squat"), Muscle.QUADS, setOf(Muscle.GLUTES, Muscle.CORE), Kind.COMPOUND),
        Rule(listOf("calf"), Muscle.CALVES, emptySet(), Kind.ISOLATION),
        Rule(listOf("close grip", "bench"), Muscle.TRICEPS, setOf(Muscle.CHEST), Kind.ACCESSORY),
        Rule(listOf("incline", "bench"), Muscle.CHEST, setOf(Muscle.SHOULDERS, Muscle.TRICEPS), Kind.ACCESSORY),
        Rule(listOf("bench press", "barbell"), Muscle.CHEST, setOf(Muscle.TRICEPS, Muscle.SHOULDERS), Kind.COMPOUND),
        Rule(listOf("bench press"), Muscle.CHEST, setOf(Muscle.TRICEPS, Muscle.SHOULDERS), Kind.ACCESSORY),
        Rule(listOf("chest press"), Muscle.CHEST, setOf(Muscle.TRICEPS), Kind.ACCESSORY),
        Rule(listOf("dip"), Muscle.TRICEPS, setOf(Muscle.CHEST), Kind.ACCESSORY),
        Rule(listOf("push up"), Muscle.CHEST, setOf(Muscle.TRICEPS), Kind.ACCESSORY),
        Rule(listOf("fly"), Muscle.CHEST, emptySet(), Kind.ISOLATION),
        Rule(listOf("pec deck"), Muscle.CHEST, emptySet(), Kind.ISOLATION),
        Rule(listOf("overhead press"), Muscle.SHOULDERS, setOf(Muscle.TRICEPS), Kind.COMPOUND),
        Rule(listOf("shoulder press"), Muscle.SHOULDERS, setOf(Muscle.TRICEPS), Kind.ACCESSORY),
        Rule(listOf("arnold"), Muscle.SHOULDERS, setOf(Muscle.TRICEPS), Kind.ACCESSORY),
        Rule(listOf("lateral raise"), Muscle.SHOULDERS, emptySet(), Kind.ISOLATION),
        Rule(listOf("front raise"), Muscle.SHOULDERS, emptySet(), Kind.ISOLATION),
        Rule(listOf("face pull"), Muscle.REAR_DELTS, setOf(Muscle.BACK), Kind.ISOLATION),
        Rule(listOf("rear delt"), Muscle.REAR_DELTS, emptySet(), Kind.ISOLATION),
        Rule(listOf("reverse fly"), Muscle.REAR_DELTS, emptySet(), Kind.ISOLATION),
        Rule(listOf("shrug"), Muscle.BACK, emptySet(), Kind.ISOLATION),
        Rule(listOf("pull up"), Muscle.BACK, setOf(Muscle.BICEPS), Kind.COMPOUND),
        Rule(listOf("chin up"), Muscle.BACK, setOf(Muscle.BICEPS), Kind.COMPOUND),
        Rule(listOf("pulldown"), Muscle.BACK, setOf(Muscle.BICEPS), Kind.ACCESSORY),
        Rule(listOf("pullover"), Muscle.BACK, setOf(Muscle.CHEST), Kind.ISOLATION),
        Rule(listOf("row"), Muscle.BACK, setOf(Muscle.BICEPS, Muscle.REAR_DELTS), Kind.ACCESSORY),
        Rule(listOf("curl"), Muscle.BICEPS, emptySet(), Kind.ISOLATION),
        Rule(listOf("pushdown"), Muscle.TRICEPS, emptySet(), Kind.ISOLATION),
        Rule(listOf("tricep"), Muscle.TRICEPS, emptySet(), Kind.ISOLATION),
        Rule(listOf("skullcrusher"), Muscle.TRICEPS, emptySet(), Kind.ISOLATION),
        Rule(listOf("crunch"), Muscle.CORE, emptySet(), Kind.ISOLATION),
        Rule(listOf("plank"), Muscle.CORE, emptySet(), Kind.ISOLATION),
        Rule(listOf("leg raise"), Muscle.CORE, emptySet(), Kind.ISOLATION),
        Rule(listOf("ab "), Muscle.CORE, emptySet(), Kind.ISOLATION),
    )

    fun info(name: String): ExerciseInfo {
        val n = name.lowercase().replace('-', ' ')
        val rule = rules.firstOrNull { r -> r.keywords.all { n.contains(it) } }
        return if (rule != null) ExerciseInfo(name, rule.primary, rule.secondary, rule.kind)
        else ExerciseInfo(name, Muscle.CORE, emptySet(), Kind.ISOLATION)
    }

    /** Starter exercises the coach can pick from even if they've never been logged. */
    val starterLibrary = listOf(
        "Squat (Barbell)", "Leg Press (Machine)", "Romanian Deadlift (Barbell)", "Leg Curl (Machine)",
        "Leg Extension (Machine)", "Hip Thrust (Barbell)", "Calf Raise (Machine)", "Bulgarian Split Squat",
        "Bench Press (Barbell)", "Incline Bench Press (Dumbbell)", "Close Grip Bench Press (Barbell)",
        "Chest Press (Machine)", "Low Cable Fly Crossovers", "Overhead Press (Barbell)", "Shoulder Press (Dumbbell)",
        "Lateral Raise (Dumbbell)", "Face Pull", "Rear Delt Reverse Fly (Machine)", "Pull Up",
        "Lat Pulldown (Cable)", "Seated Cable Row", "Chest Supported Row (Dumbbell)", "Triceps Rope Pushdown",
        "Skullcrusher (EZ Bar)", "Preacher Curl (Machine)", "Hammer Curl (Dumbbell)", "Cable Crunch", "Hanging Leg Raise",
    )
}
