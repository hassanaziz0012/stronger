package app.stronger.data

import app.stronger.health.HealthSync
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

data class WorkoutSummary(
    val workoutId: Long,
    val name: String,
    val durationMillis: Long,
    val volumeKg: Double,
    val setCount: Int,
    val records: List<Pair<String, List<String>>>,
    val workoutNumber: Int,
)

class Repository(private val db: AppDatabase, val settings: SettingsStore, private val health: HealthSync) {
    private val exerciseDao = db.exercises()
    private val workoutDao = db.workouts()
    private val templateDao = db.templates()
    private val measurementDao = db.measurements()

    // ---- Exercises ----
    val exercises: Flow<List<ExerciseEntity>> = exerciseDao.observeAll()
    /** Including archived ones, for naming exercises in old workouts. */
    val exerciseIndex: Flow<Map<Long, ExerciseEntity>> = exerciseDao.observeEverything().map { list -> list.associateBy { it.id } }
    val exerciseUsage: Flow<List<ExerciseUsage>> = exerciseDao.observeUsage()
    fun exercise(id: Long) = exerciseDao.observe(id)
    suspend fun getExercise(id: Long) = exerciseDao.get(id)
    suspend fun allExercises() = exerciseDao.all()
    suspend fun createExercise(e: ExerciseEntity): Long = exerciseDao.insert(e.copy(id = 0, isCustom = true))
    suspend fun updateExercise(e: ExerciseEntity) = exerciseDao.update(e)
    suspend fun archiveExercise(id: Long) {
        exerciseDao.get(id)?.let { exerciseDao.update(it.copy(isArchived = true)) }
    }

    suspend fun seedIfNeeded() {
        if (settings.isSeeded()) return
        if (exerciseDao.count() == 0) exerciseDao.insertAll(SeedExercises)
        settings.markSeeded()
    }

    // ---- Templates ----
    val templates: Flow<List<TemplateEntity>> = templateDao.observeAll()
    suspend fun template(id: Long) = templateDao.get(id)

    fun templateExercises(t: TemplateEntity): List<SessionExercise> =
        runCatching { AppJson.decodeFromString<List<SessionExercise>>(t.exercisesJson) }.getOrDefault(emptyList())

    suspend fun saveTemplate(id: Long?, session: Session): Long {
        val json = AppJson.encodeToString(session.asTemplateExercises())
        val name = session.name.ifBlank { "New template" }
        val existing = id?.let { templateDao.get(it) }
        return if (existing != null) {
            templateDao.update(existing.copy(name = name, notes = session.notes, exercisesJson = json))
            existing.id
        } else {
            templateDao.insert(TemplateEntity(name = name, notes = session.notes, exercisesJson = json))
        }
    }

    suspend fun duplicateTemplate(id: Long) {
        val t = templateDao.get(id) ?: return
        templateDao.insert(t.copy(id = 0, name = "${t.name} (copy)", createdAt = System.currentTimeMillis(), lastUsedAt = null))
    }

    suspend fun deleteTemplate(id: Long) = templateDao.delete(id)
    suspend fun markTemplateUsed(id: Long) = templateDao.markUsed(id, System.currentTimeMillis())

    // ---- Workouts ----
    val workouts: Flow<List<WorkoutEntity>> = workoutDao.observeAll()
    val allSets: Flow<List<SetEntity>> = workoutDao.observeAllSets()
    fun workout(id: Long) = workoutDao.observe(id)
    fun workoutSets(id: Long) = workoutDao.observeSets(id)
    fun exerciseSets(exerciseId: Long) = workoutDao.observeExerciseSets(exerciseId)
    suspend fun deleteWorkout(id: Long) {
        val exerciseIds = workoutDao.sets(id).map { it.exerciseId }
        workoutDao.delete(id)
        health.workoutDeleted(id)
        refreshRecords(exerciseIds)
    }

    /**
     * Re-awards record trophies for [exerciseIds] across all history, after a workout was edited
     * or deleted: a fixed typo stops blocking the real records that came after it.
     */
    private suspend fun refreshRecords(exerciseIds: Collection<Long>) {
        val ids = exerciseIds.distinct()
        if (ids.isEmpty()) return
        val tracking = ids.associateWith { exerciseDao.get(it)?.tracking ?: TrackingType.WEIGHT_REPS }
        val changed = workoutDao.exerciseSets(ids).groupBy { it.set.exerciseId }
            .flatMap { (exId, rows) -> recomputePrMasks(rows, tracking.getValue(exId)) }
        if (changed.isNotEmpty()) workoutDao.updateSets(changed)
    }

    /** Workouts with their set counts, for estimating pace. */
    suspend fun workoutsWithSetCounts(): Pair<List<WorkoutEntity>, Map<Long, Int>> =
        workoutDao.all() to workoutDao.setCounts().associate { it.workoutId to it.sets }

    /** Sets from the most recent workout (started before [before]) that included each exercise. */
    suspend fun previousSets(exerciseIds: List<Long>, before: Long = Long.MAX_VALUE): Map<Long, List<SetEntity>> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return workoutDao.exerciseSets(exerciseIds.distinct())
            .filter { it.startedAt < before }
            .groupBy { it.set.exerciseId }
            .mapValues { (_, rows) ->
                val latest = rows.first().set.workoutId
                rows.filter { it.set.workoutId == latest }.map { it.set }.sortedBy { it.setIndex }
            }
    }

    /** Best values per exercise across workouts started before [before]; absent = no history. */
    suspend fun bests(exerciseIds: List<Long>, before: Long = Long.MAX_VALUE): Map<Long, Bests> {
        if (exerciseIds.isEmpty()) return emptyMap()
        return workoutDao.exerciseSets(exerciseIds.distinct())
            .filter { it.startedAt < before }
            .groupBy { it.set.exerciseId }
            .mapValues { (_, rows) -> bestsOf(rows.map { it.set }) }
    }

    fun sessionFromTemplate(t: TemplateEntity) = Session(
        name = t.name,
        notes = t.notes,
        templateId = t.id,
        exercises = templateExercises(t).freshCopy(),
    )

    suspend fun sessionFromWorkout(id: Long): Session? {
        val w = workoutDao.get(id) ?: return null
        val sets = workoutDao.sets(id)
        val exercises = sets.groupBy { it.exerciseIndex }.toSortedMap().values.map { group ->
            val first = group.first()
            SessionExercise(
                key = Keys.next(),
                exerciseId = first.exerciseId,
                note = first.exerciseNote,
                restSeconds = first.restSeconds,
                supersetId = first.supersetId,
                sets = group.sortedBy { it.setIndex }.map {
                    SessionSet(
                        key = Keys.next(), type = it.type, weightKg = it.weightKg, reps = it.reps,
                        seconds = it.seconds, distanceKm = it.distanceKm, done = true, prMask = it.prMask, rir = it.rir, pauseReps = it.pauseReps,
                        goal = it.goal, goalKg = it.goalKg,
                    )
                },
            )
        }
        return Session(name = w.name, notes = w.notes, startedAt = w.startedAt, templateId = w.templateId, exercises = exercises)
    }

    private suspend fun toSetEntities(session: Session, workoutId: Long): List<SetEntity> {
        val exercises = session.exercises.filter { ex -> ex.sets.any { it.done } }
        val exById = exercises.map { it.exerciseId }.distinct().associateWith { exerciseDao.get(it) }
        val bests = bests(exById.keys.toList(), before = session.startedAt)
        return exercises.flatMapIndexed { exIndex, ex ->
            val tracking = exById[ex.exerciseId]?.tracking ?: TrackingType.WEIGHT_REPS
            val done = ex.sets.filter { it.done }
            val marked = computePrMasks(done, tracking, bests[ex.exerciseId], bests.containsKey(ex.exerciseId))
            marked.mapIndexed { setIndex, s ->
                SetEntity(
                    workoutId = workoutId, exerciseId = ex.exerciseId, exerciseIndex = exIndex, setIndex = setIndex,
                    type = s.type, weightKg = s.weightKg, reps = s.reps, seconds = s.seconds, distanceKm = s.distanceKm,
                    prMask = s.prMask, supersetId = ex.supersetId, exerciseNote = ex.note, restSeconds = ex.restSeconds,
                    rir = s.rir, pauseReps = s.pauseReps, goal = s.goal, goalKg = s.goalKg,
                )
            }
        }
    }

    suspend fun finishWorkout(session: Session, endedAt: Long = System.currentTimeMillis()): WorkoutSummary {
        val workout = WorkoutEntity(
            name = session.name.ifBlank { "Workout" }, notes = session.notes,
            startedAt = session.startedAt, endedAt = endedAt, templateId = session.templateId,
        )
        val sets = toSetEntities(session, 0)
        val id = workoutDao.insertWithSets(workout, sets)
        health.workoutSaved(id)
        session.templateId?.let { markTemplateUsed(it) }
        val names = sets.map { it.exerciseId }.distinct().associateWith { exerciseDao.get(it)?.name ?: "Exercise" }
        val records = sets.filter { it.prMask != 0 }.groupBy { it.exerciseId }.map { (exId, rows) ->
            names.getValue(exId) to Pr.labels(rows.fold(0) { acc, s -> acc or s.prMask })
        }
        return WorkoutSummary(
            workoutId = id, name = workout.name, durationMillis = workout.durationMillis,
            volumeKg = sets.filter { it.type != SetType.WARMUP }.sumOf { it.volumeKg }, setCount = sets.size,
            records = records, workoutNumber = workoutDao.count(),
        )
    }

    suspend fun updateWorkout(id: Long, session: Session, endedAt: Long) {
        val w = workoutDao.get(id) ?: return
        val before = workoutDao.sets(id).map { it.exerciseId }
        val updated = w.copy(name = session.name.ifBlank { w.name }, notes = session.notes, startedAt = session.startedAt, endedAt = endedAt)
        workoutDao.replace(updated, toSetEntities(session, id))
        health.workoutSaved(id)
        refreshRecords(before + session.exercises.map { it.exerciseId })
    }

    // ---- Body measurements ----
    val measurements: Flow<List<MeasurementEntity>> = measurementDao.observeAll()

    suspend fun saveMeasurement(m: MeasurementEntity) {
        val saved = if (m.id == 0L) m.copy(id = measurementDao.insert(m)) else m.also { measurementDao.update(it) }
        health.measurementSaved(saved)
    }

    suspend fun deleteMeasurement(m: MeasurementEntity) {
        measurementDao.delete(m.id)
        health.measurementDeleted(m)
    }

    // ---- Import helpers ----
    suspend fun insertImported(workout: WorkoutEntity, sets: List<SetEntity>) = workoutDao.insertWithSets(workout, sets)
    suspend fun insertExercise(e: ExerciseEntity) = exerciseDao.insert(e)
    suspend fun reassignExercise(from: Long, to: Long) = workoutDao.reassignExercise(from, to)
    val database get() = db
}
