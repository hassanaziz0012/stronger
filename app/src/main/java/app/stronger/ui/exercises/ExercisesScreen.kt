package app.stronger.ui.exercises

import app.stronger.ui.components.ReadyBadge
import app.stronger.data.readyToProgress
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.rirLabel
import app.stronger.data.AppSettings
import app.stronger.data.BodyPart
import app.stronger.data.Equipment
import app.stronger.data.SessionExercise
import app.stronger.data.SessionSet
import app.stronger.data.Trend
import app.stronger.data.progressStatus
import app.stronger.data.repTarget
import app.stronger.data.sessionScore
import app.stronger.data.targetFor
import app.stronger.ui.plural
import app.stronger.ui.workout.TargetsSheet
import app.stronger.data.ExerciseEntity
import app.stronger.data.ExerciseUsage
import app.stronger.data.SetType
import app.stronger.data.SetWithWorkout
import app.stronger.data.TrackingType
import app.stronger.data.WeightUnit
import app.stronger.ui.Nav
import app.stronger.ui.charts.ChartPoint
import app.stronger.ui.charts.LineChart
import app.stronger.ui.clean
import app.stronger.ui.components.EmptyState
import app.stronger.ui.components.ExerciseAvatar
import app.stronger.ui.components.HSpace
import app.stronger.ui.components.Stat
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatDate
import app.stronger.ui.formatSeconds
import app.stronger.ui.formatSet
import app.stronger.ui.formatWeight
import app.stronger.ui.formatOneRm
import app.stronger.ui.fromKg
import app.stronger.ui.relativeDay
import kotlinx.coroutines.launch

private enum class Sort(val label: String) { NAME("Name"), FREQUENCY("Most performed"), RECENT("Recently performed") }

@Composable
fun ExercisesScreen(nav: Nav) {
    val c = LocalContext.current.container
    val exercises by c.repo.exercises.collectAsStateWithLifecycle(emptyList())
    val usage by c.repo.exerciseUsage.collectAsStateWithLifecycle(emptyList<ExerciseUsage>())
    var query by rememberSaveable { mutableStateOf("") }
    var part by remember { mutableStateOf<BodyPart?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    var sortMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex < 2 } }

    val usageById = remember(usage) { usage.associateBy { it.exerciseId } }
    val shown = remember(exercises, query, part, sort, usageById) {
        val f = exercises.filtered(query, part)
        when (sort) {
            Sort.NAME -> f
            Sort.FREQUENCY -> f.sortedByDescending { usageById[it.id]?.workouts ?: 0 }
            Sort.RECENT -> f.sortedByDescending { usageById[it.id]?.lastAt ?: 0 }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Exercises") },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) },
                                    onClick = { sort = s; sortMenu = false },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                expanded = fabExpanded,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New exercise") },
            )
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp),
        ) {
            item { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            item { BodyPartChips(part, { part = it }, Modifier.padding(vertical = 8.dp)) }
            if (shown.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.SearchOff, "No matches", "Try another search, or create it as a custom exercise.") {
                        TextButton(onClick = { creating = true }) { Text("Create \"${query.trim()}\"") }
                    }
                }
            }
            val grouped = sort == Sort.NAME
            var lastLetter: Char? = null
            shown.forEach { e ->
                val letter = e.name.first().uppercaseChar().let { if (it.isLetter()) it else '#' }
                if (grouped && letter != lastLetter) {
                    lastLetter = letter
                    stickyHeader(key = "h$letter") {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                letter.toString(), style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                item(key = e.id) {
                    val u = usageById[e.id]
                    ListItem(
                        onClick = { nav.exercise(e.id) },
                        leadingContent = { ExerciseAvatar(e.name, e.bodyPart) },
                        supportingContent = {
                            Text(
                                if (sort == Sort.RECENT && u != null) "${e.bodyPart.label} · ${u.lastAt.relativeDay()}"
                                else "${e.bodyPart.label} · ${e.equipment.label}"
                            )
                        },
                        trailingContent = { if (u != null) Text(u.workouts.toString(), style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.animateItem().padding(horizontal = 8.dp),
                    ) { Text(e.name) }
                }
            }
        }
    }

    if (creating) {
        ExerciseEditorSheet(
            initial = null, initialName = query,
            onDismiss = { creating = false },
            onSave = { e -> scope.launch { val id = c.repo.createExercise(e); creating = false; nav.exercise(id) } },
        )
    }
}

// ---------------- Detail ----------------

private data class ExerciseSession(val workoutId: Long, val startedAt: Long, val name: String, val sets: List<app.stronger.data.SetEntity>)

private enum class Metric(val label: String) { ONE_RM("Est. 1RM"), WEIGHT("Max weight"), VOLUME("Volume"), REPS("Max reps"), STRAIGHT("Unbroken reps"), TIME("Longest"), DISTANCE("Distance") }

/** Unbroken reps shows once the exercise has been rest-paused, to track working toward no pauses. */
private fun metricsFor(t: TrackingType, restPaused: Boolean) = when (t) {
    TrackingType.WEIGHT_REPS -> listOf(Metric.ONE_RM, Metric.WEIGHT, Metric.VOLUME) + if (restPaused) listOf(Metric.STRAIGHT) else emptyList()
    TrackingType.REPS -> listOf(Metric.REPS, Metric.VOLUME)
    TrackingType.DURATION -> listOf(Metric.TIME)
    TrackingType.DISTANCE_DURATION -> listOf(Metric.DISTANCE, Metric.TIME)
}

private fun ExerciseSession.value(m: Metric): Double {
    val working = sets.filter { it.type != SetType.WARMUP }.ifEmpty { sets }
    return when (m) {
        Metric.ONE_RM -> working.maxOf { it.oneRm }
        Metric.WEIGHT -> working.maxOf { it.weightKg ?: 0.0 }
        Metric.VOLUME -> working.sumOf { if (it.weightKg != null) it.volumeKg else (it.reps ?: 0).toDouble() }
        Metric.REPS, Metric.STRAIGHT -> working.maxOf { it.straightReps.toDouble() }
        Metric.TIME -> working.maxOf { (it.seconds ?: 0).toDouble() }
        Metric.DISTANCE -> working.sumOf { it.distanceKm ?: 0.0 }
    }
}

private fun Metric.format(v: Double, unit: WeightUnit, tracking: TrackingType): String = when (this) {
    Metric.ONE_RM -> "${formatOneRm(v, unit)} ${unit.label}"
    Metric.WEIGHT -> "${formatWeight(v, unit)} ${unit.label}"
    Metric.VOLUME -> if (tracking == TrackingType.REPS) "${v.toInt()} reps" else "${app.stronger.ui.formatCompact(unit.fromKg(v))} ${unit.label}"
    Metric.REPS, Metric.STRAIGHT -> "${v.toInt()} reps"
    Metric.TIME -> formatSeconds(v.toInt())
    Metric.DISTANCE -> "${v.clean()} km"
}

@Composable
fun ExerciseDetailScreen(id: Long, nav: Nav) {
    val c = LocalContext.current.container
    val exercise by c.repo.exercise(id).collectAsStateWithLifecycle(null)
    val rows by c.repo.exerciseSets(id).collectAsStateWithLifecycle(emptyList<SetWithWorkout>())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val e = exercise ?: return
    val sessions = remember(rows) {
        rows.groupBy { it.set.workoutId }.map { (wid, r) -> ExerciseSession(wid, r.first().startedAt, r.first().workoutName, r.map { it.set }) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(e.name, maxLines = 1) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "Edit exercise") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete exercise", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Summary", "History", "Records").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> SummaryTab(e, sessions, settings, onSaveExercise = { scope.launch { c.repo.updateExercise(it) } })
                1 -> HistoryTab(e, sessions, settings, nav)
                else -> RecordsTab(e, sessions, settings)
            }
        }
    }

    if (editing) {
        ExerciseEditorSheet(
            initial = e, onDismiss = { editing = false },
            onSave = { updated -> scope.launch { c.repo.updateExercise(updated); editing = false } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${e.name}?") },
            text = { Text("It disappears from your exercise list. Workouts that used it keep their history.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { c.repo.archiveExercise(e.id); nav.back() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SummaryTab(e: ExerciseEntity, sessions: List<ExerciseSession>, settings: AppSettings, onSaveExercise: (ExerciseEntity) -> Unit) {
    val metrics = metricsFor(e.tracking, sessions.any { s -> s.sets.any { it.pauseReps.isNotEmpty() } })
    var targets by remember { mutableStateOf(false) }
    if (targets) TargetsSheet(e, settings.unit, onSave = { targets = false; onSaveExercise(it) }, onDismiss = { targets = false })
    var metric by remember(e.tracking) { mutableStateOf(metrics.first()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExerciseAvatar(e.name, e.bodyPart, size = 72.dp)
                HSpace(16.dp)
                Column {
                    Text(e.name, style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        "${e.bodyPart.label} · ${e.equipment.label}", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (e.notes.isNotBlank()) item {
            Text(e.notes, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.tracking != TrackingType.DISTANCE_DURATION) item { ProgressCard(e, sessions, settings) { targets = true } }
        if (sessions.isEmpty()) {
            item { EmptyState(Icons.Outlined.FitnessCenter, "Not done yet", "Log this exercise in a workout and your progress shows up here.") }
            return@LazyColumn
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(16.dp)) {
                    if (metrics.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
                            metrics.forEachIndexed { i, m ->
                                ToggleButton(
                                    checked = metric == m,
                                    onCheckedChange = { metric = m },
                                    shapes = when (i) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                        metrics.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                    },
                                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                                ) { Text(m.label, maxLines = 1) }
                            }
                        }
                        VSpace(12.dp)
                    }
                    val asc = sessions.sortedBy { it.startedAt }.takeLast(40)
                    val pts = asc.map { s -> ChartPoint(s.startedAt, s.value(metric), metric.format(s.value(metric), settings.unit, e.tracking)) }
                    val toDisplay: (Double) -> Double = { if (metric in listOf(Metric.ONE_RM, Metric.WEIGHT, Metric.VOLUME) && e.tracking == TrackingType.WEIGHT_REPS) settings.unit.fromKg(it) else it }
                    Text(metric.label, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text("Last ${asc.size} workouts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VSpace(8.dp)
                    LineChart(
                        points = pts.map { it.copy(y = toDisplay(it.y)) },
                        formatY = { if (metric == Metric.TIME) formatSeconds(it.toInt()) else app.stronger.ui.formatCompact(it) },
                        formatX = { it.formatDate("MMM d") },
                    )
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.padding(20.dp).fillMaxWidth()) {
                    Stat(sessions.size.toString(), "Workouts", Modifier.weight(1f))
                    Stat(sessions.sumOf { it.sets.size }.toString(), "Sets", Modifier.weight(1f))
                    Stat(sessions.first().startedAt.relativeDay(), "Last done", Modifier.weight(1.3f))
                }
            }
        }
    }
}

/** Plateau status, rep targets and what to aim for next time. */
@Composable
private fun ProgressCard(e: ExerciseEntity, sessions: List<ExerciseSession>, settings: AppSettings, onEditTargets: () -> Unit) {
    val unit = settings.unit
    val status = remember(sessions) { progressStatus(sessions.map { it.startedAt to sessionScore(it.sets, e.tracking) }) }
    val target = e.repTarget(unit)
    val next = remember(sessions, e, unit) {
        val last = sessions.firstOrNull() ?: return@remember null
        val ex = SessionExercise(key = 0, exerciseId = e.id, sets = last.sets.map { SessionSet(key = 0, type = it.type) })
        ex.sets.indices.mapNotNull { targetFor(ex, it, last.sets, e, unit) }.takeIf { it.isNotEmpty() }
    }
    val canTarget = e.tracking == TrackingType.WEIGHT_REPS || e.tracking == TrackingType.REPS
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text("Progress", style = MaterialTheme.typography.titleMediumEmphasized)
            VSpace(8.dp)
            val (headline, detail, color) = when (status?.trend) {
                null -> Triple(
                    "Not enough recent data",
                    "After four sessions within ten weeks, you'll see here whether this lift is still moving.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Trend.PROGRESSING -> Triple(
                    "Progressing",
                    if (status.sessionsSinceBest == 0) "New best last session." else "Best was ${plural(status.sessionsSinceBest, "session")} ago.",
                    MaterialTheme.colorScheme.primary,
                )
                Trend.STALLED -> Triple(
                    "Stalled",
                    "No new best in ${status.sessionsSinceBest} sessions. Try taking about 10% off and building back up, changing the rep range, or a similar variation for a few weeks.",
                    MaterialTheme.colorScheme.tertiary,
                )
                Trend.REGRESSING -> Triple(
                    "Going backwards",
                    "Your last two sessions were well under your best. That's usually fatigue, sleep or food rather than this exercise; a lighter week tends to fix it.",
                    MaterialTheme.colorScheme.error,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (status?.trend) {
                        Trend.PROGRESSING -> Icons.AutoMirrored.Filled.TrendingUp
                        Trend.STALLED -> Icons.AutoMirrored.Filled.TrendingFlat
                        Trend.REGRESSING -> Icons.AutoMirrored.Filled.TrendingDown
                        null -> Icons.Filled.HourglassEmpty
                    },
                    null, tint = color,
                )
                HSpace(8.dp)
                Text(headline, style = MaterialTheme.typography.titleSmallEmphasized, color = color)
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (remember(sessions, e, unit) { readyToProgress(sessions.firstOrNull()?.sets, e, unit) }) {
                VSpace(12.dp)
                ReadyBadge(null)
                Text(
                    when {
                        e.tracking == TrackingType.DURATION -> "You hit every goal last session. Aim longer next time, or move to a harder variation."
                        e.tracking == TrackingType.REPS -> "You hit every goal last session. Add reps or load next time, or move to a harder variation."
                        else -> "You hit every goal last session. Go heavier next time."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (canTarget) {
                VSpace(16.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rep targets", style = MaterialTheme.typography.titleSmallEmphasized)
                        Text(
                            when {
                                target == null -> "Off. Set a rep range and Stronger suggests the weight and reps for every set."
                                e.tracking == TrackingType.REPS -> "${target.min}–${target.max} reps"
                                else -> "${target.min}–${target.max} reps, then ${formatWeight(target.incrementKg, unit)} ${unit.label} ${if (e.equipment == Equipment.ASSISTED_BODYWEIGHT) "less assistance" else "more"}"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onEditTargets) { Text(if (target == null) "Set" else "Edit") }
                }
                if (next != null) {
                    VSpace(8.dp)
                    Text("Next time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        next.joinToString("  ·  ") { formatSet(it, e.tracking, unit) },
                        style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(e: ExerciseEntity, sessions: List<ExerciseSession>, settings: AppSettings, nav: Nav) {
    if (sessions.isEmpty()) {
        EmptyState(Icons.Outlined.FitnessCenter, "No history yet", "Every set you log for ${e.name} lands here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(sessions, key = { it.workoutId }) { s ->
            Surface(
                onClick = { nav.workout(s.workoutId) },
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text(s.name, style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(s.startedAt.formatDate("EEE, MMM d, yyyy"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.sets.first().exerciseNote.takeIf { it.isNotBlank() }?.let { note ->
                        VSpace(8.dp)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Outlined.StickyNote2, null, Modifier.padding(end = 8.dp).size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    VSpace(8.dp)
                    var n = 0
                    s.sets.forEach { set ->
                        val label = if (set.type == SetType.WARMUP) "W" else if (set.type == SetType.NORMAL) "${++n}" else { n++; set.type.short }
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.padding(end = 12.dp).size(width = 20.dp, height = 20.dp), style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatSet(set, e.tracking, settings.unit) + (set.rir?.let { " · ${rirLabel(it)}" } ?: ""), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            if (e.tracking == TrackingType.WEIGHT_REPS && set.oneRm > 0) {
                                Text("${formatOneRm(set.oneRm, settings.unit)} 1RM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (set.prMask != 0) {
                                HSpace(6.dp)
                                Icon(Icons.Filled.EmojiEvents, "Record", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsTab(e: ExerciseEntity, sessions: List<ExerciseSession>, settings: AppSettings) {
    val all = sessions.flatMap { s -> s.sets.map { s to it } }.filter { it.second.type != SetType.WARMUP }
    if (all.isEmpty()) {
        EmptyState(Icons.Filled.EmojiEvents, "No records yet", "Your personal bests for ${e.name} will show up after your first workout with it.")
        return
    }
    val unit = settings.unit
    data class Rec(val title: String, val value: String, val date: Long)
    val records = buildList {
        when (e.tracking) {
            TrackingType.WEIGHT_REPS -> {
                all.maxByOrNull { it.second.oneRm }?.let { (s, set) -> add(Rec("Estimated 1RM", "${formatOneRm(set.oneRm, unit)} ${unit.label}", s.startedAt)) }
                all.filter { (it.second.reps ?: 0) > 0 }.maxByOrNull { it.second.weightKg ?: 0.0 }?.let { (s, set) -> add(Rec("Max weight", "${formatWeight(set.weightKg, unit)} ${unit.label}", s.startedAt)) }
                all.maxByOrNull { it.second.volumeKg }?.let { (s, set) -> add(Rec("Best set", formatSet(set, e.tracking, unit), s.startedAt)) }
                sessions.maxByOrNull { it.value(Metric.VOLUME) }?.let { s -> add(Rec("Best workout volume", Metric.VOLUME.format(s.value(Metric.VOLUME), unit, e.tracking), s.startedAt)) }
            }
            TrackingType.REPS -> {
                all.maxByOrNull { it.second.straightReps }?.let { (s, set) -> add(Rec("Max reps", "${set.straightReps} reps", s.startedAt)) }
                sessions.maxByOrNull { it.value(Metric.VOLUME) }?.let { s -> add(Rec("Most reps in a workout", "${s.value(Metric.VOLUME).toInt()} reps", s.startedAt)) }
            }
            TrackingType.DURATION -> all.maxByOrNull { it.second.seconds ?: 0 }?.let { (s, set) -> add(Rec("Longest", formatSeconds(set.seconds ?: 0), s.startedAt)) }
            TrackingType.DISTANCE_DURATION -> {
                all.maxByOrNull { it.second.distanceKm ?: 0.0 }?.let { (s, set) -> add(Rec("Longest distance", "${(set.distanceKm ?: 0.0).clean()} km", s.startedAt)) }
                all.maxByOrNull { it.second.seconds ?: 0 }?.let { (s, set) -> add(Rec("Longest time", formatSeconds(set.seconds ?: 0), s.startedAt)) }
            }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(records) { r ->
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(r.value, style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Text(r.date.formatDate("MMM d, yyyy"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        if (e.tracking == TrackingType.WEIGHT_REPS) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Rep records", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text("Heaviest weight you've moved for each rep count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        VSpace(8.dp)
                        (1..12).forEach { reps ->
                            val best = all.filter { it.second.straightReps >= reps }.maxByOrNull { it.second.weightKg ?: 0.0 }
                            if (best != null && (best.second.weightKg ?: 0.0) > 0) {
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text("$reps RM", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                    Text("${formatWeight(best.second.weightKg, unit)} ${unit.label}", style = MaterialTheme.typography.titleSmallEmphasized)
                                    Text("  " + best.first.startedAt.formatDate("MMM d"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
