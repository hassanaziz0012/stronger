package app.stronger.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.AppSettings
import app.stronger.data.ExerciseEntity
import app.stronger.data.SetEntity
import app.stronger.data.SetType
import app.stronger.data.TrackingType
import app.stronger.data.WorkoutEntity
import app.stronger.data.freshCopy
import app.stronger.ui.Nav
import app.stronger.ui.components.EmptyState
import app.stronger.ui.components.HSpace
import app.stronger.ui.components.StatRow
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatCompact
import app.stronger.ui.formatDate
import app.stronger.ui.formatDurationShort
import app.stronger.ui.formatSet
import app.stronger.ui.formatWeight
import app.stronger.ui.formatOneRm
import app.stronger.ui.fromKg
import app.stronger.ui.toLocalDate
import app.stronger.ui.workout.firstDayOfWeek
import app.stronger.ui.workout.weekStart
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

data class WorkoutCardData(
    val workout: WorkoutEntity,
    val volumeKg: Double,
    val records: Int,
    /** Exercise, number of sets, best set. */
    val lines: List<Triple<ExerciseEntity?, Int, SetEntity>>,
)

fun buildCards(workouts: List<WorkoutEntity>, sets: List<SetEntity>, exercises: Map<Long, ExerciseEntity>): List<WorkoutCardData> {
    val byWorkout = sets.groupBy { it.workoutId }
    return workouts.map { w ->
        val ws = byWorkout[w.id].orEmpty()
        WorkoutCardData(
            workout = w,
            volumeKg = ws.filter { it.type != SetType.WARMUP }.sumOf { it.volumeKg },
            records = ws.count { it.prMask != 0 },
            lines = ws.groupBy { it.exerciseIndex }.toSortedMap().values.map { group ->
                val e = exercises[group.first().exerciseId]
                val best = group.maxWith(compareBy<SetEntity>({ it.oneRm }, { it.reps ?: 0 }, { it.seconds ?: 0 }, { it.distanceKm ?: 0.0 }))
                Triple(e, group.size, best)
            },
        )
    }
}

@Composable
fun HistoryScreen(nav: Nav) {
    val c = LocalContext.current.container
    val workouts by c.repo.workouts.collectAsStateWithLifecycle(emptyList())
    val sets by c.repo.allSets.collectAsStateWithLifecycle(emptyList())
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val cards = remember(workouts, sets, exercises) { buildCards(workouts, sets, exercises) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("History") }, scrollBehavior = scroll) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { MonthCalendar(month, { month = it }, workouts) { nav.workout(it) } }
            if (cards.isEmpty()) {
                item {
                    EmptyState(Icons.Outlined.CalendarMonth, "No workouts yet", "Finished workouts land here. Importing from Strong works too: Profile → Settings.")
                }
            }
            var lastMonth: YearMonth? = null
            cards.forEach { card ->
                val ym = YearMonth.from(card.workout.startedAt.toLocalDate())
                if (ym != lastMonth) {
                    lastMonth = ym
                    val count = cards.count { YearMonth.from(it.workout.startedAt.toLocalDate()) == ym }
                    item(key = "m$ym") {
                        Row(Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                            Text(
                                ym.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + ym.year,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                            )
                            Text(
                                "  $count ${if (count == 1) "workout" else "workouts"}",
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = card.workout.id) { WorkoutCard(card, settings) { nav.workout(card.workout.id) } }
            }
        }
    }
}

@Composable
private fun MonthCalendar(month: YearMonth, onMonth: (YearMonth) -> Unit, workouts: List<WorkoutEntity>, onOpen: (Long) -> Unit) {
    val byDay = remember(workouts) { workouts.groupBy { it.startedAt.toLocalDate() } }
    val today = LocalDate.now()
    val first = month.atDay(1)
    val gridStart = first.weekStart()
    val weeks = ((month.atEndOfMonth().toEpochDay() - gridStart.toEpochDay()) / 7 + 1).toInt()
    val inMonth = byDay.keys.count { YearMonth.from(it) == month }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        if (inMonth == 1) "1 workout day" else "$inMonth workout days", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.now()) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month")
                }
            }
            VSpace(4.dp)
            Row {
                (0L..6L).forEach { i ->
                    Text(
                        firstDayOfWeek.plus(i).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        Modifier.weight(1f).padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            val cookie = MaterialShapes.Cookie6Sided.toShape()
            repeat(weeks) { w ->
                Row {
                    (0..6).forEach { d ->
                        val day = gridStart.plusDays((w * 7 + d).toLong())
                        val visible = YearMonth.from(day) == month
                        val did = byDay[day]
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                            if (visible) {
                                val base = Modifier.fillMaxSize()
                                val m = when {
                                    did != null -> base.clip(cookie).background(MaterialTheme.colorScheme.primary).clickable { onOpen(did.first().id) }
                                    day == today -> base.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    else -> base
                                }
                                Box(m, contentAlignment = Alignment.Center) {
                                    Text(
                                        day.dayOfMonth.toString(),
                                        style = if (did != null) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                                        color = when {
                                            did != null -> MaterialTheme.colorScheme.onPrimary
                                            day.isAfter(today) -> MaterialTheme.colorScheme.outline
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WorkoutCard(card: WorkoutCardData, settings: AppSettings, onClick: () -> Unit) {
    val w = card.workout
    val unit = settings.unit
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.name, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                if (card.records > 0) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.EmojiEvents, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                                " ${card.records} ${if (card.records == 1) "PR" else "PRs"}",
                                style = MaterialTheme.typography.labelMediumEmphasized, color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            Text(
                listOfNotNull(
                    w.startedAt.formatDate("EEE, MMM d"),
                    formatDurationShort(w.durationMillis).takeIf { w.durationMillis > 0 },
                    "${formatCompact(unit.fromKg(card.volumeKg))} ${unit.label}".takeIf { card.volumeKg > 0 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(10.dp)
            card.lines.take(8).forEach { (e, n, best) ->
                Row(Modifier.padding(vertical = 1.dp)) {
                    Text(
                        "$n × ${e?.name ?: "Exercise"}", style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    HSpace(8.dp)
                    Text(
                        formatSet(best, e?.tracking ?: TrackingType.WEIGHT_REPS, unit),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (card.lines.size > 8) Text("+ ${card.lines.size - 8} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun WorkoutDetailScreen(id: Long, nav: Nav) {
    val c = LocalContext.current.container
    val workout by c.repo.workout(id).collectAsStateWithLifecycle(null)
    val sets by c.repo.workoutSets(id).collectAsStateWithLifecycle(emptyList())
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val w = workout ?: return
    val unit = settings.unit
    val groups = sets.groupBy { it.exerciseIndex }.toSortedMap().values.toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.editWorkout(id) }) { Icon(Icons.Filled.Edit, "Edit workout") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Do it again") }, leadingIcon = { Icon(Icons.Filled.Replay, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val s = c.repo.sessionFromWorkout(id) ?: return@launch
                                        if (c.active.isActive) {
                                            snackbar.showSnackbar("Finish the workout in progress first")
                                        } else {
                                            c.active.start(app.stronger.data.Session(name = s.name, templateId = s.templateId, exercises = s.exercises.freshCopy()))
                                            nav.liveWorkout()
                                        }
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Save as template") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        c.repo.sessionFromWorkout(id)?.let { c.repo.saveTemplate(null, it) }
                                        snackbar.showSnackbar("Saved \"${w.name}\" as a template")
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(20.dp).fillMaxWidth()) {
                        Text(w.startedAt.formatDate("EEEE, MMMM d, yyyy · h:mm a"), style = MaterialTheme.typography.labelLarge)
                        VSpace(12.dp)
                        StatRow(
                            formatDurationShort(w.durationMillis) to "Duration",
                            "${formatCompact(unit.fromKg(sets.filter { it.type != SetType.WARMUP }.sumOf { it.volumeKg }))} ${unit.label}" to "Volume",
                            sets.size.toString() to "Sets",
                            sets.count { it.prMask != 0 }.toString() to "PRs",
                        )
                        if (w.notes.isNotBlank()) {
                            VSpace(12.dp)
                            Text(w.notes, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            items(groups) { group ->
                val e = exercises[group.first().exerciseId]
                val tracking = e?.tracking ?: TrackingType.WEIGHT_REPS
                Surface(
                    onClick = { e?.let { nav.exercise(it.id) } },
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(e?.name ?: "Exercise", style = MaterialTheme.typography.titleMediumEmphasized, color = MaterialTheme.colorScheme.primary)
                        group.first().exerciseNote.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        VSpace(6.dp)
                        var n = 0
                        group.forEach { set ->
                            val label = if (set.type == SetType.WARMUP) "W" else if (set.type == SetType.NORMAL) "${++n}" else { n++; set.type.short }
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.size(width = 28.dp, height = 20.dp), style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatSet(set, tracking, unit), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (tracking == TrackingType.WEIGHT_REPS && set.oneRm > 0) {
                                    Text("${formatOneRm(set.oneRm, unit)} 1RM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (set.prMask != 0) {
                                    HSpace(6.dp)
                                    Icon(Icons.Filled.EmojiEvents, "Record: " + app.stronger.data.Pr.labels(set.prMask).joinToString(), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this workout?") },
            text = { Text("It's removed from your history and stats for good.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { c.repo.deleteWorkout(id); nav.back() }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
