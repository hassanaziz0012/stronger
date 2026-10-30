package app.stronger.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.stronger.data.Session
import app.stronger.data.TemplateEntity
import app.stronger.data.WorkoutEntity
import app.stronger.ui.Nav
import app.stronger.ui.components.EmptyState
import app.stronger.ui.components.HSpace
import app.stronger.ui.components.SectionHeader
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatClock
import app.stronger.ui.rememberNow
import app.stronger.ui.relativeDay
import app.stronger.ui.toLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.launch

fun defaultWorkoutName(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Morning Workout"
    in 12..16 -> "Afternoon Workout"
    in 17..21 -> "Evening Workout"
    else -> "Night Workout"
}

val firstDayOfWeek: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek
fun LocalDate.weekStart(): LocalDate = with(TemporalAdjusters.previousOrSame(firstDayOfWeek))

/** Consecutive weeks with at least one workout, counting back from this week (or last, if this one is still empty). */
fun weekStreak(workouts: List<WorkoutEntity>): Int {
    val weeks = workouts.map { it.startedAt.toLocalDate().weekStart() }.toSet()
    var week = LocalDate.now().weekStart()
    if (week !in weeks) week = week.minusWeeks(1)
    var n = 0
    while (week in weeks) { n++; week = week.minusWeeks(1) }
    return n
}

@Composable
fun WorkoutHomeScreen(nav: Nav) {
    val c = LocalContext.current.container
    val templates by c.repo.templates.collectAsStateWithLifecycle(emptyList())
    val workouts by c.repo.workouts.collectAsStateWithLifecycle(emptyList())
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val active by c.active.session.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<TemplateEntity?>(null) }
    var pendingStart by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<TemplateEntity?>(null) }
    val scope = rememberCoroutineScope()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun start(session: Session) {
        if (c.active.isActive) pendingStart = session
        else { c.active.start(session); nav.liveWorkout() }
    }

    Scaffold(
        topBar = { LargeFlexibleTopAppBar(title = { Text("Workout") }, scrollBehavior = scroll) },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    val a = active
                    if (a != null) ResumeCard(a, nav::liveWorkout)
                    else Button(
                        onClick = { start(Session(name = defaultWorkoutName())) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                        contentPadding = ButtonDefaults.MediumContentPadding,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(ButtonDefaults.MediumIconSize))
                        HSpace(8.dp)
                        Text("Start an empty workout", style = MaterialTheme.typography.titleMediumEmphasized)
                    }
                    VSpace(16.dp)
                    WeekCard(workouts, settings.weeklyGoal)
                }
            }
            item {
                SectionHeader("Templates") {
                    FilledTonalIconButton(onClick = { nav.template() }) { Icon(Icons.Filled.Add, "New template") }
                }
            }
            if (templates.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.ViewAgenda, "No templates yet",
                        "Save the workouts you repeat, like Push, Pull or Legs, and start them in one tap.",
                    ) { OutlinedButton(onClick = { nav.template() }) { Text("Create a template") } }
                }
            }
            items(templates, key = { it.id }) { t ->
                TemplateCard(
                    t, c.repo.templateExercises(t).map { it.sets.size to (exercises[it.exerciseId]?.name ?: "Exercise") },
                    onClick = { preview = t },
                    onEdit = { nav.template(t.id) },
                    onDuplicate = { scope.launch { c.repo.duplicateTemplate(t.id) } },
                    onDelete = { deleting = t },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    preview?.let { t ->
        TemplatePreviewSheet(
            t, c.repo.templateExercises(t).map { it.sets.size to exercises[it.exerciseId] },
            onDismiss = { preview = null },
            onEdit = { preview = null; nav.template(t.id) },
            onStart = { preview = null; start(c.repo.sessionFromTemplate(t)) },
        )
    }
    pendingStart?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingStart = null },
            title = { Text("Workout in progress") },
            text = { Text("You're already in the middle of \"${active?.name}\". Start a new one and throw that away?") },
            confirmButton = {
                TextButton(onClick = { pendingStart = null; c.active.discard(); c.active.start(s); nav.liveWorkout() }) {
                    Text("Start new", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingStart = null; nav.liveWorkout() }) { Text("Resume") } },
        )
    }
    deleting?.let { t ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${t.name}\"?") },
            text = { Text("Workouts you've done from it stay in your history.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; scope.launch { c.repo.deleteTemplate(t.id) } }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ResumeCard(session: Session, onResume: () -> Unit) {
    val now = rememberNow()
    Card(
        onClick = onResume,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("In progress", style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
                Text(session.name, style = MaterialTheme.typography.titleLargeEmphasized, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatClock((now - session.startedAt) / 1000), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
            }
            Button(onClick = onResume, shapes = ButtonDefaults.shapes()) { Text("Resume") }
        }
    }
}

@Composable
private fun WeekCard(workouts: List<WorkoutEntity>, goal: Int) {
    val today = LocalDate.now()
    val start = today.weekStart()
    val days = (0L..6L).map { start.plusDays(it) }
    val worked = workouts.map { it.startedAt.toLocalDate() }.filter { it >= start }.toSet()
    val count = workouts.count { it.startedAt.toLocalDate() >= start }
    val streak = remember(workouts) { weekStreak(workouts) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(
                    progress = { (count.toFloat() / goal.coerceAtLeast(1)).coerceAtMost(1f) },
                    modifier = Modifier.size(84.dp),
                )
                Text("$count/$goal", style = MaterialTheme.typography.titleLargeEmphasized)
            }
            HSpace(20.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        count >= goal -> "Weekly goal smashed"
                        count == 0 -> "Fresh week"
                        else -> "${goal - count} more to hit your goal"
                    },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                if (streak > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalFireDepartment, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Text(
                            " $streak week streak", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                VSpace(10.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    days.forEach { d ->
                        val did = d in worked
                        val isToday = d == today
                        Box(
                            Modifier.size(26.dp)
                                .clip(if (did) MaterialShapes.Cookie6Sided.toShape() else RoundedCornerShape(50))
                                .background(
                                    when {
                                        did -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmallEmphasized,
                                color = if (did) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    t: TemplateEntity,
    lines: List<Pair<Int, String>>,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Template options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = { menu = false; onDuplicate() })
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            if (t.notes.isNotBlank()) {
                Text(t.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                VSpace(4.dp)
            }
            lines.take(6).forEach { (sets, name) ->
                Text(
                    "$sets × $name", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (lines.size > 6) Text("+ ${lines.size - 6} more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            VSpace(8.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                Text(
                    " " + (t.lastUsedAt?.relativeDay() ?: "Never done"),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun TemplatePreviewSheet(
    t: TemplateEntity,
    lines: List<Pair<Int, ExerciseEntity?>>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onStart: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(t.name, style = MaterialTheme.typography.headlineMediumEmphasized)
            t.lastUsedAt?.let { Text("Last done ${it.relativeDay().lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (t.notes.isNotBlank()) { VSpace(8.dp); Text(t.notes, style = MaterialTheme.typography.bodyMedium) }
            VSpace(16.dp)
            lines.forEach { (sets, e) ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    app.stronger.ui.components.ExerciseAvatar(e?.name ?: "?", e?.bodyPart ?: app.stronger.data.BodyPart.OTHER, size = 40.dp)
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(e?.name ?: "Exercise", style = MaterialTheme.typography.titleSmallEmphasized)
                        Text(e?.bodyPart?.label ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("$sets ${if (sets == 1) "set" else "sets"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VSpace(20.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shapes = ButtonDefaults.shapes()) { Text("Edit") }
                Button(onClick = onStart, modifier = Modifier.weight(2f), shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text(" Start workout")
                }
            }
        }
    }
}
