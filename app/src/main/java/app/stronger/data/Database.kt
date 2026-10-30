package app.stronger.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "exercises", indices = [Index("name")])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val bodyPart: BodyPart,
    val equipment: Equipment,
    val notes: String = "",
    val isCustom: Boolean = false,
    val isArchived: Boolean = false,
    val restSeconds: Int? = null,
    /** Rep range for progression targets; both null when the exercise has none. */
    val repMin: Int? = null,
    val repMax: Int? = null,
    /** Weight added once every working set reaches [repMax]; null uses the equipment default. */
    val incrementKg: Double? = null,
) {
    val tracking: TrackingType get() = equipment.tracking
}

@Serializable
@Entity(tableName = "workouts", indices = [Index("startedAt")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val startedAt: Long,
    val endedAt: Long,
    val templateId: Long? = null,
) {
    val durationMillis: Long get() = (endedAt - startedAt).coerceAtLeast(0)
}

@Serializable
@Entity(
    tableName = "sets",
    foreignKeys = [
        ForeignKey(WorkoutEntity::class, ["id"], ["workoutId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("workoutId"), Index("exerciseId")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: Long,
    val exerciseIndex: Int,
    val setIndex: Int,
    val type: SetType = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceKm: Double? = null,
    val prMask: Int = 0,
    val supersetId: Int? = null,
    val exerciseNote: String = "",
    val restSeconds: Int? = null,
    /** Reps in reserve, when the lifter gave it. */
    val rir: Int? = null,
) {
    val volumeKg: Double get() = (weightKg ?: 0.0) * (reps ?: 0)
    val oneRm: Double get() = estimatedOneRepMax(weightKg ?: 0.0, reps ?: 0)
}

@Serializable
@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    /** JSON-encoded list of [SessionExercise]. */
    val exercisesJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long? = null,
    val position: Int = 0,
)

@Serializable
@Entity(tableName = "measurements", indices = [Index("kind", "takenAt"), Index("hcId", unique = true)])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: MeasureKind,
    val takenAt: Long,
    /** In the kind's base unit: kg, % or cm. */
    val value: Double,
    /** Health Connect record id when this came from another app. */
    val hcId: String? = null,
)

data class SetWithWorkout(
    @Embedded val set: SetEntity,
    val startedAt: Long,
    val workoutName: String,
)

data class ExerciseUsage(val exerciseId: Long, val workouts: Int, val lastAt: Long)

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE isArchived = 0 ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises")
    suspend fun all(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises")
    fun observeEverything(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun observe(id: Long): Flow<ExerciseEntity?>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun get(id: Long): ExerciseEntity?

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Insert suspend fun insert(e: ExerciseEntity): Long
    @Insert suspend fun insertAll(e: List<ExerciseEntity>): List<Long>
    @Update suspend fun update(e: ExerciseEntity)

    @Query(
        """SELECT s.exerciseId AS exerciseId, COUNT(DISTINCT s.workoutId) AS workouts, MAX(w.startedAt) AS lastAt
           FROM sets s JOIN workouts w ON w.id = s.workoutId GROUP BY s.exerciseId"""
    )
    fun observeUsage(): Flow<List<ExerciseUsage>>
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    suspend fun all(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observe(id: Long): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: Long): WorkoutEntity?

    @Query("SELECT COUNT(*) FROM workouts")
    suspend fun count(): Int

    @Query("SELECT * FROM sets ORDER BY workoutId, exerciseIndex, setIndex")
    fun observeAllSets(): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets")
    suspend fun allSets(): List<SetEntity>

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId ORDER BY exerciseIndex, setIndex")
    fun observeSets(workoutId: Long): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE workoutId = :workoutId ORDER BY exerciseIndex, setIndex")
    suspend fun sets(workoutId: Long): List<SetEntity>

    @Query(
        """SELECT s.*, w.startedAt AS startedAt, w.name AS workoutName FROM sets s
           JOIN workouts w ON w.id = s.workoutId WHERE s.exerciseId = :exerciseId
           ORDER BY w.startedAt DESC, s.setIndex"""
    )
    fun observeExerciseSets(exerciseId: Long): Flow<List<SetWithWorkout>>

    @Query(
        """SELECT s.*, w.startedAt AS startedAt, w.name AS workoutName FROM sets s
           JOIN workouts w ON w.id = s.workoutId WHERE s.exerciseId IN (:exerciseIds)
           ORDER BY w.startedAt DESC, s.setIndex"""
    )
    suspend fun exerciseSets(exerciseIds: List<Long>): List<SetWithWorkout>

    @Insert suspend fun insert(w: WorkoutEntity): Long
    @Insert suspend fun insertSets(s: List<SetEntity>)
    @Update suspend fun update(w: WorkoutEntity)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sets WHERE workoutId = :workoutId")
    suspend fun deleteSets(workoutId: Long)

    @Query("UPDATE sets SET exerciseId = :to WHERE exerciseId = :from")
    suspend fun reassignExercise(from: Long, to: Long)

    @Transaction
    suspend fun replace(w: WorkoutEntity, sets: List<SetEntity>) {
        update(w)
        deleteSets(w.id)
        insertSets(sets.map { it.copy(id = 0, workoutId = w.id) })
    }

    @Transaction
    suspend fun insertWithSets(w: WorkoutEntity, sets: List<SetEntity>): Long {
        val id = insert(w)
        insertSets(sets.map { it.copy(id = 0, workoutId = id) })
        return id
    }
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY position, createdAt DESC")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Query("SELECT * FROM templates WHERE id = :id")
    suspend fun get(id: Long): TemplateEntity?

    @Insert suspend fun insert(t: TemplateEntity): Long
    @Update suspend fun update(t: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE templates SET lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: Long, at: Long)
}

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY takenAt DESC")
    fun observeAll(): Flow<List<MeasurementEntity>>

    @Query("SELECT * FROM measurements")
    suspend fun all(): List<MeasurementEntity>

    @Query("SELECT * FROM measurements WHERE id = :id")
    suspend fun get(id: Long): MeasurementEntity?

    @Insert suspend fun insert(m: MeasurementEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnoring(m: List<MeasurementEntity>): List<Long>
    @Update suspend fun update(m: MeasurementEntity)

    @Query("SELECT * FROM measurements WHERE hcId = :hcId")
    suspend fun byHcId(hcId: String): MeasurementEntity?

    @Query("DELETE FROM measurements WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM measurements WHERE hcId = :hcId")
    suspend fun deleteByHcId(hcId: String)
}

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM sets") suspend fun clearSets()
    @Query("DELETE FROM workouts") suspend fun clearWorkouts()
    @Query("DELETE FROM templates") suspend fun clearTemplates()
    @Query("DELETE FROM exercises") suspend fun clearExercises()
    @Query("DELETE FROM measurements") suspend fun clearMeasurements()
    @Insert suspend fun insertExercises(e: List<ExerciseEntity>)
    @Insert suspend fun insertWorkouts(w: List<WorkoutEntity>)
    @Insert suspend fun insertSets(s: List<SetEntity>)
    @Insert suspend fun insertTemplates(t: List<TemplateEntity>)
    @Insert suspend fun insertMeasurements(m: List<MeasurementEntity>)
}

@Database(
    entities = [ExerciseEntity::class, WorkoutEntity::class, SetEntity::class, TemplateEntity::class, MeasurementEntity::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exercises(): ExerciseDao
    abstract fun workouts(): WorkoutDao
    abstract fun templates(): TemplateDao
    abstract fun measurements(): MeasurementDao
    abstract fun maintenance(): MaintenanceDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "stronger.db").build()
    }
}
