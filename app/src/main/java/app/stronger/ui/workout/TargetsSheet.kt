package app.stronger.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.stronger.data.Equipment
import app.stronger.data.ExerciseEntity
import app.stronger.data.TrackingType
import app.stronger.data.WeightUnit
import app.stronger.data.defaultIncrementKg
import app.stronger.ui.clean
import app.stronger.ui.components.VSpace
import app.stronger.ui.fromKg
import app.stronger.ui.toKg

private val presets = listOf(3 to 5, 5 to 8, 8 to 12, 12 to 15, 15 to 20)

/** Sets an exercise's rep range and weight increment for double-progression targets. */
@Composable
fun TargetsSheet(exercise: ExerciseEntity, unit: WeightUnit, onSave: (ExerciseEntity) -> Unit, onDismiss: () -> Unit) {
    val weighted = exercise.tracking == TrackingType.WEIGHT_REPS
    val assisted = exercise.equipment == Equipment.ASSISTED_BODYWEIGHT
    var on by remember { mutableStateOf(exercise.repMin != null) }
    var min by remember { mutableIntStateOf(exercise.repMin ?: 8) }
    var max by remember { mutableIntStateOf(exercise.repMax ?: 12) }
    val defaultStep = unit.fromKg(defaultIncrementKg(exercise.equipment, unit)).clean(1)
    var step by remember { mutableStateOf(exercise.incrementKg?.let { unit.fromKg(it).clean(2) } ?: "") }

    // Fully expanded, so the content can sit above the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding().animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rep targets", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(exercise.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { on = it })
            }
            AnimatedVisibility(on) {
                Column {
                    VSpace(20.dp)
                    Text("Rep range", style = MaterialTheme.typography.titleSmallEmphasized)
                    VSpace(8.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stepper(min, { min = it.coerceIn(1, 50); if (max < min) max = min })
                        Text("to", style = MaterialTheme.typography.bodyLarge)
                        Stepper(max, { max = it.coerceIn(1, 50); if (min > max) min = max })
                        Text("reps", style = MaterialTheme.typography.bodyLarge)
                    }
                    VSpace(8.dp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { (a, b) ->
                            FilterChip(selected = min == a && max == b, onClick = { min = a; max = b }, label = { Text("$a–$b") })
                        }
                    }
                    if (weighted) {
                        VSpace(16.dp)
                        OutlinedTextField(
                            value = step, onValueChange = { step = it.replace(',', '.') },
                            label = { Text(if (assisted) "Assistance to remove" else "Weight to add") },
                            placeholder = { Text(defaultStep) },
                            supportingText = { Text("Leave empty for $defaultStep ${unit.label}") },
                            suffix = { Text(unit.label) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    VSpace(16.dp)
                    val amount = "${step.toDoubleOrNull()?.clean(2) ?: defaultStep} ${unit.label}"
                    Text(
                        when {
                            !weighted -> "Each session, aim for one more rep per set than last time. Once every set reaches $max, you're ready for a harder variation or added weight."
                            assisted -> "Each session, aim for one more rep per set than last time. Once every working set reaches $max, take $amount of assistance off and start again at $min."
                            else -> "Each session, aim for one more rep per set than last time. Once every working set reaches $max, add $amount and start again at $min."
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(4.dp)
                    Text(
                        "Targets show up as the hints in each set, and replace the numbers saved in your templates once you've done this exercise before.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            VSpace(16.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = {
                    onSave(
                        if (!on) exercise.copy(repMin = null, repMax = null, incrementKg = null)
                        else exercise.copy(
                            repMin = min, repMax = max,
                            incrementKg = step.toDoubleOrNull()?.takeIf { weighted && it > 0 }?.let(unit::toKg),
                        )
                    )
                }) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, "Fewer") }
        Text(value.toString(), style = MaterialTheme.typography.titleLargeEmphasized, modifier = Modifier.padding(horizontal = 8.dp))
        FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, "More") }
    }
}
