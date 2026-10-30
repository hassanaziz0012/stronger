package app.stronger.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import app.stronger.container
import app.stronger.data.ExerciseEntity
import app.stronger.data.MeasureKind
import app.stronger.data.MeasurementEntity
import app.stronger.data.SetEntity
import app.stronger.data.TrackingType
import app.stronger.data.WeightUnit
import app.stronger.data.WorkoutEntity
import app.stronger.data.smoothedTrend
import app.stronger.data.trendChange
import app.stronger.health.ExternalSession
import app.stronger.health.HealthActivity
import app.stronger.ui.charts.ChartPoint
import app.stronger.ui.charts.LineChart
import app.stronger.ui.clean
import app.stronger.ui.components.HSpace
import app.stronger.ui.components.Stat
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatDate
import app.stronger.ui.formatMeasure
import app.stronger.ui.measureToDisplay
import app.stronger.ui.relativeDay
import kotlinx.coroutines.launch

/** Weekly cardio to aim for: the WHO's 150 minutes of moderate activity. */
private const val CARDIO_GOAL_MINUTES = 150

/**
 * Returns a function that asks for Health Connect access; on any grant it switches sync on and
 * pushes everything across. [onResult] gets whether anything was granted.
 */
@Composable
fun rememberHealthConnect(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        scope.launch {
            c.health.refreshGranted()
            if (granted.isNotEmpty()) {
                c.settings.update { it.copy(healthConnect = true) }
                c.health.syncInBackground()
            }
            onResult(granted.isNotEmpty())
        }
    }
    return { launcher.launch(c.health.permissions) }
}

@Composable
fun BodyWeightCard(measurements: List<MeasurementEntity>, unit: WeightUnit, onOpen: () -> Unit, onLog: () -> Unit) {
    val weights = remember(measurements) {
        measurements.filter { it.kind == MeasureKind.WEIGHT }.sortedBy { it.takenAt }.map { it.takenAt to it.value }
    }
    val trend = remember(weights) { smoothedTrend(weights) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Body weight", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        if (trend.isEmpty()) "Log it a few times a week to see the trend"
                        else listOfNotNull(
                            "Trend ${formatMeasure(MeasureKind.WEIGHT, trend.last().second, unit)}",
                            trendChange(trend, 30)?.let { "${formatChange(MeasureKind.WEIGHT, it, unit)} in 30 days" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onLog) { Icon(Icons.Filled.Add, "Log body weight") }
            }
            if (weights.size >= 2) {
                VSpace(12.dp)
                LineChart(
                    points = trend.takeLast(60).map { (t, v) -> ChartPoint(t, measureToDisplay(MeasureKind.WEIGHT, v, unit), formatMeasure(MeasureKind.WEIGHT, v, unit)) },
                    formatY = { it.clean(1) },
                    formatX = { it.formatDate("MMM d") },
                    height = 120.dp,
                )
            } else if (weights.isEmpty()) {
                VSpace(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.MonitorWeight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    HSpace(8.dp)
                    Text("Waist, arms, body fat and more are on the Body page.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Cardio done in Stronger over the last week, in the same shape as sessions from other apps. */
fun strongerCardio(sets: List<SetEntity>, workouts: List<WorkoutEntity>, exercises: Map<Long, ExerciseEntity>, now: Long = System.currentTimeMillis()): List<ExternalSession> {
    val recent = workouts.filter { it.startedAt >= now - 7 * 86_400_000L }.associateBy { it.id }
    return sets.filter { it.workoutId in recent && exercises[it.exerciseId]?.tracking == TrackingType.DISTANCE_DURATION }
        .groupBy { it.workoutId to it.exerciseId }
        .map { (key, s) -> ExternalSession(exercises[key.second]?.name ?: "Cardio", recent.getValue(key.first).startedAt, s.sumOf { it.seconds ?: 0 } / 60) }
}

@Composable
fun ActivityCard(enabled: Boolean, local: List<ExternalSession>, onConnect: () -> Unit) {
    val c = LocalContext.current.container
    if (!c.health.available) return
    val activity by produceState<HealthActivity?>(null, enabled) { value = if (enabled) runCatching { c.health.activity() }.getOrNull() else null }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("Cardio and recovery", style = MaterialTheme.typography.titleMediumEmphasized)
            if (!enabled) {
                Text(
                    "Connect Health Connect to see runs and rides from your watch, steps, resting heart rate and sleep next to your lifting. Your workouts and weight go the other way.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(12.dp)
                Button(onClick = onConnect) { Text("Connect Health Connect") }
                return@Column
            }
            Text("Last 7 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            VSpace(12.dp)
            val sessions = (activity?.cardio.orEmpty() + local).sortedByDescending { it.startedAt }
            val minutes = sessions.sumOf { it.minutes }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$minutes", style = MaterialTheme.typography.displaySmallEmphasized)
                Text(" / $CARDIO_GOAL_MINUTES min cardio", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
            VSpace(8.dp)
            LinearWavyProgressIndicator(
                progress = { (minutes.toFloat() / CARDIO_GOAL_MINUTES).coerceAtMost(1f) },
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            sessions.take(4).forEach { s ->
                VSpace(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.DirectionsRun, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    HSpace(8.dp)
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${s.minutes} min · ${s.startedAt.relativeDay()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val a = activity
            if (a != null && (a.stepsPerDay != null || a.restingHeartRate != null || a.sleepMinutesPerNight != null)) {
                VSpace(16.dp)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Stat(a.stepsPerDay?.let { "%,d".format(it) } ?: "—", "Steps a day", Modifier.weight(1f))
                    Stat(a.restingHeartRate?.let { "$it bpm" } ?: "—", "Resting HR", Modifier.weight(1f))
                    Stat(a.sleepMinutesPerNight?.let { "${it / 60}h ${it % 60}m" } ?: "—", "Sleep", Modifier.weight(1f))
                }
            }
        }
    }
}
