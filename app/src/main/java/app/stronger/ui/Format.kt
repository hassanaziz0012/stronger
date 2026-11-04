package app.stronger.ui

import app.stronger.data.MeasureKind
import app.stronger.data.SetEntity
import app.stronger.data.SessionSet
import app.stronger.data.TrackingType
import app.stronger.data.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

fun Double.clean(maxDecimals: Int = 2): String {
    if (abs(this - this.roundToLong()) < 1e-6) return this.roundToLong().toString()
    return "%.${maxDecimals}f".format(Locale.US, this).trimEnd('0').trimEnd('.')
}

fun WeightUnit.fromKg(kg: Double): Double = kg * perKg
fun WeightUnit.toKg(value: Double): Double = value / perKg

/** Display a stored kg weight in [unit], rounded to sensible plate precision. */
fun formatWeight(kg: Double?, unit: WeightUnit): String =
    kg?.let { (Math.round(unit.fromKg(it) * 100) / 100.0).clean() } ?: ""

/** Estimated maxes are fuzzy anyway; one decimal is plenty. */
fun formatOneRm(kg: Double, unit: WeightUnit): String = (Math.round(unit.fromKg(kg) * 10) / 10.0).clean()

/** Measurements display in the user's system: kg/cm or lbs/in; body fat is always %. */
fun measureUnit(kind: MeasureKind, unit: WeightUnit): String = when {
    kind == MeasureKind.WEIGHT -> unit.label
    kind == MeasureKind.BODY_FAT -> "%"
    unit == WeightUnit.LB -> "in"
    else -> "cm"
}

fun measureToDisplay(kind: MeasureKind, value: Double, unit: WeightUnit): Double = when {
    kind == MeasureKind.WEIGHT -> unit.fromKg(value)
    kind.isLength && unit == WeightUnit.LB -> value / 2.54
    else -> value
}

fun measureFromDisplay(kind: MeasureKind, value: Double, unit: WeightUnit): Double = when {
    kind == MeasureKind.WEIGHT -> unit.toKg(value)
    kind.isLength && unit == WeightUnit.LB -> value * 2.54
    else -> value
}

fun formatMeasure(kind: MeasureKind, value: Double, unit: WeightUnit, withUnit: Boolean = true): String {
    val n = measureToDisplay(kind, value, unit).clean(1)
    return if (!withUnit) n else if (kind == MeasureKind.BODY_FAT) "$n%" else "$n ${measureUnit(kind, unit)}"
}

fun formatWeightWithUnit(kg: Double, unit: WeightUnit) = "${formatWeight(kg, unit)} ${unit.label}"

/** Big numbers in compact form: 12 400 kg -> "12.4k". */
fun formatCompact(v: Double): String = when {
    v >= 1_000_000 -> "${(v / 1_000_000).clean(1)}M"
    v >= 10_000 -> "${(v / 1_000).clean(1)}k"
    else -> v.clean(0)
}

fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatDurationShort(millis: Long): String {
    val totalMin = millis / 60_000
    val h = totalMin / 60; val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * Duration cells hold plain digits and show them as M:SS, so 130 reads 1:30 and you never type a
 * colon. The last two digits are seconds and anything over 59 carries, so 90 is 90 seconds.
 */
fun secondsToDigits(sec: Int?): String = when {
    sec == null -> ""
    sec < 60 -> sec.toString()
    else -> "${sec / 60}%02d".format(sec % 60)
}

fun digitsToSeconds(digits: String): Int? {
    if (digits.isEmpty() || !digits.all(Char::isDigit)) return null
    val minutes = digits.dropLast(2).toIntOrNull() ?: 0
    return minutes * 60 + digits.takeLast(2).toInt()
}

fun formatSeconds(sec: Int): String = if (sec >= 60) formatClock(sec.toLong()) else "${sec}s"

fun formatSet(set: SetEntity, tracking: TrackingType, unit: WeightUnit): String =
    formatSetValues(set.weightKg, set.reps, set.seconds, set.distanceKm, tracking, unit, set.pauseReps)

fun formatSet(set: SessionSet, tracking: TrackingType, unit: WeightUnit): String =
    formatSetValues(set.weightKg, set.reps, set.seconds, set.distanceKm, tracking, unit, set.pauseReps)

/** Reps as done: "12", or "8+1+3" when rest-paused. */
fun formatReps(reps: Int?, pauses: List<Int>): String {
    val total = reps ?: 0
    if (pauses.isEmpty()) return "$total"
    return (listOf((total - pauses.sum()).coerceAtLeast(0)) + pauses).joinToString("+")
}

fun formatSetValues(
    weightKg: Double?, reps: Int?, seconds: Int?, distanceKm: Double?, tracking: TrackingType, unit: WeightUnit, pauses: List<Int> = emptyList(),
): String =
    when (tracking) {
        TrackingType.WEIGHT_REPS -> "${formatWeight(weightKg ?: 0.0, unit)} ${unit.label} × ${formatReps(reps, pauses)}"
        TrackingType.REPS -> "${formatReps(reps, pauses)} reps"
        TrackingType.DURATION -> formatSeconds(seconds ?: 0)
        TrackingType.DISTANCE_DURATION -> "${(distanceKm ?: 0.0).clean()} km · ${formatClock((seconds ?: 0).toLong())}"
    }

private val zone get() = ZoneId.systemDefault()
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
fun Long.formatDate(pattern: String): String =
    Instant.ofEpochMilli(this).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))

fun Long.relativeDay(now: Long = System.currentTimeMillis()): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(toLocalDate(), now.toLocalDate())
    return when {
        days <= 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "Last week"
        days < 60 -> "${days / 7} weeks ago"
        days < 365 -> "${days / 30} months ago"
        else -> formatDate("MMM d, yyyy")
    }
}

fun plural(n: Int, word: String, plural: String = word + "s") = "$n ${if (n == 1) word else plural}"

fun ordinal(n: Int): String {
    val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$n$suffix"
}
