package app.stronger.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.AppSettings
import app.stronger.data.Pr
import app.stronger.data.SetType
import app.stronger.ui.Nav
import app.stronger.ui.components.HSpace
import app.stronger.ui.components.StatRow
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatCompact
import app.stronger.ui.formatDurationShort
import app.stronger.ui.fromKg
import app.stronger.ui.ordinal
import kotlinx.coroutines.launch

/** A shape that is [progress] of the way through [morph]; polygons are unit-normalized. */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, android.graphics.Path()).asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

@Composable
fun FinishedScreen(workoutId: Long, nav: Nav) {
    val c = LocalContext.current.container
    val workout by c.repo.workout(workoutId).collectAsStateWithLifecycle(null)
    val sets by c.repo.workoutSets(workoutId).collectAsStateWithLifecycle(emptyList())
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val number by produceState(0) { value = c.repo.database.workouts().count() }
    val scope = rememberCoroutineScope()
    var savedTemplate by remember { mutableStateOf(false) }
    val w = workout ?: return

    val records = sets.filter { it.prMask != 0 }.groupBy { it.exerciseId }
    val volume = sets.filter { it.type != SetType.WARMUP }.sumOf { it.volumeKg }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())
            .statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VSpace(32.dp)
        CelebrationBadge(hasRecords = records.isNotEmpty())
        VSpace(24.dp)
        Text(
            if (records.isNotEmpty()) "New records!" else "Workout complete",
            style = MaterialTheme.typography.displaySmallEmphasized, textAlign = TextAlign.Center,
        )
        if (number > 0) {
            Text(
                "That was your ${ordinal(number)} workout",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VSpace(24.dp)
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text(w.name, style = MaterialTheme.typography.titleLargeEmphasized)
                VSpace(12.dp)
                StatRow(
                    formatDurationShort(w.durationMillis) to "Duration",
                    formatCompact(settings.unit.fromKg(volume)) + " ${settings.unit.label}" to "Volume",
                    sets.size.toString() to "Sets",
                    records.size.toString() to "Records",
                )
            }
        }
        if (records.isNotEmpty()) {
            VSpace(12.dp)
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    records.forEach { (exId, rows) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EmojiEvents, null, tint = MaterialTheme.colorScheme.tertiary)
                            HSpace(12.dp)
                            Column {
                                Text(exercises[exId]?.name ?: "Exercise", style = MaterialTheme.typography.titleSmallEmphasized)
                                Text(
                                    Pr.labels(rows.fold(0) { a, s -> a or s.prMask }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
        VSpace(28.dp)
        Button(
            onClick = { nav.back() },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = ButtonDefaults.MediumContentPadding,
            shapes = ButtonDefaults.shapes(),
        ) { Text("Done", style = MaterialTheme.typography.titleMediumEmphasized) }
        if (w.templateId != null) {
            VSpace(8.dp)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        c.repo.sessionFromWorkout(w.id)?.let { c.repo.saveTemplate(w.templateId, it.copy(name = c.repo.template(w.templateId)?.name ?: it.name)) }
                        savedTemplate = true
                    }
                },
                enabled = !savedTemplate,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(if (savedTemplate) "Template updated" else "Update template with today's sets") }
        } else {
            VSpace(8.dp)
            OutlinedButton(
                onClick = {
                    scope.launch {
                        c.repo.sessionFromWorkout(w.id)?.let { c.repo.saveTemplate(null, it) }
                        savedTemplate = true
                    }
                },
                enabled = !savedTemplate,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes(),
            ) { Text(if (savedTemplate) "Saved as a template" else "Save as template") }
        }
        VSpace(24.dp)
    }
}

@Composable
private fun CelebrationBadge(hasRecords: Boolean) {
    val entrance = remember { Animatable(0f) }
    val pop = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, pop) }
    val infinite = rememberInfiniteTransition(label = "celebrate")
    val spin by infinite.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "spin")
    val morphProgress by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "morph",
    )
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny) }
    Box(Modifier.size(200.dp).graphicsLayer { scaleX = entrance.value; scaleY = entrance.value }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = -spin }.clip(MaterialShapes.SoftBurst.toShape())
                .background(MaterialTheme.colorScheme.tertiaryContainer)
        )
        Box(
            Modifier.size(150.dp).graphicsLayer { rotationZ = spin * 1.5f }.clip(MorphShape(morph, morphProgress))
                .background(MaterialTheme.colorScheme.primary)
        )
        Icon(
            if (hasRecords) Icons.Filled.EmojiEvents else Icons.Filled.Check, null,
            Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
