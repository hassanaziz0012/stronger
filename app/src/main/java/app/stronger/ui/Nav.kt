package app.stronger.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.stronger.container
import app.stronger.data.Session
import app.stronger.ui.components.HSpace
import app.stronger.ui.exercises.ExerciseDetailScreen
import app.stronger.ui.exercises.ExercisesScreen
import app.stronger.ui.history.HistoryScreen
import app.stronger.ui.history.WorkoutDetailScreen
import app.stronger.ui.profile.ProfileScreen
import app.stronger.ui.profile.SettingsScreen
import app.stronger.ui.workout.EditorMode
import app.stronger.ui.workout.FinishedScreen
import app.stronger.ui.workout.SessionEditorRoute
import app.stronger.ui.workout.WorkoutHomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable object ProfileTab
@Serializable object HistoryTab
@Serializable object WorkoutTab
@Serializable object ExercisesTab
@Serializable object LiveWorkout
@Serializable data class TemplateEditor(val id: Long = -1)
@Serializable data class ExerciseDetail(val id: Long)
@Serializable data class WorkoutDetail(val id: Long)
@Serializable data class EditWorkout(val id: Long)
@Serializable data class Finished(val id: Long)
@Serializable object SettingsRoute

/** Navigation actions screens may take, so they never touch the controller directly. */
class Nav(private val c: NavHostController) {
    fun back() = c.popBackStack()
    fun liveWorkout() = c.navigate(LiveWorkout) { launchSingleTop = true }
    fun template(id: Long = -1) = c.navigate(TemplateEditor(id))
    fun exercise(id: Long) = c.navigate(ExerciseDetail(id))
    fun workout(id: Long) = c.navigate(WorkoutDetail(id))
    fun editWorkout(id: Long) = c.navigate(EditWorkout(id))
    fun settings() = c.navigate(SettingsRoute)
    fun finished(id: Long) = c.navigate(Finished(id)) { popUpTo(LiveWorkout) { inclusive = true } }
    fun tab(route: Any) = c.navigate(route) {
        popUpTo(c.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private data class Tab(val route: Any, val label: String, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    Tab(ProfileTab, "Profile", Icons.Filled.Person, Icons.Outlined.Person),
    Tab(HistoryTab, "History", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth),
    Tab(WorkoutTab, "Workout", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    Tab(ExercisesTab, "Exercises", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter),
)

@Composable
fun StrongerNav(openWorkoutRequests: StateFlow<Int>) {
    val controller = rememberNavController()
    val nav = remember(controller) { Nav(controller) }
    val active = LocalContext.current.container.active
    val session by active.session.collectAsStateWithLifecycle()
    val openRequests by openWorkoutRequests.collectAsStateWithLifecycle()
    LaunchedEffect(openRequests) { if (openRequests > 0 && active.isActive) nav.liveWorkout() }

    val entry by controller.currentBackStackEntryAsState()
    val dest = entry?.destination
    val onTab = tabs.any { t -> dest?.hasRoute(t.route::class) == true }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(onTab, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    AnimatedVisibility(session != null && dest?.hasRoute(WorkoutTab::class) != true, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        session?.let { WorkoutMiniBar(it, nav::liveWorkout) }
                    }
                    ShortNavigationBar {
                        tabs.forEach { t ->
                            val selected = dest?.hasRoute(t.route::class) == true
                            ShortNavigationBarItem(
                                selected = selected,
                                onClick = { nav.tab(t.route) },
                                icon = { Icon(if (selected) t.selected else t.unselected, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            controller,
            startDestination = WorkoutTab,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            enterTransition = { fadeIn() + scaleIn(initialScale = 0.96f) },
            exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() },
            popExitTransition = { fadeOut() },
        ) {
            composable<ProfileTab> { ProfileScreen(nav) }
            composable<HistoryTab> { HistoryScreen(nav) }
            composable<WorkoutTab> { WorkoutHomeScreen(nav) }
            composable<ExercisesTab> { ExercisesScreen(nav) }
            composable<LiveWorkout>(
                enterTransition = { slideInVertically { it / 3 } + fadeIn() },
                popExitTransition = { slideOutVertically { it / 3 } + fadeOut() },
            ) { SessionEditorRoute(EditorMode.Live, nav) }
            composable<TemplateEditor> { SessionEditorRoute(EditorMode.Template(it.toRoute<TemplateEditor>().id), nav) }
            composable<EditWorkout> { SessionEditorRoute(EditorMode.History(it.toRoute<EditWorkout>().id), nav) }
            composable<ExerciseDetail> { ExerciseDetailScreen(it.toRoute<ExerciseDetail>().id, nav) }
            composable<WorkoutDetail> { WorkoutDetailScreen(it.toRoute<WorkoutDetail>().id, nav) }
            composable<Finished> { FinishedScreen(it.toRoute<Finished>().id, nav) }
            composable<SettingsRoute> { SettingsScreen(nav) }
        }
    }
}

@Composable
fun rememberNow(intervalMillis: Long = 1000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(intervalMillis - value % intervalMillis)
        }
    }
    return now
}

@Composable
private fun WorkoutMiniBar(session: Session, onOpen: () -> Unit) {
    val now = rememberNow()
    val restLeft = session.restEndsAt?.let { ((it - now) / 1000).takeIf { s -> s >= 0 } }
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(session.name, style = MaterialTheme.typography.titleMediumEmphasized, maxLines = 1)
                Text(
                    formatClock((now - session.startedAt) / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (restLeft != null) {
                Icon(Icons.Filled.Timer, null, Modifier.size(20.dp))
                HSpace(6.dp)
                Text(formatClock(restLeft), style = MaterialTheme.typography.titleMediumEmphasized)
            }
        }
    }
}
