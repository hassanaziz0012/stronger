package app.stronger.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.kilograms
import androidx.health.connect.client.units.percent
import app.stronger.data.AppDatabase
import app.stronger.data.Equipment
import app.stronger.data.MeasureKind
import app.stronger.data.MeasurementEntity
import app.stronger.data.SettingsStore
import app.stronger.data.WorkoutEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A workout another app (a watch, a running app) recorded. */
data class ExternalSession(val title: String, val startedAt: Long, val minutes: Int)

/** The last week of activity and recovery from Health Connect; null fields weren't shared. */
data class HealthActivity(
    val cardio: List<ExternalSession>,
    val stepsToday: Long?,
    val stepsPerDay: Long?,
    val restingHeartRate: Long?,
    val sleepMinutesPerNight: Int?,
) {
    val cardioMinutes get() = cardio.sumOf { it.minutes }
}

data class SyncResult(val workouts: Int, val imported: Int)

/**
 * Two-way sync with Health Connect. Finished workouts and body measurements are written out
 * (keyed by our own ids, so edits replace and deletes remove them); weight and body fat from
 * scales and other apps come in; cardio, steps, resting heart rate and sleep are read for the
 * profile. Nothing here ever throws to the caller's UI flow: a failed sync just tries again later.
 */
class HealthSync(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    val available: Boolean get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    private val client by lazy { HealthConnectClient.getOrCreate(context) }
    private val zone get() = ZoneId.systemDefault()
    private val own get() = context.packageName

    val permissions: Set<String> by lazy {
        buildSet {
            listOf(ExerciseSessionRecord::class, WeightRecord::class, BodyFatRecord::class).forEach {
                add(HealthPermission.getReadPermission(it))
                add(HealthPermission.getWritePermission(it))
            }
            listOf(StepsRecord::class, RestingHeartRateRecord::class, SleepSessionRecord::class).forEach {
                add(HealthPermission.getReadPermission(it))
            }
            val history = runCatching {
                client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            }.getOrDefault(false)
            if (history) add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    private val _granted = MutableStateFlow<Set<String>>(emptySet())
    val granted: StateFlow<Set<String>> = _granted.asStateFlow()

    suspend fun refreshGranted(): Set<String> {
        if (!available) return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
            .also { _granted.value = it }
    }

    private suspend fun allowed(permission: String): Boolean =
        available && settings.current().healthConnect && permission in (_granted.value.ifEmpty { refreshGranted() })

    private suspend fun canWrite(type: KClass<out Record>) = allowed(HealthPermission.getWritePermission(type))
    private suspend fun canRead(type: KClass<out Record>) = allowed(HealthPermission.getReadPermission(type))

    private fun background(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    // ---- Workouts out ----

    fun workoutSaved(id: Long) = background {
        if (!canWrite(ExerciseSessionRecord::class)) return@background
        val w = db.workouts().get(id) ?: return@background
        client.insertRecords(listOf(sessionRecord(w)))
    }

    fun workoutDeleted(id: Long) = background {
        if (!canWrite(ExerciseSessionRecord::class)) return@background
        client.deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf(workoutKey(id)))
    }

    private fun workoutKey(id: Long) = "workout-$id"
    private fun measurementKey(id: Long) = "measurement-$id"

    private suspend fun sessionRecord(w: WorkoutEntity): ExerciseSessionRecord {
        val sets = db.workouts().sets(w.id)
        val exercises = sets.map { it.exerciseId }.distinct().mapNotNull { db.exercises().get(it) }
        val start = Instant.ofEpochMilli(w.startedAt)
        // Health Connect rejects sessions that end where they start.
        val end = Instant.ofEpochMilli(maxOf(w.endedAt, w.startedAt + 60_000))
        val summary = exercises.joinToString("\n") { e -> "${e.name}: ${sets.count { it.exerciseId == e.id }} sets" }
        return ExerciseSessionRecord(
            startTime = start, startZoneOffset = zone.rules.getOffset(start),
            endTime = end, endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(clientRecordId = workoutKey(w.id), clientRecordVersion = System.currentTimeMillis()),
            exerciseType = exerciseType(exercises.map { it.name to it.equipment }),
            title = w.name,
            notes = listOf(w.notes, summary).filter { it.isNotBlank() }.joinToString("\n\n").take(1000),
        )
    }

    // ---- Measurements out ----

    fun measurementSaved(m: MeasurementEntity) = background {
        if (m.hcId != null) return@background // Came from Health Connect; the source app owns it.
        val record = measurementRecord(m) ?: return@background
        if (!canWrite(record::class)) return@background
        client.insertRecords(listOf(record))
    }

    fun measurementDeleted(m: MeasurementEntity) = background {
        if (m.hcId != null) return@background
        val type = recordType(m.kind) ?: return@background
        if (!canWrite(type)) return@background
        client.deleteRecords(type, emptyList(), listOf(measurementKey(m.id)))
    }

    private fun recordType(kind: MeasureKind): KClass<out Record>? = when (kind) {
        MeasureKind.WEIGHT -> WeightRecord::class
        MeasureKind.BODY_FAT -> BodyFatRecord::class
        else -> null // Health Connect has no tape measurements.
    }

    private fun measurementRecord(m: MeasurementEntity): Record? {
        val time = Instant.ofEpochMilli(m.takenAt)
        val metadata = Metadata.manualEntry(clientRecordId = measurementKey(m.id), clientRecordVersion = System.currentTimeMillis())
        return when (m.kind) {
            MeasureKind.WEIGHT -> WeightRecord(time, zone.rules.getOffset(time), m.value.kilograms, metadata)
            MeasureKind.BODY_FAT -> BodyFatRecord(time, zone.rules.getOffset(time), m.value.percent, metadata)
            else -> null
        }
    }

    // ---- Full sync ----

    fun syncInBackground() = background { syncAll() }

    private var lastImport = 0L

    /** Picks up new scale readings when the app opens, at most every few minutes. */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastImport < 5 * 60_000) return
        lastImport = now
        background { refreshGranted(); importBody() }
    }

    /**
     * Writes every workout and measurement (replacing earlier copies), removes copies of ones
     * deleted here, then pulls in body measurements from other apps.
     */
    suspend fun syncAll(): SyncResult {
        var written = 0
        if (canWrite(ExerciseSessionRecord::class)) {
            val workouts = db.workouts().all()
            workouts.chunked(200).forEach { chunk -> client.insertRecords(chunk.map { sessionRecord(it) }); written += chunk.size }
            removeOrphans(ExerciseSessionRecord::class, workouts.map { workoutKey(it.id) }.toSet())
        }
        val mine = db.measurements().all().filter { it.hcId == null }
        for (type in listOf(WeightRecord::class, BodyFatRecord::class)) {
            if (!canWrite(type)) continue
            val records = mine.mapNotNull { measurementRecord(it) }.filter { it::class == type }
            records.chunked(500).forEach { client.insertRecords(it) }
            removeOrphans(type, mine.map { measurementKey(it.id) }.toSet())
        }
        return SyncResult(written, importBody())
    }

    private suspend fun <T : Record> removeOrphans(type: KClass<T>, keep: Set<String>) {
        val stale = readAll(type, TimeRangeFilter.after(Instant.EPOCH), setOf(DataOrigin(own)))
            .mapNotNull { it.metadata.clientRecordId }.filter { it !in keep }
        stale.chunked(500).forEach { client.deleteRecords(type, emptyList(), it) }
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter, origins: Set<DataOrigin> = emptySet()): List<T> {
        val out = mutableListOf<T>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, origins, pageToken = page))
            out += response.records
            page = response.pageToken
        } while (page != null)
        return out
    }

    // ---- Body measurements in ----

    /**
     * Brings in weight and body fat logged by other apps (smart scales, Fit). The first run reads
     * everything; after that only changes, including deletions, using a changes token.
     */
    suspend fun importBody(): Int {
        val types = listOf(WeightRecord::class, BodyFatRecord::class).filter { canRead(it) }
        if (types.isEmpty()) return 0
        val dao = db.measurements()
        var count = 0
        suspend fun upsert(r: Record) {
            if (r.metadata.dataOrigin.packageName == own) return
            val m = when (r) {
                is WeightRecord -> MeasurementEntity(kind = MeasureKind.WEIGHT, takenAt = r.time.toEpochMilli(), value = r.weight.inKilograms, hcId = r.metadata.id)
                is BodyFatRecord -> MeasurementEntity(kind = MeasureKind.BODY_FAT, takenAt = r.time.toEpochMilli(), value = r.percentage.value, hcId = r.metadata.id)
                else -> return
            }
            val existing = dao.byHcId(r.metadata.id)
            if (existing == null) { dao.insert(m); count++ } else dao.update(m.copy(id = existing.id))
        }

        var token = settings.healthToken()
        if (token != null) {
            while (true) {
                val response = client.getChanges(token!!)
                if (response.changesTokenExpired) { token = null; break }
                response.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> upsert(change.record)
                        is DeletionChange -> dao.deleteByHcId(change.recordId)
                    }
                }
                token = response.nextChangesToken
                if (!response.hasMore) break
            }
        }
        if (token == null) {
            // Take the token first so nothing written during the full read is missed.
            token = client.getChangesToken(ChangesTokenRequest(types.toSet()))
            types.forEach { type -> readAll(type, TimeRangeFilter.after(Instant.EPOCH)).forEach { upsert(it) } }
        }
        settings.setHealthToken(token)
        return count
    }

    // ---- Activity and recovery ----

    suspend fun activity(now: Instant = Instant.now()): HealthActivity? {
        if (!available || !settings.current().healthConnect) return null
        refreshGranted()
        val weekAgo = now.minusSeconds(7 * 86_400L)
        val cardio = if (canRead(ExerciseSessionRecord::class)) runCatching {
            readAll(ExerciseSessionRecord::class, TimeRangeFilter.between(weekAgo, now))
                .filter { it.metadata.dataOrigin.packageName != own && it.exerciseType !in nonCardio }
                .map { ExternalSession(it.title ?: typeLabel(it.exerciseType), it.startTime.toEpochMilli(), ((it.endTime.epochSecond - it.startTime.epochSecond) / 60).toInt()) }
                .sortedByDescending { it.startedAt }
        }.getOrDefault(emptyList()) else emptyList()

        val today = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val steps = if (canRead(StepsRecord::class)) runCatching {
            val week = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today.minusSeconds(7 * 86_400L), today)))
            val todays = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(today, now)))
            todays[StepsRecord.COUNT_TOTAL] to week[StepsRecord.COUNT_TOTAL]?.div(7)
        }.getOrNull() else null

        val heart = if (canRead(RestingHeartRateRecord::class)) runCatching {
            client.aggregate(AggregateRequest(setOf(RestingHeartRateRecord.BPM_AVG), TimeRangeFilter.between(weekAgo, now)))[RestingHeartRateRecord.BPM_AVG]
        }.getOrNull() else null

        val sleep = if (canRead(SleepSessionRecord::class)) runCatching {
            val nights = readAll(SleepSessionRecord::class, TimeRangeFilter.between(weekAgo, now))
            if (nights.isEmpty()) null
            else (nights.sumOf { it.endTime.epochSecond - it.startTime.epochSecond } / 60 / nights.size).toInt()
        }.getOrNull() else null

        return HealthActivity(cardio, steps?.first, steps?.second, heart, sleep)
    }

    companion object {
        private val nonCardio = setOf(
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING,
        )

        private val bodyweight = setOf(
            Equipment.REPS_ONLY, Equipment.DURATION, Equipment.WEIGHTED_BODYWEIGHT, Equipment.ASSISTED_BODYWEIGHT,
        )

        /** Strength by default; calisthenics when it's all bodyweight; a cardio type for a lone cardio exercise. */
        internal fun exerciseType(exercises: List<Pair<String, Equipment>>): Int {
            val (name, equipment) = exercises.singleOrNull() ?: ("" to null)
            if (equipment == Equipment.CARDIO) {
                val n = name.lowercase()
                return when {
                    "treadmill" in n -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
                    "run" in n -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                    "indoor" in n || "stationary" in n -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                    "cycl" in n || "bike" in n -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                    "row" in n -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
                    "elliptical" in n -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
                    "stair" in n -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
                    "walk" in n -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    "swim" in n -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
                    "hik" in n -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
                    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                }
            }
            return if (exercises.isNotEmpty() && exercises.all { it.second in bodyweight }) ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
            else ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        }

        fun typeLabel(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Ride"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Row"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stairs"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
            ExerciseSessionRecord.EXERCISE_TYPE_BOXING, ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Combat"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
            ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Sport"
            else -> "Workout"
        }
    }
}
