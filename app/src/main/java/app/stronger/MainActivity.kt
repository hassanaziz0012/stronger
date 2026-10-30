package app.stronger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.flow.MutableStateFlow
import app.stronger.service.WorkoutService
import app.stronger.ui.StrongerNav
import app.stronger.ui.theme.StrongerTheme

class MainActivity : ComponentActivity() {
    /** Bumped when the notification asks to open the live workout. */
    private val openWorkoutRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            StrongerTheme {
                StrongerNav(openWorkoutRequests)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A workout restored after process death needs its service back.
        if (container.active.isActive) WorkoutService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_WORKOUT, false) == true) {
            openWorkoutRequests.value++
            intent.removeExtra(EXTRA_OPEN_WORKOUT)
        }
    }

    companion object {
        const val EXTRA_OPEN_WORKOUT = "open_workout"
    }
}
