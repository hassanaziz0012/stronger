package app.stronger.data

import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ImportResult(val workouts: Int, val sets: Int, val newExercises: Int, val skipped: Int)

/** Reads the CSV that Strong's "Export data" produces (old comma and newer semicolon formats). */
class StrongCsvImporter(private val repo: Repository) {

    suspend fun import(text: String, assumedUnit: WeightUnit): ImportResult {
        val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
        require(lines.size > 1) { "The file has no workouts in it" }
        val delimiter = if (lines[0].count { it == ';' } > lines[0].count { it == ',' }) ';' else ','
        val header = parseLine(lines[0], delimiter).map { it.trim().lowercase() }
        fun col(vararg prefixes: String) = header.indexOfFirst { h -> prefixes.any { h.startsWith(it) } }
        val iDate = col("date"); val iName = col("workout name"); val iDur = col("duration")
        val iEx = col("exercise name"); val iOrder = col("set order"); val iWeight = col("weight")
        val iReps = col("reps"); val iDist = col("distance"); val iSec = col("seconds")
        val iNotes = header.indexOf("notes"); val iWNotes = col("workout notes")
        require(iDate >= 0 && iEx >= 0) { "This doesn't look like a Strong export" }
        val weightFactor = when {
            iWeight >= 0 && "lb" in header[iWeight] -> 1 / WeightUnit.LB.perKg
            iWeight >= 0 && "kg" in header[iWeight] -> 1.0
            else -> 1 / assumedUnit.perKg
        }
        val distFactor = if (iDist >= 0 && "meter" in header[iDist]) 0.001 else 1.0
        val durationInSeconds = iDur >= 0 && "sec" in header[iDur]

        data class Row(val date: String, val name: String, val duration: String, val exercise: String, val order: String,
                       val weight: Double?, val reps: Int?, val dist: Double?, val sec: Int?, val note: String, val wNote: String)

        val rows = lines.drop(1).mapNotNull { line ->
            val c = parseLine(line, delimiter)
            fun at(i: Int) = if (i in c.indices) c[i].trim() else ""
            val order = at(iOrder)
            if (order.equals("Rest Timer", true) || at(iEx).isBlank()) return@mapNotNull null
            Row(
                at(iDate), at(iName), at(iDur), at(iEx), order,
                at(iWeight).toDoubleOrNull()?.times(weightFactor), at(iReps).toDoubleOrNull()?.toInt(),
                at(iDist).toDoubleOrNull()?.times(distFactor), at(iSec).toDoubleOrNull()?.toInt(),
                at(iNotes), at(iWNotes),
            )
        }

        val existing = repo.allExercises().associateBy { it.name.lowercase() }.toMutableMap()
        val existingStarts = repo.database.workouts().all().map { it.startedAt }.toSet()
        var newExercises = 0
        val idFor = mutableMapOf<String, Long>()
        for ((name, exRows) in rows.groupBy { it.exercise }) {
            val found = existing[name.lowercase()]
            idFor[name] = if (found != null) {
                if (found.isArchived) repo.updateExercise(found.copy(isArchived = false))
                found.id
            } else {
                newExercises++
                repo.insertExercise(
                    ExerciseEntity(name = name, bodyPart = guessBodyPart(name), equipment = guessEquipment(name, exRows.map { Triple(it.weight, it.dist, it.sec to it.reps) }), isCustom = true)
                ).also { existing[name.lowercase()] = ExerciseEntity(id = it, name = name, bodyPart = BodyPart.OTHER, equipment = Equipment.OTHER) }
            }
        }
        val tracking = repo.allExercises().associate { it.id to it.tracking }

        val workouts = rows.groupBy { it.date to it.name }.entries.mapNotNull { (key, wRows) ->
            val start = parseDate(key.first) ?: return@mapNotNull null
            start to (key to wRows)
        }.sortedBy { it.first }

        val running = mutableMapOf<Long, Bests>()
        val history = mutableMapOf<Long, MutableList<SetEntity>>()
        var imported = 0; var setCount = 0; var skipped = 0
        repo.database.withTransaction {
            for ((start, entry) in workouts) {
                val (key, wRows) = entry
                if (start in existingStarts) { skipped++; continue }
                val duration = parseDuration(wRows.first().duration, durationInSeconds)
                val workout = WorkoutEntity(
                    name = key.second.ifBlank { "Workout" }, notes = wRows.firstOrNull { it.wNote.isNotBlank() }?.wNote ?: "",
                    startedAt = start, endedAt = start + duration * 1000L,
                )
                val exerciseOrder = wRows.map { it.exercise }.distinct()
                val sets = exerciseOrder.flatMapIndexed { exIndex, exName ->
                    val exId = idFor.getValue(exName)
                    val exRows = wRows.filter { it.exercise == exName }
                    val note = exRows.firstOrNull { it.note.isNotBlank() }?.note ?: ""
                    val session = exRows.map {
                        SessionSet(
                            key = Keys.next(), type = setType(it.order), weightKg = it.weight?.takeIf { w -> w != 0.0 },
                            reps = it.reps?.takeIf { r -> r != 0 }, seconds = it.sec?.takeIf { s -> s != 0 },
                            distanceKm = it.dist?.takeIf { d -> d != 0.0 }, done = true,
                        )
                    }
                    val marked = computePrMasks(session, tracking[exId] ?: TrackingType.WEIGHT_REPS, running[exId], running.containsKey(exId))
                    marked.mapIndexed { setIndex, s ->
                        SetEntity(
                            workoutId = 0, exerciseId = exId, exerciseIndex = exIndex, setIndex = setIndex, type = s.type,
                            weightKg = s.weightKg, reps = s.reps, seconds = s.seconds, distanceKm = s.distanceKm,
                            prMask = s.prMask, exerciseNote = note,
                        )
                    }
                }
                sets.groupBy { it.exerciseId }.forEach { (exId, exSets) ->
                    val all = history.getOrPut(exId) { mutableListOf() }
                    all += exSets
                    running[exId] = bestsOf(all)
                }
                repo.insertImported(workout, sets)
                imported++; setCount += sets.size
            }
        }
        return ImportResult(imported, setCount, newExercises, skipped)
    }

    private fun setType(order: String) = when (order.uppercase()) {
        "W" -> SetType.WARMUP
        "D" -> SetType.DROP
        "F" -> SetType.FAILURE
        else -> SetType.NORMAL
    }

    private fun parseDate(s: String): Long? {
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss")
        for (p in patterns) {
            runCatching {
                return LocalDateTime.parse(s, DateTimeFormatter.ofPattern(p)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    companion object {
        internal fun parseDuration(s: String, plainSeconds: Boolean): Long {
            if (plainSeconds) return s.toDoubleOrNull()?.toLong() ?: 0
            var total = 0L
            Regex("""(\d+)\s*([hms])""").findAll(s).forEach { m ->
                val n = m.groupValues[1].toLong()
                total += when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
            }
            return total
        }

        internal fun parseLine(line: String, delimiter: Char): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == delimiter && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            out += sb.toString()
            return out
        }

        fun guessEquipment(name: String, data: List<Triple<Double?, Double?, Pair<Int?, Int?>>>): Equipment {
            val paren = Regex("""\(([^)]*)\)""").findAll(name).lastOrNull()?.groupValues?.get(1)?.lowercase() ?: ""
            when {
                "barbell" in paren || "ez bar" in paren || "trap bar" in paren -> return Equipment.BARBELL
                "dumbbell" in paren -> return Equipment.DUMBBELL
                "machine" in paren -> return Equipment.MACHINE
                "cable" in paren -> return Equipment.CABLE
                "kettlebell" in paren -> return Equipment.KETTLEBELL
                "band" in paren -> return Equipment.BAND
                "assisted" in paren -> return Equipment.ASSISTED_BODYWEIGHT
                "weighted" in paren -> return Equipment.WEIGHTED_BODYWEIGHT
            }
            return when {
                data.any { (it.second ?: 0.0) > 0 } -> Equipment.CARDIO
                data.any { (it.first ?: 0.0) > 0 } -> Equipment.OTHER
                data.any { (it.third.first ?: 0) > 0 && (it.third.second ?: 0) == 0 } -> Equipment.DURATION
                else -> Equipment.REPS_ONLY
            }
        }

        fun guessBodyPart(name: String): BodyPart {
            val n = name.lowercase()
            // Order matters: the first group with a matching keyword wins.
            val table = listOf(
                BodyPart.CORE to listOf("plank", "crunch", "ab wheel", "abs", "sit up", "hollow", "l-sit", "core", "twist", "leg raise", "knee raise", "frog"),
                BodyPart.CHEST to listOf("bench", "chest", "fly", "push up", "pushup", "dip", "pec", "crossover"),
                BodyPart.BACK to listOf("row", "pull up", "pullup", "chin", "pulldown", "deadlift", "back", "shrug", "hang", "lat "),
                BodyPart.LEGS to listOf("squat", "lunge", "leg", "calf", "hip", "glute", "step", "split"),
                BodyPart.ARMS to listOf("curl", "tricep", "bicep", "skull", "pushdown", "wrist"),
                BodyPart.SHOULDERS to listOf("shoulder", "overhead", "press", "lateral", "raise", "handstand", "pike", "delt", "face pull"),
                BodyPart.CARDIO to listOf("run", "cycl", "bike", "rowing", "walk", "swim", "elliptical", "jump rope", "cardio"),
                BodyPart.OLYMPIC to listOf("snatch", "clean", "jerk"),
            )
            return table.firstOrNull { (_, keys) -> keys.any { it in n } }?.first ?: BodyPart.OTHER
        }
    }
}

@Serializable
data class Backup(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: AppSettings,
    val exercises: List<ExerciseEntity>,
    val workouts: List<WorkoutEntity>,
    val sets: List<SetEntity>,
    val templates: List<TemplateEntity>,
)

class BackupManager(private val repo: Repository) {
    suspend fun export(): String {
        val db = repo.database
        return AppJson.encodeToString(
            Backup(
                settings = repo.settings.current(),
                exercises = db.exercises().all(),
                workouts = db.workouts().all(),
                sets = db.workouts().allSets(),
                templates = db.templates().all(),
            )
        )
    }

    suspend fun restore(json: String): Backup {
        val backup = AppJson.decodeFromString<Backup>(json)
        val db = repo.database
        val m = db.maintenance()
        db.withTransaction {
            m.clearSets(); m.clearWorkouts(); m.clearTemplates(); m.clearExercises()
            m.insertExercises(backup.exercises)
            m.insertWorkouts(backup.workouts)
            m.insertSets(backup.sets)
            m.insertTemplates(backup.templates)
        }
        repo.settings.update { backup.settings }
        return backup
    }
}
