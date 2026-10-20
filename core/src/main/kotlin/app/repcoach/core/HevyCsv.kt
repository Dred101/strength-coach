package app.repcoach.core

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reads the workout CSV that Hevy exports (Settings, Export & Import Data, Export Workouts). */
object HevyCsv {
    private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): List<Workout> {
        val rows = parseRows(text.removePrefix("﻿"))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        fun col(name: String) = header.indexOf(name).also {
            require(it >= 0) { "This doesn't look like a Hevy export: missing column \"$name\"" }
        }
        val iTitle = col("title")
        val iStart = col("start_time")
        val iEnd = col("end_time")
        val iExercise = col("exercise_title")
        val iNotes = col("exercise_notes")
        val iType = col("set_type")
        val iWeight = col("weight_kg")
        val iReps = col("reps")
        val iRpe = col("rpe")

        // Keep file order: Hevy lists sets in order within each workout and exercise.
        val workouts = LinkedHashMap<String, MutableMap<String, MutableList<LoggedSet>>>()
        val meta = HashMap<String, Triple<String, Long, Long>>()
        val notes = HashMap<Pair<String, String>, String>()

        for (row in rows.drop(1)) {
            if (row.size < header.size || row.all { it.isBlank() }) continue
            val start = row[iStart]
            val key = start + "|" + row[iTitle]
            val exercise = row[iExercise].trim()
            if (exercise.isEmpty()) continue
            meta.getOrPut(key) { Triple(row[iTitle], toMillis(start, zone), toMillis(row[iEnd], zone)) }
            val reps = row[iReps].toDoubleOrNull()?.toInt() ?: continue
            val set = LoggedSet(
                weightKg = row[iWeight].toDoubleOrNull() ?: 0.0,
                reps = reps,
                type = when (row[iType].lowercase()) {
                    "warmup" -> SetType.WARMUP
                    "dropset" -> SetType.DROPSET
                    "failure" -> SetType.FAILURE
                    else -> SetType.NORMAL
                },
                rir = row[iRpe].toDoubleOrNull()?.let { (10.0 - it).coerceAtLeast(0.0) },
            )
            workouts.getOrPut(key) { LinkedHashMap() }.getOrPut(exercise) { mutableListOf() }.add(set)
            if (row[iNotes].isNotBlank()) notes[key to exercise] = row[iNotes]
        }

        return workouts.map { (key, exercises) ->
            val (title, start, end) = meta.getValue(key)
            Workout(
                id = "hevy-$start-${title.hashCode()}",
                title = title,
                startMillis = start,
                endMillis = end,
                exercises = exercises.map { (name, sets) -> ExerciseLog(name, sets, notes[key to name] ?: "") },
                source = "hevy",
            )
        }.sortedBy { it.startMillis }
    }

    private fun toMillis(value: String, zone: ZoneId): Long =
        LocalDateTime.parse(value.trim(), dateFormat).atZone(zone).toInstant().toEpochMilli()

    /** Minimal RFC 4180 reader: quoted fields, doubled quotes, newlines inside quotes. */
    internal fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> inQuotes = true
                ',' -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row.toList()); row.clear() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row.toList()) }
        return rows
    }
}
