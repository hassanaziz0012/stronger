package app.stronger.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.exp

/**
 * Pure analysis of training history: plateaus, deloads, body-weight trends, timing, the next
 * workout, weekly-goal nudges and monthly recaps.
 */

private const val DAY = 86_400_000L

enum class Trend { PROGRESSING, STALLED, REGRESSING }

/**
 * Where an exercise stands. [sessionsSinceBest] counts sessions after the most recent new best
 * within the recent window.
 */
data class ProgressStatus(val trend: Trend, val sessionsSinceBest: Int, val best: Double, val bestAt: Long)

/**
 * How well a session went: best est. 1RM, or most reps or seconds when there's no weight. Reps in
 * reserve count as reps, so the same weight feeling harder shows up as a drop before it shows in
 * the numbers.
 */
fun sessionScore(sets: List<SetEntity>, tracking: TrackingType): Double {
    val working = sets.filter { it.type != SetType.WARMUP }
    return when (tracking) {
        TrackingType.WEIGHT_REPS -> working.maxOfOrNull { effortOneRepMax(it.weightKg ?: 0.0, it.straightReps, it.rir) } ?: 0.0
        TrackingType.REPS -> working.maxOfOrNull { (it.straightReps + (it.rir ?: 0)).toDouble() } ?: 0.0
        TrackingType.DURATION -> working.maxOfOrNull { (it.seconds ?: 0).toDouble() } ?: 0.0
        TrackingType.DISTANCE_DURATION -> 0.0
    }
}

/**
 * Looks at the last [windowDays] of (time, score) sessions, oldest first. Stalled means no new best
 * in [stallAfter] or more sessions; regressing means the last two sessions were both clearly
 * (5%+) below that best. Null when there's too little recent data to judge, or the exercise
 * hasn't been done lately.
 */
fun progressStatus(
    sessions: List<Pair<Long, Double>>,
    now: Long = System.currentTimeMillis(),
    windowDays: Int = 70,
    stallAfter: Int = 3,
): ProgressStatus? {
    val recent = sessions.filter { it.first >= now - windowDays * DAY && it.second > 0 }.sortedBy { it.first }
    if (recent.size < stallAfter + 1) return null
    if (recent.last().first < now - 21 * DAY) return null
    var bestIndex = 0
    recent.forEachIndexed { i, s -> if (s.second > recent[bestIndex].second * 1.001) bestIndex = i }
    val since = recent.lastIndex - bestIndex
    val best = recent[bestIndex].second
    val trend = when {
        since >= 2 && recent.takeLast(2).all { it.second < best * 0.95 } -> Trend.REGRESSING
        since >= stallAfter -> Trend.STALLED
        else -> Trend.PROGRESSING
    }
    return ProgressStatus(trend, since, best, recent[bestIndex].first)
}

/** Status for every exercise that has enough recent history to judge. */
fun progressByExercise(
    sets: List<SetEntity>,
    startedAt: Map<Long, Long>,
    tracking: Map<Long, TrackingType>,
    now: Long = System.currentTimeMillis(),
): Map<Long, ProgressStatus> =
    sets.groupBy { it.exerciseId }.mapNotNull { (exId, exSets) ->
        val t = tracking[exId] ?: return@mapNotNull null
        val sessions = exSets.groupBy { it.workoutId }.mapNotNull { (wid, s) -> startedAt[wid]?.let { it to sessionScore(s, t) } }
        progressStatus(sessions, now)?.let { exId to it }
    }.toMap()

/**
 * A deload is worth suggesting when several lifts have stopped moving at once: that points at
 * accumulated fatigue rather than one exercise needing a change.
 */
fun deloadSuggested(statuses: Collection<ProgressStatus>): Boolean {
    val regressing = statuses.count { it.trend == Trend.REGRESSING }
    val stuck = statuses.count { it.trend != Trend.PROGRESSING }
    return regressing >= 2 || stuck >= 3
}

/**
 * Exponentially smoothed weight trend (time-aware, about a 10-day half-life), so a salty dinner
 * doesn't look like a kilo gained. Input is (time, value) oldest first; output pairs each entry
 * with the trend at that point.
 */
fun smoothedTrend(points: List<Pair<Long, Double>>, halfLifeDays: Double = 10.0): List<Pair<Long, Double>> {
    if (points.isEmpty()) return emptyList()
    val sorted = points.sortedBy { it.first }
    var trend = sorted.first().second
    var last = sorted.first().first
    return sorted.map { (t, v) ->
        val days = (t - last).coerceAtLeast(0) / DAY.toDouble()
        val keep = exp(-days * kotlin.math.ln(2.0) / halfLifeDays).coerceAtMost(0.9)
        trend = trend * keep + v * (1 - keep)
        last = t
        t to trend
    }
}

/** Trend change over the last [days], or null without data on both ends. */
fun trendChange(trend: List<Pair<Long, Double>>, days: Int, now: Long = System.currentTimeMillis()): Double? {
    if (trend.size < 2) return null
    val start = trend.lastOrNull { it.first <= now - days * DAY } ?: return null
    return trend.last().second - start.second
}

// ---- Timing ----

private fun <T : Comparable<T>> List<T>.median(): T? = sorted().getOrNull(size / 2)

/** Durations outside this range are forgotten timers or quick logs, not real sessions. */
private fun WorkoutEntity.plausible() = durationMillis in 10 * 60_000L..4 * 3_600_000L

/** Done from template [id], or (for imported history, which has no template link) one with its name. */
fun WorkoutEntity.isFrom(id: Long, name: String) = templateId == id || (templateId == null && this.name.equals(name, ignoreCase = true))

/** How long a template usually takes: the median of its last 5 sessions. */
fun typicalDuration(workouts: List<WorkoutEntity>, templateId: Long, templateName: String): Long? =
    workouts.filter { it.isFrom(templateId, templateName) && it.plausible() }
        .sortedByDescending { it.startedAt }.take(5).map { it.durationMillis }.median()

/**
 * Your pace in milliseconds per set, from the same template's recent sessions when there are at
 * least 2, else from your last 20 workouts. Null without history.
 */
fun millisPerSet(workouts: List<WorkoutEntity>, setCounts: Map<Long, Int>, templateId: Long?, templateName: String): Long? {
    val usable = workouts.filter { it.plausible() && (setCounts[it.id] ?: 0) >= 3 }.sortedByDescending { it.startedAt }
    val same = templateId?.let { id -> usable.filter { it.isFrom(id, templateName) }.take(10) }.orEmpty()
    val pool = if (same.size >= 2) same else usable.take(20)
    return pool.map { it.durationMillis / setCounts.getValue(it.id) }.median()
}

/** When a workout in progress should be done, at your usual pace for its remaining sets. */
fun Session.estimatedEnd(millisPerSet: Long?, now: Long): Long? {
    val pace = millisPerSet ?: return null
    val left = exercises.sumOf { ex -> ex.sets.count { !it.done } }
    return if (left == 0) null else now + left * pace
}

// ---- Next workout ----

/**
 * The template you're most likely to do next. Your rotation is the templates done at least twice
 * in your last 12 template workouts and at least once in the last 6 (so one-offs and dropped
 * templates fall out); of those, the one done longest ago is next. Null without a rotation of two
 * or more, or after 8 weeks without one.
 */
fun nextTemplate(templates: List<TemplateEntity>, workouts: List<WorkoutEntity>, now: Long = System.currentTimeMillis()): Long? {
    val recent = workouts.filter { it.startedAt >= now - 56 * DAY }.sortedByDescending { it.startedAt }
    val sequence = recent.mapNotNull { w -> templates.firstOrNull { w.isFrom(it.id, it.name) }?.let { it.id to w.startedAt } }.take(12)
    val lastSix = sequence.take(6).map { it.first }.toSet()
    val rotation = sequence.groupBy { it.first }
        .filter { (id, uses) -> uses.size >= 2 && id in lastSix }
        .mapValues { (_, uses) -> uses.maxOf { it.second } }
    if (rotation.size < 2) return null
    return rotation.minBy { it.value }.key
}

// ---- Weekly goal ----

/**
 * A reminder for today, only when skipping today would cost something: the weekly goal now needs
 * a workout every remaining day, or this is the last day to keep the week streak alive. Null when
 * you've already trained today or there's nothing left to save.
 */
fun goalNudge(days: List<LocalDate>, goal: Int, streak: Int, today: LocalDate, firstDay: DayOfWeek): String? {
    if (today in days) return null
    val start = today.with(TemporalAdjusters.previousOrSame(firstDay))
    val count = days.count { it >= start && it <= today }
    val daysLeft = 7 - ChronoUnit.DAYS.between(start, today).toInt()
    val needed = goal - count
    return when {
        needed > 0 && needed == daysLeft && daysLeft == 1 ->
            "Last day of the week: one more workout hits your goal of $goal."
        needed > 0 && needed == daysLeft ->
            "$needed more workouts and $daysLeft days left: train today to hit your goal of $goal."
        count == 0 && daysLeft == 1 && streak > 0 ->
            "Train today to keep your $streak-week streak going."
        else -> null
    }
}

/**
 * When you usually start training, in minutes after midnight: the median start of your workouts
 * in the last 60 days, or null with fewer than 3.
 */
fun usualStartMinute(startTimes: List<Long>, zone: ZoneId, now: Long = System.currentTimeMillis()): Int? {
    val recent = startTimes.filter { it >= now - 60 * DAY }
    if (recent.size < 3) return null
    return recent.map { Instant.ofEpochMilli(it).atZone(zone).let { t -> t.hour * 60 + t.minute } }.median()
}

// ---- Monthly recap ----

data class MonthRecap(
    val month: YearMonth,
    val workouts: Int,
    val previousWorkouts: Int,
    val durationMillis: Long,
    val volumeKg: Double,
    val records: Int,
    /** Exercise id to est. 1RM gain in kg over its best in the 3 months before, biggest first. */
    val gains: List<Pair<Long, Double>>,
    /** Change in smoothed body weight over the month, in kg. */
    val bodyWeightChange: Double?,
)

/** A summary of [month], or null if you didn't train in it. */
fun monthRecap(
    month: YearMonth,
    workouts: List<WorkoutEntity>,
    sets: List<SetEntity>,
    tracking: Map<Long, TrackingType>,
    bodyWeights: List<Pair<Long, Double>>,
    zone: ZoneId,
): MonthRecap? {
    fun startOf(m: YearMonth) = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val from = startOf(month)
    val to = startOf(month.plusMonths(1))
    val inMonth = workouts.filter { it.startedAt in from until to }
    if (inMonth.isEmpty()) return null
    val prevFrom = startOf(month.minusMonths(1))
    val baseFrom = startOf(month.minusMonths(3))
    val startedAt = workouts.associate { it.id to it.startedAt }
    val monthIds = inMonth.map { it.id }.toSet()
    val monthSets = sets.filter { it.workoutId in monthIds }
    val gains = monthSets.filter { tracking[it.exerciseId] == TrackingType.WEIGHT_REPS && it.type != SetType.WARMUP }
        .groupBy { it.exerciseId }
        .mapNotNull { (exId, exSets) ->
            val before = sets.filter {
                it.exerciseId == exId && it.type != SetType.WARMUP && (startedAt[it.workoutId] ?: 0) in baseFrom until from
            }.maxOfOrNull { it.oneRm } ?: return@mapNotNull null
            val gain = exSets.maxOf { it.oneRm } - before
            if (before > 0 && gain > 0.05) exId to gain else null
        }
        .sortedByDescending { it.second }
    val trend = smoothedTrend(bodyWeights)
    val weightStart = trend.lastOrNull { it.first < from }?.second
    val weightEnd = trend.lastOrNull { it.first < to }?.second
    return MonthRecap(
        month = month,
        workouts = inMonth.size,
        previousWorkouts = workouts.count { it.startedAt in prevFrom until from },
        durationMillis = inMonth.sumOf { it.durationMillis },
        volumeKg = monthSets.filter { it.type != SetType.WARMUP }.sumOf { it.volumeKg },
        records = monthSets.count { it.prMask != 0 },
        gains = gains,
        bodyWeightChange = if (weightStart != null && weightEnd != null && trend.any { it.first in from until to }) weightEnd - weightStart else null,
    )
}
