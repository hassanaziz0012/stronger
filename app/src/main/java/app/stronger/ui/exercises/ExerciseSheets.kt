package app.stronger.ui.exercises

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.BodyPart
import app.stronger.data.Equipment
import app.stronger.data.ExerciseEntity
import app.stronger.data.ExerciseUsage
import app.stronger.ui.components.ExerciseAvatar
import app.stronger.ui.components.SectionHeader
import app.stronger.ui.components.VSpace
import kotlinx.coroutines.launch

fun List<ExerciseEntity>.filtered(query: String, part: BodyPart?): List<ExerciseEntity> {
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return filter { e ->
        (part == null || e.bodyPart == part) &&
            words.all { w -> w in e.name.lowercase() || w in e.equipment.label.lowercase() || w in e.bodyPart.label.lowercase() }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search exercises") {
    TextField(
        value = query, onValueChange = onQuery,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "Clear search") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun BodyPartChips(selected: BodyPart?, onSelect: (BodyPart?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") }) }
        items(BodyPart.entries) { p ->
            FilterChip(selected = selected == p, onClick = { onSelect(if (selected == p) null else p) }, label = { Text(p.label) })
        }
    }
}

/** Full-screen dialogs get their own window, which starts out with light-on-light status icons. */
@Composable
fun LightSystemBars() {
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    LaunchedEffect(dark) {
        (view.parent as? DialogWindowProvider)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

/** Full-screen exercise picker. Single mode returns on the first tap. */
@Composable
fun ExercisePickerDialog(
    multi: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onPicked: (List<Long>) -> Unit,
) {
    val repo = LocalContext.current.container.repo
    val exercises by repo.exercises.collectAsStateWithLifecycle(emptyList())
    val usage by repo.exerciseUsage.collectAsStateWithLifecycle(emptyList<ExerciseUsage>())
    var query by remember { mutableStateOf("") }
    var part by remember { mutableStateOf<BodyPart?>(null) }
    val picked = remember { mutableStateListOf<Long>() }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        LightSystemBars()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") } },
                    actions = { TextButton(onClick = { creating = true }) { Text("New") } },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(picked.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    ExtendedFloatingActionButton(
                        onClick = { onPicked(picked.toList()) },
                        icon = { Icon(Icons.Filled.Add, null) },
                        text = { Text(if (picked.size == 1) "Add 1 exercise" else "Add ${picked.size} exercises") },
                    )
                }
            },
        ) { padding ->
            val filtered = remember(exercises, query, part) { exercises.filtered(query, part) }
            val recent = remember(exercises, usage) {
                val byId = exercises.associateBy { it.id }
                usage.sortedByDescending { it.lastAt }.mapNotNull { byId[it.exerciseId] }.take(6)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
                item { SearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                item { BodyPartChips(part, { part = it }, Modifier.padding(bottom = 8.dp)) }
                fun row(e: ExerciseEntity, keyPrefix: String) = item(key = keyPrefix + e.id) {
                    PickerRow(e, e.id in picked) {
                        if (!multi) onPicked(listOf(e.id))
                        else if (e.id in picked) picked.remove(e.id) else picked.add(e.id)
                    }
                }
                if (query.isBlank() && part == null && recent.isNotEmpty()) {
                    item { SectionHeader("Recent") }
                    recent.forEach { row(it, "r") }
                    item { SectionHeader("All exercises") }
                }
                filtered.forEach { row(it, "a") }
            }
        }
        if (creating) {
            ExerciseEditorSheet(
                initial = null,
                initialName = query,
                onDismiss = { creating = false },
                onSave = { e ->
                    scope.launch {
                        val id = repo.createExercise(e)
                        creating = false
                        if (!multi) onPicked(listOf(id)) else picked.add(id)
                    }
                },
            )
        }
    }
}

@Composable
private fun PickerRow(e: ExerciseEntity, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        leadingContent = {
            AnimatedContent(selected, transitionSpec = { scaleIn() togetherWith scaleOut() }, label = "pick") { sel ->
                if (sel) {
                    Box(
                        Modifier.size(48.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.onPrimary) }
                } else {
                    ExerciseAvatar(e.name, e.bodyPart)
                }
            }
        },
        supportingContent = { Text("${e.bodyPart.label} · ${e.equipment.label}") },
        colors = ListItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.padding(horizontal = 8.dp).clip(MaterialTheme.shapes.large),
    ) { Text(e.name) }
}

/** Create or edit an exercise. */
@Composable
fun ExerciseEditorSheet(
    initial: ExerciseEntity?,
    onDismiss: () -> Unit,
    onSave: (ExerciseEntity) -> Unit,
    initialName: String = "",
) {
    var name by remember { mutableStateOf(initial?.name ?: initialName.trim()) }
    var part by remember { mutableStateOf(initial?.bodyPart ?: BodyPart.CHEST) }
    var equipment by remember { mutableStateOf(initial?.equipment ?: Equipment.BARBELL) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()
        ) {
            Text(if (initial == null) "New exercise" else "Edit exercise", style = MaterialTheme.typography.headlineSmallEmphasized)
            VSpace(16.dp)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            VSpace(20.dp)
            Text("Body part", style = MaterialTheme.typography.titleSmallEmphasized)
            VSpace(8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyPart.entries.forEach { p -> FilterChip(selected = part == p, onClick = { part = p }, label = { Text(p.label) }) }
            }
            VSpace(16.dp)
            Text("Category", style = MaterialTheme.typography.titleSmallEmphasized)
            Text(
                "Decides what you log: weight and reps, reps only, time, or distance.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8.dp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Equipment.entries.forEach { q -> FilterChip(selected = equipment == q, onClick = { equipment = q }, label = { Text(q.label) }) }
            }
            VSpace(16.dp)
            OutlinedTextField(
                value = notes, onValueChange = { notes = it }, label = { Text("Notes (optional)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text),
            )
            VSpace(24.dp)
            Button(
                onClick = {
                    val base = initial ?: ExerciseEntity(name = "", bodyPart = part, equipment = equipment)
                    onSave(base.copy(name = name.trim(), bodyPart = part, equipment = equipment, notes = notes.trim()))
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
