package app.stronger.data

import kotlin.math.exp

/** Pure analysis of training history: plateaus, deloads and body-weight trends. */

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
        TrackingType.WEIGHT_REPS -> working.maxOfOrNull { effortOneRepMax(it.weightKg ?: 0.0, it.reps ?: 0, it.rir) } ?: 0.0
        TrackingType.REPS -> working.maxOfOrNull { ((it.reps ?: 0) + (it.rir ?: 0)).toDouble() } ?: 0.0
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
