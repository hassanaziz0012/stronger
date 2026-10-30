package app.stronger

import android.app.Application
import android.content.Context
import app.stronger.data.ActiveWorkout
import app.stronger.data.AppDatabase
import app.stronger.data.Repository
import app.stronger.data.SettingsStore
import app.stronger.service.WorkoutService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class StrongerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val app: Application) {
    val scope = MainScope()
    val db = AppDatabase.create(app)
    val settings = SettingsStore(app)
    val repo = Repository(db, settings)
    val active = ActiveWorkout(app, repo, scope)

    init {
        scope.launch { repo.seedIfNeeded() }
        scope.launch {
            active.session.map { it != null }.distinctUntilChanged().collect { running ->
                if (running) WorkoutService.start(app) else WorkoutService.stop(app)
            }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as StrongerApp).container
