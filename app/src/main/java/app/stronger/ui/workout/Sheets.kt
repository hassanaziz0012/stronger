package app.stronger.ui.workout

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stronger.data.AppSettings
import app.stronger.ui.clean
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatClock
import app.stronger.ui.fromKg

val RestOptions = listOf(30, 45, 60, 90, 120, 150, 180, 240, 300)

/**
 * Picks a rest duration. [current] null with [allowDefault] means "use the default".
 */
@Composable
fun RestPickerSheet(
    title: String,
    current: Int?,
    allowDefault: Boolean,
    defaultSeconds: Int,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmallEmphasized)
            VSpace(16.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (allowDefault) {
                    FilterChip(
                        selected = current == null, onClick = { onPick(null) },
                        label = { Text("Default (${formatClock(defaultSeconds.toLong())})") },
                    )
                    FilterChip(selected = current == 0, onClick = { onPick(0) }, label = { Text("Off") })
                }
                RestOptions.forEach { s ->
                    FilterChip(selected = current == s, onClick = { onPick(s) }, label = { Text(formatClock(s.toLong())) })
                }
            }
        }
    }
}

data class PlateLoad(val perSide: List<Double>, val achieved: Double)

fun loadPlates(target: Double, bar: Double, plates: List<Double>): PlateLoad {
    var remaining = (target - bar) / 2
    val out = mutableListOf<Double>()
    for (p in plates.sortedDescending()) {
        while (remaining + 1e-9 >= p) {
            out += p
            remaining -= p
        }
    }
    return PlateLoad(out, bar + out.sum() * 2)
}

@Composable
fun PlateCalculatorSheet(initialKg: Double?, settings: AppSettings, onDismiss: () -> Unit) {
    val unit = settings.unit
    val bar = unit.fromKg(settings.barWeightKg).let { Math.round(it * 10) / 10.0 }
    var text by remember { mutableStateOf(initialKg?.let { unit.fromKg(it).clean() } ?: "") }
    val target = text.toDoubleOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().animateContentSize()) {
            Text("Plate calculator", style = MaterialTheme.typography.headlineSmallEmphasized)
            Text(
                "Bar ${bar.clean()} ${unit.label}. Change the bar and plates in Settings.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(16.dp)
            OutlinedTextField(
                value = text, onValueChange = { text = it.replace(',', '.') },
                label = { Text("Target weight (${unit.label})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(20.dp)
            if (target != null && target >= bar) {
                val load = loadPlates(target, bar, settings.plates)
                BarbellPicture(load.perSide, settings.plates)
                VSpace(16.dp)
                val grouped = load.perSide.groupingBy { it }.eachCount()
                Text(
                    if (grouped.isEmpty()) "Just the bar" else grouped.entries.joinToString("   ") { "${it.value} × ${it.key.clean()}" },
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
                Text("per side", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (kotlin.math.abs(load.achieved - target) > 1e-6) {
                    VSpace(8.dp)
                    Text(
                        "Closest you can load is ${load.achieved.clean()} ${unit.label}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    )
                }
            } else if (target != null) {
                Text("That's lighter than the bar", color = MaterialTheme.colorScheme.error)
            }
            VSpace(8.dp)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun BarbellPicture(perSide: List<Double>, all: List<Double>) {
    val heaviest = all.maxOrNull() ?: 25.0
    val colors = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.outline,
    )
    val sorted = all.sortedDescending()
    Row(Modifier.fillMaxWidth().height(120.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.width(10.dp).height(28.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline))
        perSide.forEach { p ->
            val idx = sorted.indexOf(p).coerceAtLeast(0)
            val h = (40 + 80 * (p / heaviest)).dp
            Box(
                Modifier.padding(horizontal = 1.dp).width(if (p >= 10) 16.dp else 10.dp).height(h)
                    .clip(RoundedCornerShape(4.dp)).background(colors.getOrElse(idx) { Color.Gray })
            )
        }
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(MaterialTheme.colorScheme.outlineVariant))
    }
}
