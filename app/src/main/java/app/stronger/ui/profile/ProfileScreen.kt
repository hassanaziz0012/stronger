package app.stronger.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.AppSettings
import app.stronger.data.BackupManager
import app.stronger.data.Pr
import app.stronger.data.StrongCsvImporter
import app.stronger.data.WeightUnit
import app.stronger.ui.Nav
import app.stronger.ui.charts.BarChart
import app.stronger.ui.clean
import app.stronger.ui.components.EmptyState
import app.stronger.ui.components.SectionHeader
import app.stronger.ui.components.StatRow
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatClock
import app.stronger.ui.formatCompact
import app.stronger.ui.formatDate
import app.stronger.ui.formatSet
import app.stronger.ui.fromKg
import app.stronger.ui.plural
import app.stronger.ui.relativeDay
import app.stronger.ui.toKg
import app.stronger.ui.toLocalDate
import app.stronger.ui.workout.PlateCalculatorSheet
import app.stronger.ui.workout.RestPickerSheet
import app.stronger.ui.workout.weekStart
import app.stronger.ui.workout.weekStreak
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(nav: Nav) {
    val c = LocalContext.current.container
    val workouts by c.repo.workouts.collectAsStateWithLifecycle(emptyList())
    val sets by c.repo.allSets.collectAsStateWithLifecycle(emptyList())
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var plates by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val unit = settings.unit

    val weeks = remember(workouts) {
        val thisWeek = LocalDate.now().weekStart()
        val counts = workouts.groupingBy { it.startedAt.toLocalDate().weekStart() }.eachCount()
        (11 downTo 0).map { i ->
            val w = thisWeek.minusWeeks(i.toLong())
            w.formatShort() to (counts[w] ?: 0).toDouble()
        }
    }
    val starts = remember(workouts) { workouts.associateBy { it.id } }
    val recentRecords = remember(sets, starts) {
        sets.filter { it.prMask != 0 }.sortedByDescending { starts[it.workoutId]?.startedAt ?: 0 }.take(8)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Profile") },
                actions = { IconButton(onClick = { nav.settings() }) { Icon(Icons.Filled.Settings, "Settings") } },
                scrollBehavior = scroll,
            )
        },
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(20.dp).fillMaxWidth()) {
                        Text("All time", style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary)
                        VSpace(8.dp)
                        StatRow(
                            workouts.size.toString() to "Workouts",
                            "${workouts.sumOf { it.durationMillis } / 3_600_000}h" to "Time",
                            "${formatCompact(unit.fromKg(sets.sumOf { it.volumeKg }))} ${unit.label}" to "Lifted",
                            "${weekStreak(workouts)}w" to "Streak",
                        )
                    }
                }
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Workouts per week", style = MaterialTheme.typography.titleMediumEmphasized)
                        Text(
                            "Last 12 weeks · goal ${settings.weeklyGoal}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        VSpace(12.dp)
                        BarChart(weeks, goal = settings.weeklyGoal.toDouble())
                    }
                }
            }
            item { SectionHeader("Recent records", Modifier.padding(start = 0.dp)) }
            if (recentRecords.isEmpty()) {
                item { EmptyState(Icons.Filled.EmojiEvents, "No records yet", "Beat a previous best and it'll be celebrated here.") }
            }
            recentRecords.forEach { s ->
                item(key = "pr${s.id}") {
                    val e = exercises[s.exerciseId]
                    ListItem(
                        onClick = { nav.exercise(s.exerciseId) },
                        leadingContent = { Icon(Icons.Filled.EmojiEvents, null, tint = MaterialTheme.colorScheme.tertiary) },
                        supportingContent = {
                            Text("${formatSet(s, e?.tracking ?: app.stronger.data.TrackingType.WEIGHT_REPS, unit)} · ${Pr.labels(s.prMask).joinToString()}")
                        },
                        trailingContent = { Text(starts[s.workoutId]?.startedAt?.relativeDay() ?: "", style = MaterialTheme.typography.labelMedium) },
                        shapes = ListItemDefaults.segmentedShapes(recentRecords.indexOf(s), recentRecords.size),
                        colors = segmentedColors(),
                    ) { Text(e?.name ?: "Exercise", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            item { SectionHeader("Tools", Modifier.padding(start = 0.dp)) }
            item {
                ListItem(
                    onClick = { plates = true },
                    leadingContent = { Icon(Icons.Filled.Calculate, null) },
                    supportingContent = { Text("How to load the bar for any weight") },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    shapes = ListItemDefaults.segmentedShapes(0, 1),
                    colors = segmentedColors(),
                ) { Text("Plate calculator") }
            }
        }
    }
    if (plates) PlateCalculatorSheet(null, settings) { plates = false }
}

private fun LocalDate.formatShort() = "${monthValue}/${dayOfMonth}"

// ---------------- Settings ----------------

private val kgPlates = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
private val lbPlates = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)

@Composable
fun SettingsScreen(nav: Nav) {
    val context = LocalContext.current
    val c = context.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    var restSheet by remember { mutableStateOf(false) }
    var barDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }

    fun update(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }

    suspend fun readText(uri: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
    }

    val importCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Importing your Strong history…"
            message = runCatching {
                val r = StrongCsvImporter(c.repo).import(readText(uri), settings.unit)
                "Import complete" to buildString {
                    append("${plural(r.workouts, "workout")} and ${plural(r.sets, "set")} imported.")
                    if (r.newExercises > 0) append(" ${plural(r.newExercises, "exercise")} new to Stronger ${if (r.newExercises == 1) "was" else "were"} added as custom.")
                    if (r.skipped > 0) append(" ${plural(r.skipped, "workout")} already here ${if (r.skipped == 1) "was" else "were"} skipped.")
                }
            }.getOrElse { "Import failed" to (it.message ?: "Couldn't read that file.") }
            busy = null
        }
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Backing up…"
            message = runCatching {
                val json = BackupManager(c.repo).export()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
                "Backup saved" to "Everything's in that file. Restore it from here on any phone."
            }.getOrElse { "Backup failed" to (it.message ?: "Couldn't write the file.") }
            busy = null
        }
    }
    val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            group("Workouts") {
                listOf(
                    {
                        Row1(Icons.Outlined.FitnessCenter, "Weight unit", null, it) {
                            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                WeightUnit.entries.forEachIndexed { i, u ->
                                    ToggleButton(
                                        checked = settings.unit == u,
                                        onCheckedChange = {
                                            update { s -> s.copy(unit = u, plates = if (u == WeightUnit.KG) kgPlates.take(7) else lbPlates) }
                                        },
                                        shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                    ) { Text(u.label) }
                                }
                            }
                        }
                    },
                    {
                        Row1(Icons.Outlined.Timer, "Default rest", formatClock(settings.defaultRestSeconds.toLong()), it, onClick = { restSheet = true })
                    },
                    {
                        Row1(Icons.Outlined.Flag, "Weekly goal", "${settings.weeklyGoal} workouts a week", it) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { update { s -> s.copy(weeklyGoal = (s.weeklyGoal - 1).coerceAtLeast(1)) } }) { Icon(Icons.Filled.Remove, "Fewer") }
                                Text(settings.weeklyGoal.toString(), style = MaterialTheme.typography.titleMediumEmphasized)
                                IconButton(onClick = { update { s -> s.copy(weeklyGoal = (s.weeklyGoal + 1).coerceAtMost(7)) } }) { Icon(Icons.Filled.Add, "More") }
                            }
                        }
                    },
                    {
                        Row1(Icons.Outlined.LightMode, "Keep screen on", "While a workout is open", it) {
                            Switch(settings.keepScreenOn, { v -> update { s -> s.copy(keepScreenOn = v) } })
                        }
                    },
                )
            }
            group("Rest timer alert") {
                listOf(
                    { Row1(Icons.Outlined.Vibration, "Vibrate", null, it) { Switch(settings.timerVibrate, { v -> update { s -> s.copy(timerVibrate = v) } }) } },
                    { Row1(Icons.Outlined.VolumeUp, "Sound", null, it) { Switch(settings.timerSound, { v -> update { s -> s.copy(timerSound = v) } }) } },
                )
            }
            group("Plate calculator") {
                listOf(
                    {
                        Row1(
                            Icons.Filled.Calculate, "Bar weight",
                            "${settings.unit.fromKg(settings.barWeightKg).clean(1)} ${settings.unit.label}", it, onClick = { barDialog = true },
                        )
                    },
                    {
                        val options = if (settings.unit == WeightUnit.KG) kgPlates else lbPlates
                        ListItem(
                            supportingContent = {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    options.forEach { p ->
                                        val on = settings.plates.any { kotlin.math.abs(it - p) < 1e-6 }
                                        FilterChip(
                                            selected = on,
                                            onClick = {
                                                update { s -> s.copy(plates = if (on) s.plates.filterNot { kotlin.math.abs(it - p) < 1e-6 } else (s.plates + p).sortedDescending()) }
                                            },
                                            label = { Text(p.clean()) },
                                        )
                                    }
                                }
                            },
                            shapes = ListItemDefaults.segmentedShapes(it.first, it.second),
                            colors = segmentedColors(),
                        ) { Text("Plates you have (${settings.unit.label})") }
                    },
                )
            }
            group("Your data") {
                listOf(
                    {
                        Row1(Icons.Outlined.FileDownload, "Import from Strong", "In Strong: Settings → Export data, then pick the CSV here", it,
                            onClick = { importCsv.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) })
                    },
                    {
                        Row1(Icons.Outlined.Backup, "Back up", "Save everything to a file", it,
                            onClick = { exportBackup.launch("stronger-backup-${LocalDate.now()}.json") })
                    },
                    {
                        Row1(Icons.Outlined.Restore, "Restore a backup", "Replaces everything on this phone", it,
                            onClick = { restoreBackup.launch(arrayOf("application/json", "application/octet-stream", "*/*")) })
                    },
                )
            }
        }
    }

    if (restSheet) {
        RestPickerSheet(
            title = "Default rest", current = settings.defaultRestSeconds, allowDefault = false, defaultSeconds = settings.defaultRestSeconds,
            onPick = { v -> restSheet = false; v?.let { update { s -> s.copy(defaultRestSeconds = it) } } },
            onDismiss = { restSheet = false },
        )
    }
    if (barDialog) {
        var text by remember { mutableStateOf(settings.unit.fromKg(settings.barWeightKg).clean(1)) }
        AlertDialog(
            onDismissRequest = { barDialog = false },
            title = { Text("Bar weight") },
            text = {
                OutlinedTextField(
                    text, { text = it.replace(',', '.') }, singleLine = true, suffix = { Text(settings.unit.label) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toDoubleOrNull()?.let { v -> update { s -> s.copy(barWeightKg = s.unit.toKg(v)) } }
                    barDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { barDialog = false }) { Text("Cancel") } },
        )
    }
    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = { Text("All workouts, templates and exercises on this phone are replaced by the ones in the backup.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        busy = "Restoring…"
                        message = runCatching {
                            val b = BackupManager(c.repo).restore(readText(uri))
                            "Restored" to "${plural(b.workouts.size, "workout")}, ${plural(b.templates.size, "template")} and ${plural(b.exercises.size, "exercise")} are back."
                        }.getOrElse { "Restore failed" to (it.message ?: "That file isn't a Stronger backup.") }
                        busy = null
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
    busy?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Text("  $text", style = MaterialTheme.typography.bodyLarge)
                }
            },
        )
    }
    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

private val Icons.Outlined.Flag: ImageVector get() = Icons.Filled.EmojiEvents

/** A titled run of segmented list rows; each row gets its (index, count) for shape. */
private fun LazyListScope.group(title: String, rows: () -> List<@Composable (Pair<Int, Int>) -> Unit>) {
    item(key = "t$title") { SectionHeader(title, Modifier.padding(start = 0.dp)) }
    val list = rows()
    list.forEachIndexed { i, row -> item(key = "$title$i") { row(i to list.size) } }
}

@Composable
private fun Row1(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    position: Pair<Int, Int>,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shapes = ListItemDefaults.segmentedShapes(position.first, position.second)
    val colors = segmentedColors()
    if (onClick != null) {
        ListItem(
            onClick = onClick,
            leadingContent = { Icon(icon, null) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = trailing,
            shapes = shapes, colors = colors,
        ) { Text(title) }
    } else {
        ListItem(
            leadingContent = { Icon(icon, null) },
            supportingContent = subtitle?.let { { Text(it) } },
            trailingContent = trailing,
            shapes = shapes, colors = colors,
        ) { Text(title) }
    }
}

/** Segmented rows sit on a tonal container so each one reads as its own surface. */
@Composable
private fun segmentedColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
