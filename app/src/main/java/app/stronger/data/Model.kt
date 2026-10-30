package app.stronger.data

import kotlinx.serialization.Serializable

enum class BodyPart(val label: String) {
    CHEST("Chest"),
    BACK("Back"),
    SHOULDERS("Shoulders"),
    ARMS("Arms"),
    LEGS("Legs"),
    CORE("Core"),
    FULL_BODY("Full Body"),
    CARDIO("Cardio"),
    OLYMPIC("Olympic"),
    OTHER("Other"),
}

/** What a set of this exercise records. */
enum class TrackingType { WEIGHT_REPS, REPS, DURATION, DISTANCE_DURATION }

enum class Equipment(val label: String, val tracking: TrackingType) {
    BARBELL("Barbell", TrackingType.WEIGHT_REPS),
    DUMBBELL("Dumbbell", TrackingType.WEIGHT_REPS),
    MACHINE("Machine", TrackingType.WEIGHT_REPS),
    CABLE("Cable", TrackingType.WEIGHT_REPS),
    KETTLEBELL("Kettlebell", TrackingType.WEIGHT_REPS),
    BAND("Band", TrackingType.WEIGHT_REPS),
    WEIGHTED_BODYWEIGHT("Weighted bodyweight", TrackingType.WEIGHT_REPS),
    ASSISTED_BODYWEIGHT("Assisted bodyweight", TrackingType.WEIGHT_REPS),
    REPS_ONLY("Reps only", TrackingType.REPS),
    DURATION("Duration", TrackingType.DURATION),
    CARDIO("Cardio", TrackingType.DISTANCE_DURATION),
    OTHER("Other", TrackingType.WEIGHT_REPS),
}

@Serializable
enum class SetType(val short: String, val label: String) {
    NORMAL("", "Normal"),
    WARMUP("W", "Warm-up"),
    DROP("D", "Drop set"),
    FAILURE("F", "Failure"),
}

enum class WeightUnit(val label: String, val perKg: Double) {
    KG("kg", 1.0),
    LB("lbs", 2.2046226218),
}

/** Body measurements. Weight is stored in kg, body fat in %, and lengths in cm. */
enum class MeasureKind(val label: String, val isLength: Boolean = false) {
    WEIGHT("Body weight"),
    BODY_FAT("Body fat"),
    WAIST("Waist", true),
    CHEST("Chest", true),
    SHOULDERS("Shoulders", true),
    ARMS("Arms", true),
    THIGHS("Thighs", true),
    CALVES("Calves", true),
    HIPS("Hips", true),
    NECK("Neck", true),
}

/** Personal-record kinds, stored as a bitmask on each logged set. */
object Pr {
    const val WEIGHT = 1
    const val ONE_RM = 2
    const val VOLUME = 4
    const val REPS = 8
    const val DURATION = 16
    const val DISTANCE = 32

    fun labels(mask: Int): List<String> = buildList {
        if (mask and ONE_RM != 0) add("Est. 1RM")
        if (mask and WEIGHT != 0) add("Weight")
        if (mask and VOLUME != 0) add("Volume")
        if (mask and REPS != 0) add("Reps")
        if (mask and DURATION != 0) add("Duration")
        if (mask and DISTANCE != 0) add("Distance")
    }
}

/** RIR at or above this is recorded and shown as "4+". */
const val RIR_MAX = 4

fun rirLabel(rir: Int) = if (rir >= RIR_MAX) "$RIR_MAX+ RIR" else "$rir RIR"

/** Epley estimate, the same formula most lifting apps show. Single reps are their own max. */
fun estimatedOneRepMax(weightKg: Double, reps: Int): Double = when {
    reps <= 0 || weightKg <= 0 -> 0.0
    reps == 1 -> weightKg
    else -> weightKg * (1 + reps / 30.0)
}

// ---- Session: the editable shape shared by active workouts, templates and history edits ----

@Serializable
data class Session(
    val name: String,
    val notes: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val templateId: Long? = null,
    val exercises: List<SessionExercise> = emptyList(),
    val restEndsAt: Long? = null,
    val restTotalSeconds: Int = 0,
)

@Serializable
data class SessionExercise(
    val key: Long,
    val exerciseId: Long,
    val note: String = "",
    val restSeconds: Int? = null,
    val supersetId: Int? = null,
    val sets: List<SessionSet> = emptyList(),
)

@Serializable
data class SessionSet(
    val key: Long,
    val type: SetType = SetType.NORMAL,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceKm: Double? = null,
    val done: Boolean = false,
    val prMask: Int = 0,
    /** Reps in reserve the lifter reported; 4 means "4 or more". Null when not given. */
    val rir: Int? = null,
    /** When the set was ticked in a live workout. */
    val doneAt: Long? = null,
)

object Keys {
    private var last = 0L
    @Synchronized
    fun next(): Long {
        val now = System.nanoTime()
        last = if (now > last) now else last + 1
        return last
    }
}
