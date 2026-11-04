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
    ex.copy(sets = ex.sets.map { it.copy(done = false, prMask = 0, rir = null, doneAt = null, pauseReps = emptyList()) })
}

/** Starts a workout from template exercises with fresh keys. */
fun List<SessionExercise>.freshCopy() = map { ex ->
    ex.copy(key = Keys.next(), sets = ex.sets.map { it.copy(key = Keys.next(), done = false, prMask = 0, rir = null, doneAt = null, pauseReps = emptyList()) })
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

// ---- Targets ----

/**
 * A rep range to work in, and how much weight to add once every working set reaches the top of
 * it. Set per exercise by the user.
 */
data class RepTarget(val min: Int, val max: Int, val incrementKg: Double)

fun defaultIncrementKg(equipment: Equipment, unit: WeightUnit): Double = when (unit) {
    WeightUnit.LB -> 5.0 / WeightUnit.LB.perKg
    WeightUnit.KG -> if (equipment == Equipment.KETTLEBELL) 4.0 else 2.5
}

fun ExerciseEntity.repTarget(unit: WeightUnit): RepTarget? {
    if (tracking != TrackingType.WEIGHT_REPS && tracking != TrackingType.REPS) return null
    val min = repMin ?: return null
    val max = repMax ?: return null
    return RepTarget(min, max.coerceAtLeast(min), incrementKg ?: defaultIncrementKg(equipment, unit))
}

private fun SetType.isWorking() = this == SetType.NORMAL || this == SetType.FAILURE

/** True when every working set last time reached the top of the range, so the weight goes up. */
fun readyToProgress(previous: List<SetEntity>?, target: RepTarget): Boolean {
    val working = previous.orEmpty().filter { it.type.isWorking() }
    return working.isNotEmpty() && working.all { it.straightReps >= target.max }
}

/** Reported this many reps in reserve or more on every working set: it was easy, so progress faster. */
private const val EASY_RIR = 3

private fun List<SetEntity>.allEasy() = isNotEmpty() && all { (it.rir ?: -1) >= EASY_RIR }

/**
 * Double progression: keep the weight and add a rep each session until every working set reaches
 * the top of the range, then add [RepTarget.incrementKg] (take it off, for assisted exercises)
 * and start again from the bottom. Rest-paused reps count toward the next target but not toward
 * progressing, so the weight stays until the whole range is done without pausing. Warm-ups and drop sets have no target. When every working set
 * was reported as easy ([EASY_RIR]+ reps in reserve), the jump is doubled: two reps, or two
 * increments.
 */
fun targetFor(ex: SessionExercise, index: Int, previous: List<SetEntity>?, info: ExerciseEntity?, unit: WeightUnit): SessionSet? {
    val target = info?.repTarget(unit) ?: return null
    if (!ex.sets[index].type.isWorking()) return null
    val working = previous.orEmpty().filter { it.type.isWorking() }
    val prev = previousFor(ex, index, previous)?.takeIf { it.type.isWorking() } ?: working.lastOrNull() ?: return null
    val ready = readyToProgress(previous, target)
    val step = if (working.allEasy()) 2 else 1
    val reps = if (ready) target.min else ((prev.reps ?: 0) + step).coerceIn(target.min, target.max)
    return when (info.tracking) {
        TrackingType.WEIGHT_REPS -> {
            val w = prev.weightKg ?: return null
            val weight = when {
                !ready -> w
                info.equipment == Equipment.ASSISTED_BODYWEIGHT -> (w - step * target.incrementKg).coerceAtLeast(0.0)
                else -> w + step * target.incrementKg
            }
            SessionSet(key = 0, weightKg = weight, reps = reps)
        }
        // No weight to add, so bodyweight sets just climb to the top of the range.
        TrackingType.REPS -> SessionSet(key = 0, reps = if (ready) target.max else reps)
        else -> null
    }
}

/**
 * Blanks working sets that still hold the numbers a template started them with, for exercises
 * where [targeted] is true, so their targets show instead of stale template values.
 */
fun Session.clearForTargets(original: Session, targeted: (Long) -> Boolean): Session {
    val before = original.exercises.flatMap { it.sets }.associateBy { it.key }
    return copy(exercises = exercises.map { ex ->
        if (!targeted(ex.exerciseId)) return@map ex
        ex.copy(sets = ex.sets.map { set ->
            val o = before[set.key]
            if (set.done || !set.type.isWorking() || o == null || o.weightKg != set.weightKg || o.reps != set.reps) set
            else set.copy(weightKg = null, reps = null)
        })
    })
}

/** What a blank set suggests: its target if the exercise has one, else last time's values. */
fun suggestionFor(ex: SessionExercise, index: Int, previous: List<SetEntity>?, info: ExerciseEntity?, unit: WeightUnit): SessionSet? =
    targetFor(ex, index, previous, info, unit) ?: hintFor(ex, index, previous)

fun SessionSet.applyHint(hint: SessionSet?): SessionSet = if (hint == null) this else copy(
    weightKg = weightKg ?: hint.weightKg,
    reps = reps ?: hint.reps,
    seconds = seconds ?: hint.seconds,
    distanceKm = distanceKm ?: hint.distanceKm,
)

// ---- Effort ----

/** Effort is asked for exercises measured in reps; time and distance have no "reps left". */
fun tracksEffort(info: ExerciseEntity?) = info?.tracking == TrackingType.WEIGHT_REPS || info?.tracking == TrackingType.REPS

/**
 * A guess at reps in reserve, to suggest before the lifter answers: the reps your best est. 1RM
 * says you could do at this weight (inverse Epley), minus what you did. Bodyweight sets compare
 * against your best set's reps. Null when there's nothing to go on.
 */
fun estimateRir(set: SessionSet, info: ExerciseEntity?, bests: Bests?): Int? {
    val reps = set.reps?.takeIf { it > 0 } ?: return null
    if (bests == null || info == null) return null
    return when (info.tracking) {
        TrackingType.WEIGHT_REPS -> {
            if (info.equipment == Equipment.ASSISTED_BODYWEIGHT) return null
            val w = set.weightKg?.takeIf { it > 0 } ?: return null
            if (bests.oneRm <= 0) return null
            val possible = if (w >= bests.oneRm) 1.0 else 30 * (bests.oneRm / w - 1)
            Math.round(possible - reps).toInt().coerceIn(0, RIR_MAX)
        }
        TrackingType.REPS -> if (bests.reps <= 0) null else (bests.reps - reps).coerceIn(0, RIR_MAX)
        else -> null
    }
}

/** Est. 1RM counting the reps left in reserve, so an easy 8 rates above a grinding 8. */
fun effortOneRepMax(weightKg: Double, reps: Int, rir: Int?): Double = estimatedOneRepMax(weightKg, reps + (rir ?: 0))

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
        reps = working.maxOfOrNull { it.straightReps } ?: 0,
        seconds = working.maxOfOrNull { it.seconds ?: 0 } ?: 0,
        distance = working.maxOfOrNull { it.distanceKm ?: 0.0 } ?: 0.0,
    )
}

/**
 * Marks the single best done set per record type when it beats [previous]. Rep and 1RM records go
 * by reps before any rest-pause; volume counts them all. With no history at
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
                mark(Pr.ONE_RM, { estimatedOneRepMax(it.weightKg ?: 0.0, it.straightReps ?: 0) }, previous.oneRm)
                mark(Pr.VOLUME, { (it.weightKg ?: 0.0) * (it.reps ?: 0) }, previous.volume)
            }
            TrackingType.REPS -> mark(Pr.REPS, { (it.straightReps ?: 0).toDouble() }, previous.reps.toDouble())
            TrackingType.DURATION -> mark(Pr.DURATION, { (it.seconds ?: 0).toDouble() }, previous.seconds.toDouble())
            TrackingType.DISTANCE_DURATION -> mark(Pr.DISTANCE, { it.distanceKm ?: 0.0 }, previous.distance)
        }
    }
    return sets.map { it.copy(prMask = masks[it.key] ?: 0) }
}

/**
 * Recalculates the record trophies across one exercise's whole history, oldest first, so fixing
 * or deleting an old workout moves the trophies on later ones too. Returns only the sets whose
 * trophies changed.
 */
fun recomputePrMasks(rows: List<SetWithWorkout>, tracking: TrackingType): List<SetEntity> {
    val history = mutableListOf<SetEntity>()
    val changed = mutableListOf<SetEntity>()
    rows.groupBy { it.set.workoutId }.values
        .sortedWith(compareBy({ it.first().startedAt }, { it.first().set.workoutId }))
        .forEach { workout ->
            val bests = if (history.isEmpty()) null else bestsOf(history)
            workout.map { it.set }.groupBy { it.exerciseIndex }.toSortedMap().values.forEach { group ->
                val sets = group.sortedBy { it.setIndex }
                val session = sets.map {
                    SessionSet(
                        key = it.id, type = it.type, weightKg = it.weightKg, reps = it.reps, seconds = it.seconds, distanceKm = it.distanceKm,
                        done = true, pauseReps = it.pauseReps,
                    )
                }
                computePrMasks(session, tracking, bests, bests != null).zip(sets).forEach { (marked, s) ->
                    if (marked.prMask != s.prMask) changed += s.copy(prMask = marked.prMask)
                }
            }
            history += workout.map { it.set }
        }
    return changed
}

/** [this] raised by the done working sets of the workout in progress. */
private fun Bests.with(sets: List<SessionSet>): Bests {
    val working = sets.filter { it.done && counts(it.type) }
    return Bests(
        weight = maxOf(weight, working.filter { (it.reps ?: 0) > 0 }.maxOfOrNull { it.weightKg ?: 0.0 } ?: 0.0),
        oneRm = maxOf(oneRm, working.maxOfOrNull { estimatedOneRepMax(it.weightKg ?: 0.0, it.straightReps ?: 0) } ?: 0.0),
        volume = maxOf(volume, working.maxOfOrNull { (it.weightKg ?: 0.0) * (it.reps ?: 0) } ?: 0.0),
        reps = maxOf(reps, working.maxOfOrNull { it.straightReps ?: 0 } ?: 0),
        seconds = seconds, distance = distance,
    )
}

enum class RecordChance { NOW, ONE_MORE_REP }

/**
 * Whether an unticked set would set a record at the numbers it shows (what's typed, else its
 * target or last time's), or with one more rep. Measured against [bests] from before the workout
 * plus the sets already ticked today. Null when it wouldn't, or there's no history to beat.
 */
fun recordChance(ex: SessionExercise, index: Int, hint: SessionSet?, tracking: TrackingType, bests: Bests?): RecordChance? {
    val set = ex.sets[index]
    if (set.done || !counts(set.type) || bests == null) return null
    val b = bests.with(ex.sets)
    val reps = set.straightReps ?: hint?.reps ?: return null
    if (reps <= 0) return null
    val w = set.weightKg ?: hint?.weightKg ?: 0.0
    fun beats(r: Int) = when (tracking) {
        TrackingType.WEIGHT_REPS -> w > 0 && (w > b.weight + 1e-9 || estimatedOneRepMax(w, r) > b.oneRm + 1e-9 || w * r > b.volume + 1e-9)
        TrackingType.REPS -> r > b.reps
        else -> false
    }
    return when {
        beats(reps) -> RecordChance.NOW
        beats(reps + 1) -> RecordChance.ONE_MORE_REP
        else -> null
    }
}

// ---- Rest-pause ----

/** Whether a set's reps can be split by rest-pauses: rep-counted working sets. */
fun canRestPause(set: SessionSet, tracking: TrackingType) =
    set.type != SetType.WARMUP && (tracking == TrackingType.WEIGHT_REPS || tracking == TrackingType.REPS)

/**
 * The reps working sets aim for, to offer finishing a short one with a rest-pause: the most that
 * any of them suggests through [suggest]. A later set's own suggestion is often lower (last
 * time's tailed off, or there was no such set and it copies the one above), but you're still
 * going for the same reps.
 */
fun pauseGoal(ex: SessionExercise, suggest: (Int) -> SessionSet?): Int? =
    ex.sets.indices.filter { ex.sets[it].type.isWorking() }.mapNotNull { suggest(it)?.reps }.maxOrNull()

/** Whether a ticked set should offer finishing it with a rest-pause: a working set short of [goal]. */
fun offersPause(set: SessionSet, goal: Int?): Boolean =
    set.done && set.type.isWorking() && set.pauseReps.isEmpty() && goal != null && (set.reps ?: 0) in 1 until goal

/**
 * Adds a rest-pause after the set's last mini-set, starting at the reps still needed to reach
 * [goal] (what the set was aiming for), else one.
 */
fun SessionSet.addPause(goal: Int?): SessionSet {
    val done = reps ?: 0
    val next = goal?.let { it - done }?.takeIf { it > 0 } ?: 1
    return copy(reps = done + next, pauseReps = pauseReps + next)
}

/** Sets the [index]th rest-pause's reps, or removes it when [value] is below one. */
fun SessionSet.setPause(index: Int, value: Int): SessionSet {
    val old = pauseReps.getOrNull(index) ?: return this
    val pauses = if (value < 1) pauseReps.filterIndexed { i, _ -> i != index } else pauseReps.toMutableList().also { it[index] = value }
    return copy(reps = reps?.let { it - old + value.coerceAtLeast(0) }, pauseReps = pauses)
}

/** Sets the reps before the first rest-pause, keeping the ones after it. */
fun SessionSet.withStraightReps(value: Int?): SessionSet =
    copy(reps = value?.let { it + pauseReps.sum() })

/**
 * Done sets that finished, with no rest-pause, the reps that needed one last time at the same
 * weight or less: the goal of rest-pausing. Paired by the same set from last time.
 */
fun pauseFreeWins(ex: SessionExercise, previous: List<SetEntity>?): List<SessionSet> =
    ex.sets.filterIndexed { i, set ->
        if (!set.done || set.pauseReps.isNotEmpty() || set.type == SetType.WARMUP) return@filterIndexed false
        val prev = previousFor(ex, i, previous) ?: return@filterIndexed false
        prev.pauseReps.isNotEmpty() && (set.reps ?: 0) >= (prev.reps ?: 0) && (set.weightKg ?: 0.0) >= (prev.weightKg ?: 0.0) - 1e-9
    }

// ---- Typos ----

enum class SuspectField { WEIGHT, REPS, TIME, DISTANCE }

/** A ticked set whose [field] is far beyond your [best], likely a slip of the keyboard. */
data class Suspect(val exercise: SessionExercise, val set: SessionSet, val field: SuspectField, val best: Double)

/** With no history to compare against, only the absurd is flagged. */
private const val ABSURD_KG = 500.0
private const val ABSURD_REPS = 150

/**
 * Ticked sets that look like typos: an extra digit or a lost decimal point (225 kg for 22.5, 100
 * reps for 10). A real jump is rarely 75% over your best weight or 2.5× your best reps in one
 * session, so these get checked before a bad number becomes a record nothing can beat.
 */
fun suspiciousSets(session: Session, bests: Map<Long, Bests>, info: (Long) -> ExerciseEntity?): List<Suspect> =
    session.exercises.flatMap { ex ->
        val e = info(ex.exerciseId) ?: return@flatMap emptyList()
        val b = bests[ex.exerciseId]
        ex.sets.filter { it.done }.mapNotNull { s ->
            val weight = s.weightKg ?: 0.0
            val reps = s.reps ?: 0
            val seconds = s.seconds ?: 0
            val distance = s.distanceKm ?: 0.0
            fun far(value: Double, best: Double, ratio: Double, margin: Double) = best > 0 && value >= best * ratio && value - best >= margin
            when {
                e.tracking == TrackingType.WEIGHT_REPS && e.equipment != Equipment.ASSISTED_BODYWEIGHT &&
                    (if (b == null) weight > ABSURD_KG else far(weight, b.weight, 1.75, 10.0)) ->
                    Suspect(ex, s, SuspectField.WEIGHT, b?.weight ?: 0.0)
                (e.tracking == TrackingType.WEIGHT_REPS || e.tracking == TrackingType.REPS) &&
                    (if (b == null) reps > ABSURD_REPS else far(reps.toDouble(), b.reps.toDouble(), 2.5, 10.0)) ->
                    Suspect(ex, s, SuspectField.REPS, b?.reps?.toDouble() ?: 0.0)
                e.tracking == TrackingType.DURATION || e.tracking == TrackingType.DISTANCE_DURATION ->
                    when {
                        b != null && far(seconds.toDouble(), b.seconds.toDouble(), 3.0, 600.0) -> Suspect(ex, s, SuspectField.TIME, b.seconds.toDouble())
                        b != null && far(distance, b.distance, 3.0, 5.0) -> Suspect(ex, s, SuspectField.DISTANCE, b.distance)
                        else -> null
                    }
                else -> null
            }
        }
    }

// ---- Timing ----

/** Gap after the last set beyond which the workout counts as forgotten rather than still going. */
const val FORGOTTEN_AFTER_MS = 30 * 60_000L

/** When the last set was ticked, or the start if none has been. */
fun Session.lastActivityAt(): Long =
    exercises.flatMap { it.sets }.mapNotNull { it.doneAt }.maxOrNull()?.coerceAtLeast(startedAt) ?: startedAt

/**
 * When the workout really ended. Finishing long after the last set (the app was left open) ends it
 * one rest after that set instead, so duration and Health Connect aren't padded with the drive
 * home. A finish within [FORGOTTEN_AFTER_MS] keeps the current time, so a cooldown still counts.
 */
fun Session.realEnd(now: Long, restSeconds: (SessionExercise) -> Int): Long {
    var last: Pair<Long, SessionExercise>? = null
    exercises.forEach { ex ->
        ex.sets.forEach { s -> if (s.done && s.doneAt != null && s.doneAt > (last?.first ?: 0)) last = s.doneAt to ex }
    }
    val (at, ex) = last ?: return now
    if (now - at <= FORGOTTEN_AFTER_MS) return now
    return (at + restSeconds(ex).coerceIn(0, 300) * 1000L).coerceIn(startedAt, now)
}
