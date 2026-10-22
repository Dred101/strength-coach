package app.repcoach.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes workouts in the Strong app's CSV export format, the only format Hevy's importer accepts
 * (Profile, Settings, Export & Import Data, Import data, Import Strong CSV). Weights are in kg and
 * times in UTC. Exercise names are kept as they are: names that came from Hevy match its library,
 * and anything else becomes a custom exercise there.
 */
object StrongCsv {
    private val header = listOf(
        "Workout #", "Date", "Workout Name", "Duration (sec)", "Exercise Name", "Set Order",
        "Weight (kg)", "Reps", "RPE", "Distance (meters)", "Seconds", "Notes", "Workout Notes",
    )
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    fun export(workouts: List<Workout>): String = buildString {
        appendRow(header)
        workouts.sortedBy { it.startMillis }.forEachIndexed { i, w ->
            val duration = ((w.endMillis - w.startMillis) / 1000).coerceAtLeast(0)
            for (ex in w.exercises) ex.sets.forEachIndexed { s, set ->
                appendRow(listOf(
                    "${i + 1}", date.format(Instant.ofEpochMilli(w.startMillis)), w.title, "$duration", ex.name, "${s + 1}",
                    "%.1f".format(java.util.Locale.ROOT, set.weightKg), "${set.reps}",
                    set.rir?.let { "%.1f".format(java.util.Locale.ROOT, (10 - it).coerceIn(1.0, 10.0)) } ?: "",
                    "", "", if (s == 0) ex.notes else "", "",
                ))
            }
        }
    }

    private fun StringBuilder.appendRow(values: List<String>) {
        append(values.joinToString(";") { "\"" + it.replace("\"", "\"\"").replace('\n', ' ') + "\"" })
        append('\n')
    }
}
