package app.stronger.ui.workout

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.stronger.container
import app.stronger.data.AppSettings
import app.stronger.data.ExerciseEntity
import app.stronger.data.Repository
import app.stronger.data.Session
import app.stronger.data.SessionStore
import app.stronger.data.SetEntity
import app.stronger.data.addExercises
import app.stronger.data.doneSetCount
import app.stronger.data.replaceExercise
import app.stronger.data.updateSet
import app.stronger.ui.Nav
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatClock
import app.stronger.ui.formatDate
import app.stronger.ui.exercises.ExercisePickerDialog
import app.stronger.ui.rememberNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EditorMode {
    data object Live : EditorMode
    data class Template(val id: Long) : EditorMode
    data class History(val id: Long) : EditorMode
}

/** Editing state for templates and past workouts; the live workout uses [app.stronger.data.ActiveWorkout]. */
class LocalEditor(private val repo: Repository, private val mode: EditorMode) : ViewModel(), SessionStore {
    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()
    private var endedAt = 0L

    init {
        viewModelScope.launch {
            _session.value = when (mode) {
                is EditorMode.Template -> repo.template(mode.id)?.let { t ->
                    repo.sessionFromTemplate(t).copy(templateId = t.id)
                } ?: Session(name = "New template")
                is EditorMode.History -> {
                    endedAt = repo.database.workouts().get(mode.id)?.endedAt ?: 0L
                    repo.sessionFromWorkout(mode.id)
                }
                EditorMode.Live -> null
            }
        }
    }

    override fun update(f: (Session) -> Session) = _session.update { it?.let(f) }

    suspend fun save() {
        val s = _session.value ?: return
        when (mode) {
            is EditorMode.Template -> repo.saveTemplate(mode.id.takeIf { it > 0 }, s)
            is EditorMode.History -> {
                // Every set with something in it counts when fixing up history.
                val done = s.copy(exercises = s.exercises.map { ex ->
                    ex.copy(sets = ex.sets.map { it.copy(done = it.weightKg != null || it.reps != null || it.seconds != null || it.distanceKm != null) })
                })
                repo.updateWorkout(mode.id, done, endedAt)
            }
            EditorMode.Live -> Unit
        }
    }
}

@Composable
fun SessionEditorRoute(mode: EditorMode, nav: Nav) {
    val c = LocalContext.current.container
    val store: SessionStore = if (mode == EditorMode.Live) c.active else viewModel(key = mode.toString()) { LocalEditor(c.repo, mode) }
    val session by store.session.collectAsStateWithLifecycle()
    val exercises by c.repo.exerciseIndex.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val previous by (if (mode == EditorMode.Live) c.active.previous else remember { MutableStateFlow(emptyMap<Long, List<SetEntity>>()) })
        .collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    if (mode == EditorMode.Live) {
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (settings.keepScreenOn) {
            val view = LocalView.current
            DisposableEffect(Unit) {
                view.keepScreenOn = true
                onDispose { view.keepScreenOn = false }
            }
        }
    }
    val s = session ?: return
    SessionEditorScreen(
        mode = mode,
        session = s,
        store = store,
        exercises = exercises,
        previous = previous,
        settings = settings,
        onClose = { nav.back() },
        onSave = {
            scope.launch {
                when (mode) {
                    EditorMode.Live -> c.active.finish()?.let { nav.finished(it.workoutId) }
                    else -> {
                        (store as LocalEditor).save()
                        nav.back()
                    }
                }
            }
        },
        onDiscard = {
            c.active.discard()
            nav.back()
        },
        onOpenExercise = nav::exercise,
    )
}

@Composable
fun SessionEditorScreen(
    mode: EditorMode,
    session: Session,
    store: SessionStore,
    exercises: Map<Long, ExerciseEntity>,
    previous: Map<Long, List<SetEntity>>,
    settings: AppSettings,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onOpenExercise: (Long) -> Unit,
) {
    val live = mode == EditorMode.Live
    val c = LocalContext.current.container
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Long?>(null) }
    var timerSheet by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    if (!live) BackHandler { confirmLeave = true }

    Scaffold(
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (live) IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Minimize workout") }
                    else IconButton(onClick = { confirmLeave = true }) { Icon(Icons.Filled.Close, "Discard changes") }
                },
                title = {
                    when (mode) {
                        EditorMode.Live -> Text(
                            formatClock((now - session.startedAt) / 1000),
                            style = MaterialTheme.typography.titleLargeEmphasized.copy(fontFeatureSettings = "tnum"),
                        )
                        is EditorMode.Template -> Text(if (mode.id > 0) "Edit template" else "New template")
                        is EditorMode.History -> Text("Edit workout")
                    }
                },
                actions = {
                    if (live) {
                        IconButton(onClick = { timerSheet = true }) {
                            Icon(if (session.restEndsAt != null) Icons.Filled.Timer else Icons.Outlined.Timer, "Rest timer")
                        }
                    }
                    Button(
                        onClick = { if (live) confirmFinish = true else onSave() },
                        modifier = Modifier.padding(end = 8.dp),
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (live) "Finish" else "Save") }
                },
            )
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { SessionHeader(mode, session, store) }
                items(session.exercises, key = { it.key }) { ex ->
                    val info = exercises[ex.exerciseId]
                    ExerciseBlock(
                        ex = ex,
                        index = session.exercises.indexOf(ex),
                        session = session,
                        info = info,
                        previous = previous[ex.exerciseId],
                        settings = settings,
                        mode = mode,
                        store = store,
                        exerciseNames = exercises,
                        onReplace = { replacing = ex.key },
                        onOpenExercise = { onOpenExercise(ex.exerciseId) },
                        onToggleSet = { e, st ->
                            // Live workouts complete sets through ActiveWorkout for hints, records and rest.
                            if (live) c.active.toggleSet(e, st)
                            else store.update { it.updateSet(e, st) { x -> x.copy(done = !x.done) } }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "footer") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        FilledTonalButton(
                            onClick = { picking = true },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            contentPadding = ButtonDefaults.MediumContentPadding,
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Filled.Add, null)
                            Text("  Add exercises", style = MaterialTheme.typography.titleMedium)
                        }
                        if (live) {
                            VSpace(8.dp)
                            TextButton(onClick = { confirmDiscard = true }) {
                                Text("Cancel workout", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (live) {
                AnimatedVisibility(
                    session.restEndsAt != null && session.restEndsAt > now,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                ) {
                    RestToolbar(session, now)
                }
            }
        }
    }

    if (picking) {
        ExercisePickerDialog(multi = true, title = "Add exercises", onDismiss = { picking = false }) { ids ->
            picking = false
            store.update { s -> s.addExercises(ids) { id -> if (live) previous[id]?.size ?: 1 else 3 } }
        }
    }
    replacing?.let { key ->
        ExercisePickerDialog(multi = false, title = "Replace exercise", onDismiss = { replacing = null }) { ids ->
            replacing = null
            ids.firstOrNull()?.let { id -> store.update { it.replaceExercise(key, id) } }
        }
    }
    if (timerSheet) {
        RestPickerSheet(
            title = "Start a rest timer", current = null, allowDefault = false, defaultSeconds = settings.defaultRestSeconds,
            onPick = { secs -> timerSheet = false; secs?.let { c.active.startRest(it) } },
            onDismiss = { timerSheet = false },
        )
    }
    if (confirmFinish) {
        val undone = session.exercises.sumOf { ex -> ex.sets.count { !it.done } }
        val done = session.doneSetCount
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text(if (done == 0) "Nothing logged yet" else "Finish workout?") },
            text = {
                Text(
                    when {
                        done == 0 -> "Tick off at least one set to save this workout, or cancel it instead."
                        undone > 0 -> "$undone unfinished ${if (undone == 1) "set" else "sets"} will be left out."
                        else -> "Nice work. Everything's ticked off."
                    }
                )
            },
            confirmButton = {
                if (done > 0) TextButton(onClick = { confirmFinish = false; onSave() }) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Cancel workout?") },
            text = { Text("Everything you logged in this workout will be thrown away.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text("Cancel workout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep it") } },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onClose() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; scope.launch { onSave() } }) { Text("Save") } },
        )
    }
}

@Composable
private fun SessionHeader(mode: EditorMode, session: Session, store: SessionStore) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize()) {
        BasicTextField(
            value = session.name,
            onValueChange = { v -> store.update { it.copy(name = v) } },
            textStyle = MaterialTheme.typography.headlineMediumEmphasized.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (session.name.isEmpty()) Text("Workout name", style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            },
        )
        if (mode !is EditorMode.Template) {
            Text(
                session.startedAt.formatDate("EEEE, MMM d · h:mm a"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VSpace(8.dp)
        BasicTextField(
            value = session.notes,
            onValueChange = { v -> store.update { it.copy(notes = v) } },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (session.notes.isEmpty()) Text("Add notes", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                inner()
            },
        )
    }
}

@Composable
private fun RestToolbar(session: Session, now: Long) {
    val active = LocalContext.current.container.active
    val left = (((session.restEndsAt ?: now) - now) / 1000).coerceAtLeast(0)
    val total = session.restTotalSeconds.coerceAtLeast(1)
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { active.adjustRest(-15) }) { Text("−15") }
                Text(
                    formatClock(left),
                    style = MaterialTheme.typography.displaySmallEmphasized.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                TextButton(onClick = { active.adjustRest(15) }) { Text("+15") }
                FilledTonalButton(onClick = { active.skipRest() }, shapes = ButtonDefaults.shapes()) { Text("Skip") }
            }
            LinearWavyProgressIndicator(
                progress = { 1f - left.toFloat() / total },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(width = 260.dp, height = 10.dp),
            )
        }
    }
}
