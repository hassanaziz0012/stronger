package app.stronger.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stronger.container
import app.stronger.data.AppSettings
import app.stronger.data.MeasureKind
import app.stronger.data.MeasurementEntity
import app.stronger.data.WeightUnit
import app.stronger.data.smoothedTrend
import app.stronger.data.trendChange
import app.stronger.ui.Nav
import app.stronger.ui.charts.ChartPoint
import app.stronger.ui.charts.LineChart
import app.stronger.ui.clean
import app.stronger.ui.components.EmptyState
import app.stronger.ui.components.VSpace
import app.stronger.ui.formatDate
import app.stronger.ui.formatMeasure
import app.stronger.ui.measureFromDisplay
import app.stronger.ui.measureToDisplay
import app.stronger.ui.measureUnit
import app.stronger.ui.relativeDay
import app.stronger.ui.toLocalDate
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/** A signed change, e.g. "−0.8 kg"; null when it rounds to nothing. */
fun formatChange(kind: MeasureKind, delta: Double, unit: WeightUnit): String {
    val shown = measureToDisplay(kind, kotlin.math.abs(delta), unit)
    if (shown < 0.05) return "No change"
    val sign = if (delta > 0) "+" else "−"
    return if (kind == MeasureKind.BODY_FAT) "$sign${shown.clean(1)}%" else "$sign${shown.clean(1)} ${measureUnit(kind, unit)}"
}

@Composable
fun BodyScreen(nav: Nav) {
    val c = LocalContext.current.container
    val all by c.repo.measurements.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val unit = settings.unit
    var kind by rememberSaveable { mutableStateOf(MeasureKind.WEIGHT) }
    var editing by remember { mutableStateOf<MeasurementEntity?>(null) }
    val scope = rememberCoroutineScope()
    val entries = remember(all, kind) { all.filter { it.kind == kind } }
    val withData = remember(all) { all.map { it.kind }.toSet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Body") },
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = MeasurementEntity(kind = kind, takenAt = System.currentTimeMillis(), value = 0.0) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Log ${kind.label.lowercase()}") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Kinds with data first, so the ones you track are always in reach.
                    MeasureKind.entries.sortedBy { it !in withData }.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.MonitorWeight, "No ${kind.label.lowercase()} yet",
                        if (kind == MeasureKind.WEIGHT) "Weigh in a few times a week, ideally in the morning. The trend smooths out day-to-day swings."
                        else "Measure every couple of weeks, same time of day, same spot.",
                    )
                }
            } else {
                item { MeasureSummary(kind, entries, unit) }
                item { VSpace(12.dp) }
                itemsIndexed(entries, key = { _, m -> m.id }) { i, m ->
                    ListItem(
                        onClick = { if (m.hcId == null) editing = m },
                        supportingContent = { Text(if (m.hcId != null) "${m.takenAt.formatDate("MMM d, yyyy")} · from Health Connect" else m.takenAt.formatDate("EEE, MMM d, yyyy")) },
                        trailingContent = { Text(formatMeasure(kind, m.value, unit), style = MaterialTheme.typography.titleMediumEmphasized) },
                        shapes = ListItemDefaults.segmentedShapes(i, entries.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) { Text(m.takenAt.relativeDay()) }
                }
            }
        }
    }

    editing?.let { m ->
        MeasurementSheet(
            initial = m, unit = unit,
            onSave = { saved -> editing = null; kind = saved.kind; scope.launch { c.repo.saveMeasurement(saved) } },
            onDelete = if (m.id != 0L) ({ editing = null; scope.launch { c.repo.deleteMeasurement(m) } }) else null,
            onDismiss = { editing = null },
        )
    }
}

/** Latest value, trend and chart for one kind; [entries] are newest first. */
@Composable
private fun MeasureSummary(kind: MeasureKind, entries: List<MeasurementEntity>, unit: WeightUnit) {
    val asc = remember(entries) { entries.sortedBy { it.takenAt }.map { it.takenAt to it.value } }
    val trend = remember(asc, kind) { if (kind == MeasureKind.WEIGHT) smoothedTrend(asc) else asc }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp).fillMaxWidth()) {
            Text(
                if (kind == MeasureKind.WEIGHT) "Trend" else "Latest",
                style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.primary,
            )
            Text(formatMeasure(kind, trend.last().second, unit), style = MaterialTheme.typography.displaySmallEmphasized)
            val changes = listOf(7, 30, 90).mapNotNull { d -> trendChange(trend, d)?.let { "${formatChange(kind, it, unit)} in $d days" } }
            Text(
                changes.joinToString("  ·  ").ifEmpty { "Log a few more to see how it's changing" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (asc.size >= 2) {
                VSpace(16.dp)
                LineChart(
                    points = asc.takeLast(90).map { (t, v) -> ChartPoint(t, measureToDisplay(kind, v, unit), formatMeasure(kind, v, unit)) },
                    formatY = { it.clean(1) },
                    formatX = { it.formatDate("MMM d") },
                )
            }
        }
    }
}

/** Log or edit one measurement. */
@Composable
fun MeasurementSheet(
    initial: MeasurementEntity,
    unit: WeightUnit,
    onSave: (MeasurementEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf(initial.kind) }
    var field by remember {
        val start = if (initial.value > 0) measureToDisplay(initial.kind, initial.value, unit).clean(1) else ""
        mutableStateOf(TextFieldValue(start, TextRange(start.length)))
    }
    val text = field.text
    var date by remember { mutableStateOf(initial.takenAt.toLocalDate()) }
    var picking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val value = text.toDoubleOrNull()?.takeIf { it > 0 }
    fun save() {
        val v = value ?: return
        val zone = ZoneId.systemDefault()
        // Keep the original time when the day didn't change; otherwise now, or noon for a past day.
        val takenAt = when {
            date == initial.takenAt.toLocalDate() -> initial.takenAt
            date == LocalDate.now() -> System.currentTimeMillis()
            else -> date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        }
        onSave(initial.copy(kind = kind, value = measureFromDisplay(kind, v, unit), takenAt = takenAt))
    }

    // Fully expanded, so the content can sit above the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)
                .navigationBarsPadding().imePadding()
        ) {
            Text(if (initial.id == 0L) "Log measurement" else "Edit measurement", style = MaterialTheme.typography.headlineSmallEmphasized)
            VSpace(12.dp)
            if (initial.id == 0L) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeasureKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                }
                VSpace(12.dp)
            }
            OutlinedTextField(
                value = field, onValueChange = { field = it.copy(text = it.text.replace(',', '.')) },
                label = { Text(kind.label) },
                suffix = { Text(measureUnit(kind, unit)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            VSpace(12.dp)
            AssistChip(
                onClick = { picking = true },
                leadingIcon = { Icon(Icons.Outlined.CalendarToday, null) },
                label = { Text(if (date == LocalDate.now()) "Today" else date.toString()) },
            )
            VSpace(16.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(enabled = value != null, onClick = ::save) { Text("Save") }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
