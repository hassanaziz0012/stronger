package app.stronger.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.stronger.ui.components.VSpace
import app.stronger.ui.theme.StrongerTheme

/** What Stronger reads and writes in Health Connect, shown from Health Connect's permission screens. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StrongerTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Stronger and your health data") },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                        )
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
                        listOf(
                            "What's written" to "Each workout you finish, as an exercise session with its name, time and exercises, plus the body weight and body fat you log in Stronger. Editing or deleting them in Stronger updates Health Connect too.",
                            "What's read" to "Weight and body fat from scales and other apps, so your trend is complete. Cardio sessions, steps, resting heart rate and sleep from the last week, shown on your profile.",
                            "Where it goes" to "Nowhere. Stronger has no account or server: everything stays on this phone, in Health Connect and in backups you save yourself.",
                            "Turning it off" to "Switch Health Connect off in Stronger's settings, or remove Stronger's access in Health Connect. Data already written stays there until you delete it.",
                        ).forEach { (title, body) ->
                            VSpace(16.dp)
                            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
                            VSpace(4.dp)
                            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        VSpace(24.dp)
                    }
                }
            }
        }
    }
}
