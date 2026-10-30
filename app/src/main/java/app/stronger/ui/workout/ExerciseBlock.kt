package app.stronger.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonShapes
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stronger.data.AppSettings
import app.stronger.data.Equipment
import app.stronger.data.ExerciseEntity
import app.stronger.data.Session
import app.stronger.data.SessionExercise
import app.stronger.data.SessionSet
import app.stronger.data.SessionStore
import app.stronger.data.SetEntity
import app.stronger.data.SetType
import app.stronger.data.TrackingType
import app.stronger.data.addSet
import app.stronger.data.hintFor
import app.stronger.data.Bests
import app.stronger.data.RIR_MAX
import app.stronger.data.estimateRir
import app.stronger.data.tracksEffort
import app.stronger.data.readyToProgress
import app.stronger.data.repTarget
import app.stronger.data.targetFor
import app.stronger.data.RecordChance
import app.stronger.data.recordChance
import app.stronger.container
import kotlinx.coroutines.launch
import app.stronger.data.previousFor
import app.stronger.data.moveExercise
import app.stronger.data.removeExercise
import app.stronger.data.removeFromSuperset
import app.stronger.data.removeSet
import app.stronger.data.superset
import app.stronger.data.updateExercise
import app.stronger.data.updateSet
import app.stronger.ui.clean
import app.stronger.ui.formatClock
import app.stronger.ui.formatSeconds
import app.stronger.ui.formatWeight
import app.stronger.ui.toKg
import kotlin.math.roundToInt

@Composable
fun ExerciseBlock(
    ex: SessionExercise,
    index: Int,
    session: Session,
    info: ExerciseEntity?,
    previous: List<SetEntity>?,
    bests: Bests?,
    effortKey: Long?,
    onEffortKey: (Long?) -> Unit,
    settings: AppSettings,
    mode: EditorMode,
    store: SessionStore,
    exerciseNames: Map<Long, ExerciseEntity>,
    onReplace: () -> Unit,
    onOpenExercise: () -> Unit,
    onToggleSet: (exKey: Long, setKey: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = mode == EditorMode.Live
    val tracking = info?.tracking ?: TrackingType.WEIGHT_REPS
    var menu by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var restSheet by remember { mutableStateOf(false) }
    var supersetPicker by remember { mutableStateOf(false) }
    var plates by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf(false) }
    val repTarget = info?.repTarget(settings.unit)
    val canTarget = info != null && mode !is EditorMode.History && (tracking == TrackingType.WEIGHT_REPS || tracking == TrackingType.REPS)
    val superset = ex.supersetId != null
    val restSeconds = ex.restSeconds ?: info?.restSeconds ?: settings.defaultRestSeconds

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        val stripe = MaterialTheme.colorScheme.tertiary
        Column(
            Modifier
                .drawBehind {
                    if (superset) {
                        val w = 4.dp.toPx()
                        drawRoundRect(
                            stripe, topLeft = Offset(6.dp.toPx(), 16.dp.toPx()),
                            size = androidx.compose.ui.geometry.Size(w, size.height - 32.dp.toPx()),
                            cornerRadius = CornerRadius(w / 2),
                        )
                    }
                }
                .padding(start = if (superset) 10.dp else 0.dp)
        ) {
            Column(Modifier.padding(8.dp).animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpenExercise)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        if (superset) {
                            Text("Superset", style = MaterialTheme.typography.labelMediumEmphasized, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(
                            info?.name ?: "Exercise",
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (repTarget != null && mode !is EditorMode.History) {
                            val ready = live && readyToProgress(previous, repTarget)
                            Row(
                                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).clickable { targets = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.TrackChanges, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    " ${repTarget.min}–${repTarget.max} reps",
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (ready) Text(
                                    when {
                                        tracking == TrackingType.REPS -> " · top of range, go harder"
                                        info?.equipment == Equipment.ASSISTED_BODYWEIGHT -> " · less assistance today"
                                        else -> " · +${formatWeight(repTarget.incrementKg, settings.unit)} ${settings.unit.label} today"
                                    },
                                    style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                    if (mode !is EditorMode.History) {
                        TextButton(onClick = { restSheet = true }) {
                            Icon(Icons.Outlined.Timer, null, Modifier.size(18.dp))
                            Text(if (restSeconds > 0) " ${formatClock(restSeconds.toLong())}" else " Off", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Exercise options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (ex.note.isEmpty()) "Add note" else "Edit note") },
                                leadingIcon = { Icon(Icons.Outlined.StickyNote2, null) },
                                onClick = { menu = false; editingNote = true },
                            )
                            if (session.exercises.size > 1) {
                                if (superset) {
                                    DropdownMenuItem(
                                        text = { Text("Remove from superset") }, leadingIcon = { Icon(Icons.Filled.LinkOff, null) },
                                        onClick = { menu = false; store.update { it.removeFromSuperset(ex.key) } },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (superset) "Add to superset…" else "Superset with…") },
                                    leadingIcon = { Icon(Icons.Filled.Link, null) },
                                    onClick = { menu = false; supersetPicker = true },
                                )
                            }
                            if (index > 0) DropdownMenuItem(
                                text = { Text("Move up") }, leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                                onClick = { menu = false; store.update { it.moveExercise(ex.key, -1) } },
                            )
                            if (index < session.exercises.lastIndex) DropdownMenuItem(
                                text = { Text("Move down") }, leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                                onClick = { menu = false; store.update { it.moveExercise(ex.key, 1) } },
                            )
                            DropdownMenuItem(
                                text = { Text("Replace exercise") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                onClick = { menu = false; onReplace() },
                            )
                            if (info?.equipment == Equipment.BARBELL) DropdownMenuItem(
                                text = { Text("Plate calculator") }, leadingIcon = { Icon(Icons.Filled.Calculate, null) },
                                onClick = { menu = false; plates = true },
                            )
                            if (canTarget) DropdownMenuItem(
                                text = { Text("Rep targets") }, leadingIcon = { Icon(Icons.Filled.TrackChanges, null) },
                                onClick = { menu = false; targets = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Exercise details") }, leadingIcon = { Icon(Icons.Filled.Info, null) },
                                onClick = { menu = false; onOpenExercise() },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Remove exercise", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; store.update { it.removeExercise(ex.key) } },
                            )
                        }
                    }
                }
                if (ex.note.isNotEmpty() || editingNote) {
                    BasicTextField(
                        value = ex.note,
                        onValueChange = { v -> store.update { it.updateExercise(ex.key) { e -> e.copy(note = v) } } },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onTertiaryContainer),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        decorationBox = { inner ->
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (ex.note.isEmpty()) Text("Note for this exercise", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
                                inner()
                            }
                        },
                    )
                }
                ColumnHeaders(tracking, info?.equipment, settings, live)
                var normalCount = 0
                ex.sets.forEachIndexed { i, set ->
                    val label = when (set.type) {
                        SetType.WARMUP -> "W"
                        SetType.NORMAL -> (++normalCount).toString()
                        else -> { normalCount++; set.type.short }
                    }
                    key(set.key) {
                        val target = if (live) targetFor(ex, i, previous, info, settings.unit) else null
                        val hint = target ?: hintFor(ex, i, previous)
                        SetRow(
                            set = set, label = label, tracking = tracking, settings = settings, mode = mode,
                            previous = previousFor(ex, i, previous),
                            hint = hint,
                            targeted = target != null,
                            chance = if (live) recordChance(ex, i, hint, tracking, bests) else null,
                            onChange = { f -> store.update { it.updateSet(ex.key, set.key, f) } },
                            onToggle = { onToggleSet(ex.key, set.key) },
                            onDelete = { store.update { it.removeSet(ex.key, set.key) } },
                            onEffort = if (set.done && set.type != SetType.WARMUP && mode !is EditorMode.Template && tracksEffort(info)) ({ onEffortKey(set.key) }) else null,
                        )
                        AnimatedVisibility(set.done && set.key == effortKey) {
                            EffortPicker(
                                selected = set.rir,
                                estimate = if (live) estimateRir(set, info, bests) else null,
                                onPick = { r ->
                                    store.update { it.updateSet(ex.key, set.key) { x -> x.copy(rir = r) } }
                                    onEffortKey(null)
                                },
                                onDismiss = { onEffortKey(null) },
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { store.update { it.addSet(ex.key) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Text(" Add set")
                }
            }
        }
    }

    if (restSheet) {
        RestPickerSheet(
            title = "Rest after ${info?.name ?: "this exercise"}",
            current = ex.restSeconds, allowDefault = true, defaultSeconds = info?.restSeconds ?: settings.defaultRestSeconds,
            onPick = { v -> restSheet = false; store.update { it.updateExercise(ex.key) { e -> e.copy(restSeconds = v) } } },
            onDismiss = { restSheet = false },
        )
    }
    if (targets && info != null) {
        val c = LocalContext.current.container
        val scope = rememberCoroutineScope()
        TargetsSheet(
            info, settings.unit,
            onSave = { updated -> targets = false; scope.launch { c.repo.updateExercise(updated) } },
            onDismiss = { targets = false },
        )
    }
    if (plates) {
        val lastWeight = ex.sets.lastOrNull { it.weightKg != null }?.weightKg ?: previous?.lastOrNull()?.weightKg
        PlateCalculatorSheet(lastWeight, settings) { plates = false }
    }
    if (supersetPicker) {
        val others = session.exercises.filter { it.key != ex.key && (ex.supersetId == null || it.supersetId != ex.supersetId) }
        AlertDialog(
            onDismissRequest = { supersetPicker = false },
            title = { Text("Superset with") },
            text = {
                Column {
                    others.forEach { o ->
                        ListItem(
                            onClick = { supersetPicker = false; store.update { it.superset(ex.key, o.key) } },
                        ) { Text(exerciseNames[o.exerciseId]?.name ?: "Exercise") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { supersetPicker = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ColumnHeaders(tracking: TrackingType, equipment: Equipment?, settings: AppSettings, live: Boolean) {
    val unit = settings.unit.label
    val weightLabel = when (equipment) {
        Equipment.ASSISTED_BODYWEIGHT -> "−$unit"
        Equipment.WEIGHTED_BODYWEIGHT -> "+$unit"
        else -> unit
    }
    val fields = when (tracking) {
        TrackingType.WEIGHT_REPS -> listOf(weightLabel, "reps")
        TrackingType.REPS -> listOf("reps")
        TrackingType.DURATION -> listOf("sec")
        TrackingType.DISTANCE_DURATION -> listOf("km", "min")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = MaterialTheme.typography.labelMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text("SET", Modifier.width(36.dp), style = style, color = color, textAlign = TextAlign.Center)
        if (live) Text("PREVIOUS", Modifier.weight(1.3f), style = style, color = color, textAlign = TextAlign.Center)
        fields.forEach { Text(it.uppercase(), Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.Center) }
        if (live) Box(Modifier.width(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = color)
        }
    }
}

@Composable
private fun SetRow(
    set: SessionSet,
    label: String,
    tracking: TrackingType,
    settings: AppSettings,
    mode: EditorMode,
    previous: SetEntity?,
    hint: SessionSet?,
    targeted: Boolean,
    chance: RecordChance?,
    onChange: ((SessionSet) -> SessionSet) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEffort: (() -> Unit)?,
) {
    val live = mode == EditorMode.Live
    val unit = settings.unit
    val haptics = LocalHapticFeedback.current
    val dismiss = rememberSwipeToDismissBoxState()
    var typeMenu by remember { mutableStateOf(false) }
    val rowColor by animateColorAsState(
        if (set.done && live) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "row",
    )

    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        onDismiss = { onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxWidth().fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Filled.Delete, "Delete set", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                .background(rowColor).padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp)) {
                val badgeColor = when (set.type) {
                    SetType.WARMUP -> MaterialTheme.colorScheme.tertiary
                    SetType.DROP -> MaterialTheme.colorScheme.secondary
                    SetType.FAILURE -> MaterialTheme.colorScheme.error
                    SetType.NORMAL -> MaterialTheme.colorScheme.onSurface
                }
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).clickable { typeMenu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = MaterialTheme.typography.titleSmallEmphasized, color = badgeColor)
                        if (set.done && set.rir != null) {
                            Text(
                                "${if (set.rir >= RIR_MAX) "$RIR_MAX+" else set.rir} left",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false,
                            )
                        }
                        if (chance == RecordChance.ONE_MORE_REP) {
                            Text(
                                "+1 rep",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                color = MaterialTheme.colorScheme.tertiary, maxLines = 1, softWrap = false,
                            )
                        }
                    }
                }
                if (set.prMask != 0 && set.done) {
                    Icon(
                        Icons.Filled.EmojiEvents, "Personal record",
                        Modifier.size(14.dp).align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (chance != null) {
                    Icon(
                        Icons.Outlined.EmojiEvents,
                        if (chance == RecordChance.NOW) "A record at these numbers" else "One more rep sets a record",
                        Modifier.size(14.dp).align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    SetType.entries.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.label) },
                            leadingIcon = { Text(t.short.ifEmpty { "1" }, style = MaterialTheme.typography.titleSmallEmphasized) },
                            trailingIcon = { if (t == set.type) Icon(Icons.Filled.Check, null) },
                            onClick = { typeMenu = false; onChange { it.copy(type = t) } },
                        )
                    }
                    if (onEffort != null) DropdownMenuItem(
                        text = { Text("Reps in reserve") },
                        leadingIcon = { Icon(Icons.Filled.Speed, null) },
                        trailingIcon = { set.rir?.let { Text(if (it >= RIR_MAX) "$RIR_MAX+" else "$it") } },
                        onClick = { typeMenu = false; onEffort() },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Delete set", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { typeMenu = false; onDelete() },
                    )
                }
            }
            if (live) {
                Text(
                    previous?.let { previousText(it, tracking, unit) } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1.3f).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = previous != null && !set.done) {
                            previous?.let { p -> onChange { it.copy(weightKg = p.weightKg, reps = p.reps, seconds = p.seconds, distanceKm = p.distanceKm) } }
                        }
                        .padding(vertical = 8.dp),
                )
            }
            when (tracking) {
                TrackingType.WEIGHT_REPS -> {
                    NumberCell(formatWeight(set.weightKg, unit), formatWeight(hint?.weightKg, unit), true, set.done && live, targeted, Modifier.weight(1f)) { t ->
                        onChange { it.copy(weightKg = t.toDoubleOrNull()?.let(unit::toKg)) }
                    }
                    NumberCell(set.reps?.toString() ?: "", hint?.reps?.toString() ?: "", false, set.done && live, targeted, Modifier.weight(1f)) { t ->
                        onChange { it.copy(reps = t.toIntOrNull()) }
                    }
                }
                TrackingType.REPS -> NumberCell(set.reps?.toString() ?: "", hint?.reps?.toString() ?: "", false, set.done && live, targeted, Modifier.weight(1f)) { t ->
                    onChange { it.copy(reps = t.toIntOrNull()) }
                }
                TrackingType.DURATION -> NumberCell(set.seconds?.toString() ?: "", hint?.seconds?.toString() ?: "", false, set.done && live, targeted, Modifier.weight(1f)) { t ->
                    onChange { it.copy(seconds = t.toIntOrNull()) }
                }
                TrackingType.DISTANCE_DURATION -> {
                    NumberCell(set.distanceKm?.clean() ?: "", hint?.distanceKm?.clean() ?: "", true, set.done && live, targeted, Modifier.weight(1f)) { t ->
                        onChange { it.copy(distanceKm = t.toDoubleOrNull()) }
                    }
                    NumberCell(set.seconds?.let { (it / 60.0).clean() } ?: "", hint?.seconds?.let { (it / 60.0).clean() } ?: "", true, set.done && live, targeted, Modifier.weight(1f)) { t ->
                        onChange { it.copy(seconds = t.toDoubleOrNull()?.let { m -> (m * 60).roundToInt() }) }
                    }
                }
            }
            if (live) {
                FilledIconToggleButton(
                    checked = set.done,
                    onCheckedChange = {
                        haptics.performHapticFeedback(if (it) HapticFeedbackType.Confirm else HapticFeedbackType.ToggleOff)
                        onToggle()
                    },
                    shapes = IconToggleButtonShapes(
                        shape = RoundedCornerShape(10.dp),
                        pressedShape = RoundedCornerShape(6.dp),
                        checkedShape = CircleShape,
                    ),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedContainerColor = MaterialTheme.colorScheme.primary,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp, 36.dp),
                ) { Icon(Icons.Filled.Check, if (set.done) "Mark set not done" else "Complete set") }
            }
        }
    }
}

/** "How many more could you have done?" asked under a set right after it's ticked. */
@Composable
private fun EffortPicker(selected: Int?, estimate: Int?, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Reps left?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            (0..RIR_MAX).forEach { n ->
                val text = if (n == RIR_MAX) "$RIR_MAX+" else "$n"
                ToggleButton(
                    checked = selected == n,
                    onCheckedChange = { onPick(n) },
                    shapes = when (n) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        RIR_MAX -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding = PaddingValues(horizontal = 2.dp),
                    modifier = Modifier.weight(1f).height(36.dp).semantics {
                        contentDescription = (if (n == 1) "1 rep in reserve" else "$text reps in reserve") + if (estimate == n && selected == null) ", estimated" else ""
                    },
                ) {
                    // The guess is marked, not chosen: only what you tap is recorded.
                    Text(if (estimate == n && selected == null) "~$text" else text, maxLines = 1)
                }
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.Close, "Skip", Modifier.size(18.dp)) }
    }
}

private fun previousText(p: SetEntity, tracking: TrackingType, unit: app.stronger.data.WeightUnit): String = when (tracking) {
    TrackingType.WEIGHT_REPS -> "${formatWeight(p.weightKg ?: 0.0, unit)} × ${p.reps ?: 0}"
    TrackingType.REPS -> "× ${p.reps ?: 0}"
    TrackingType.DURATION -> formatSeconds(p.seconds ?: 0)
    TrackingType.DISTANCE_DURATION -> "${(p.distanceKm ?: 0.0).clean()} km"
}

@Composable
private fun NumberCell(
    value: String,
    hint: String,
    decimal: Boolean,
    done: Boolean,
    targeted: Boolean,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value.toDoubleOrNull()) text = value }
    val pattern = if (decimal) Regex("""^\d{0,5}([.]\d{0,2})?$""") else Regex("""^\d{0,5}$""")
    BasicTextField(
        value = text,
        onValueChange = { raw ->
            val t = raw.replace(',', '.')
            if (pattern.matches(t)) {
                text = t
                onChange(t)
            }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMediumEmphasized.copy(
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, fontFeatureSettings = "tnum",
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (done) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (text.isEmpty()) {
                    Text(
                        hint, style = MaterialTheme.typography.titleMedium,
                        // Targets read as a goal to hit; plain hints as a faint reminder of last time.
                        color = if (targeted) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                    )
                }
                inner()
            }
        },
    )
}
