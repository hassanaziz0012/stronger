package app.stronger.data

/** Pure edits on a [Session], shared by the live workout, template editor and history editor. */

fun Session.updateExercise(key: Long, f: (SessionExercise) -> SessionExercise) =
    copy(exercises = exercises.map { if (it.key == key) f(it) else it })

fun Session.updateSet(exKey: Long, setKey: Long, f: (SessionSet) -> SessionSet) =
    updateExercise(exKey) { ex -> ex.copy(sets = ex.sets.map { if (it.key == setKey) f(it) else it }) }

fun Session.addExercises(ids: List<Long>, setCounts: (Long) -> Int = { 1 }) = copy(
    exercises = exercises + ids.map { id ->
        SessionExercise(
            key = Keys.next(),
            exerciseId = id,
            sets = List(setCounts(id).coerceIn(1, 10)) { SessionSet(key = Keys.next()) },
        )
    }
)

fun Session.removeExercise(key: Long): Session {
    val remaining = exercises.filterNot { it.key == key }
    return copy(exercises = remaining.dissolveLonelySupersets())
}

fun Session.moveExercise(key: Long, delta: Int): Session {
    val i = exercises.indexOfFirst { it.key == key }
    val j = i + delta
    if (i < 0 || j !in exercises.indices) return this
    val list = exercises.toMutableList()
    list.add(j, list.removeAt(i))
    return copy(exercises = list)
}

fun Session.replaceExercise(key: Long, newExerciseId: Long) =
    updateExercise(key) { it.copy(exerciseId = newExerciseId, sets = it.sets.map { s -> s.copy(prMask = 0) }) }

/** Links [a] and [b] into one superset, joining an existing group if either is already in one. */
fun Session.superset(a: Long, b: Long): Session {
    val ea = exercises.firstOrNull { it.key == a } ?: return this
    val eb = exercises.firstOrNull { it.key == b } ?: return this
    val id = ea.supersetId ?: eb.supersetId ?: ((exercises.mapNotNull { it.supersetId }.maxOrNull() ?: 0) + 1)
    val merged = setOfNotNull(ea.supersetId, eb.supersetId)
    val linked = exercises.map {
        if (it.key == a || it.key == b || (it.supersetId != null && it.supersetId in merged)) it.copy(supersetId = id) else it
    }
    // Keep superset members adjacent: move b's group right after a.
    val members = linked.filter { it.supersetId == id }
    val firstIndex = linked.indexOfFirst { it.supersetId == id }
    val others = linked.filterNot { it.supersetId == id }.toMutableList()
    val insertAt = linked.take(firstIndex).count { it.supersetId != id }
    others.addAll(insertAt, members)
    return copy(exercises = others)
}

fun Session.removeFromSuperset(key: Long) =
    copy(exercises = exercises.map { if (it.key == key) it.copy(supersetId = null) else it }.dissolveLonelySupersets())

private fun List<SessionExercise>.dissolveLonelySupersets(): List<SessionExercise> {
    val counts = mapNotNull { it.supersetId }.groupingBy { it }.eachCount()
    return map { if (it.supersetId != null && counts[it.supersetId] == 1) it.copy(supersetId = null) else it }
}

fun Session.addSet(exKey: Long) = updateExercise(exKey) { ex ->
    val last = ex.sets.lastOrNull()
    ex.copy(sets = ex.sets + SessionSet(key = Keys.next(), type = if (last?.type == SetType.WARMUP) SetType.WARMUP else SetType.NORMAL))
}

fun Session.removeSet(exKey: Long, setKey: Long) =
    updateExercise(exKey) { ex -> ex.copy(sets = ex.sets.filterNot { it.key == setKey }) }

/** A template keeps targets but never completion state. */
fun Session.asTemplateExercises() = exercises.map { ex ->
    ex.copy(sets = ex.sets.map { it.copy(done = false, prMask = 0) })
}

/** Starts a workout from template exercises with fresh keys. */
fun List<SessionExercise>.freshCopy() = map { ex ->
    ex.copy(key = Keys.next(), sets = ex.sets.map { it.copy(key = Keys.next(), done = false, prMask = 0) })
}

val Session.doneSetCount get() = exercises.sumOf { ex -> ex.sets.count { it.done } }

fun Session.volumeKg() = exercises.sumOf { ex ->
    ex.sets.filter { it.done && it.type != SetType.WARMUP }.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
}

/**
 * The matching set from last time: warm-ups pair with warm-ups and working sets with working
 * sets, so adding or deleting a warm-up doesn't shift the comparison.
 */
fun previousFor(ex: SessionExercise, index: Int, previous: List<SetEntity>?): SetEntity? {
    previous ?: return null
    val warm = ex.sets[index].type == SetType.WARMUP
    val ordinal = ex.sets.take(index).count { (it.type == SetType.WARMUP) == warm }
    return previous.filter { (it.type == SetType.WARMUP) == warm }.getOrNull(ordinal)
}

/**
 * What a blank set should default to: the same set from last time, else the set above it.
 */
fun hintFor(ex: SessionExercise, index: Int, previous: List<SetEntity>?): SessionSet? {
    previousFor(ex, index, previous)?.let {
        return SessionSet(key = 0, weightKg = it.weightKg, reps = it.reps, seconds = it.seconds, distanceKm = it.distanceKm)
    }
    return ex.sets.take(index).lastOrNull { it.weightKg != null || it.reps != null || it.seconds != null || it.distanceKm != null }
}

fun SessionSet.applyHint(hint: SessionSet?): SessionSet = if (hint == null) this else copy(
    weightKg = weightKg ?: hint.weightKg,
    reps = reps ?: hint.reps,
    seconds = seconds ?: hint.seconds,
    distanceKm = distanceKm ?: hint.distanceKm,
)

// ---- Records ----

data class Bests(
    val weight: Double = 0.0,
    val oneRm: Double = 0.0,
    val volume: Double = 0.0,
    val reps: Int = 0,
    val seconds: Int = 0,
    val distance: Double = 0.0,
)

private fun counts(type: SetType) = type != SetType.WARMUP

fun bestsOf(sets: List<SetEntity>): Bests {
    val working = sets.filter { counts(it.type) }
    return Bests(
        weight = working.filter { (it.reps ?: 0) > 0 }.maxOfOrNull { it.weightKg ?: 0.0 } ?: 0.0,
        oneRm = working.maxOfOrNull { it.oneRm } ?: 0.0,
        volume = working.maxOfOrNull { it.volumeKg } ?: 0.0,
        reps = working.maxOfOrNull { it.reps ?: 0 } ?: 0,
        seconds = working.maxOfOrNull { it.seconds ?: 0 } ?: 0,
        distance = working.maxOfOrNull { it.distanceKm ?: 0.0 } ?: 0.0,
    )
}

/**
 * Marks the single best done set per record type when it beats [previous]. With no history at
 * all there is nothing to beat, so a first-ever session sets no records.
 */
fun computePrMasks(sets: List<SessionSet>, tracking: TrackingType, previous: Bests?, hasHistory: Boolean): List<SessionSet> {
    val masks = mutableMapOf<Long, Int>()
    if (hasHistory && previous != null) {
        val candidates = sets.filter { it.done && counts(it.type) }
        fun mark(bit: Int, value: (SessionSet) -> Double, prev: Double) {
            val best = candidates.maxByOrNull(value) ?: return
            if (value(best) > prev + 1e-9) masks[best.key] = (masks[best.key] ?: 0) or bit
        }
        when (tracking) {
            TrackingType.WEIGHT_REPS -> {
                mark(Pr.WEIGHT, { if ((it.reps ?: 0) > 0) it.weightKg ?: 0.0 else 0.0 }, previous.weight)
                mark(Pr.ONE_RM, { estimatedOneRepMax(it.weightKg ?: 0.0, it.reps ?: 0) }, previous.oneRm)
                mark(Pr.VOLUME, { (it.weightKg ?: 0.0) * (it.reps ?: 0) }, previous.volume)
            }
            TrackingType.REPS -> mark(Pr.REPS, { (it.reps ?: 0).toDouble() }, previous.reps.toDouble())
            TrackingType.DURATION -> mark(Pr.DURATION, { (it.seconds ?: 0).toDouble() }, previous.seconds.toDouble())
            TrackingType.DISTANCE_DURATION -> mark(Pr.DISTANCE, { it.distanceKm ?: 0.0 }, previous.distance)
        }
    }
    return sets.map { it.copy(prMask = masks[it.key] ?: 0) }
}
