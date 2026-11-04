package app.stronger.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore("settings")

@Serializable
data class AppSettings(
    val unit: WeightUnit = WeightUnit.KG,
    val defaultRestSeconds: Int = 120,
    val timerVibrate: Boolean = true,
    val timerSound: Boolean = true,
    /** Seconds to get into position before a set timer starts counting; 0 starts it at once. */
    val setCountdownSeconds: Int = 3,
    val keepScreenOn: Boolean = true,
    val barWeightKg: Double = 20.0,
    /** Plate sizes in the user's unit, heaviest first. */
    val plates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25),
    val weeklyGoal: Int = 3,
    /** Sync workouts and body measurements with Health Connect. */
    val healthConnect: Boolean = false,
    /** Ask for reps in reserve after each working set. */
    val trackEffort: Boolean = true,
    /** When the deload suggestion was last dismissed. */
    val deloadDismissedAt: Long = 0,
    /** The month ("2026-10") whose recap card was last dismissed. */
    val recapDismissed: String = "",
)

class SettingsStore(private val context: Context) {
    private object K {
        val unit = stringPreferencesKey("unit")
        val rest = intPreferencesKey("rest")
        val vibrate = booleanPreferencesKey("vibrate")
        val sound = booleanPreferencesKey("sound")
        val setCountdown = intPreferencesKey("set_countdown")
        val screenOn = booleanPreferencesKey("screen_on")
        val bar = doublePreferencesKey("bar")
        val plates = stringPreferencesKey("plates")
        val goal = intPreferencesKey("goal")
        val health = booleanPreferencesKey("health_connect")
        val effort = booleanPreferencesKey("track_effort")
        val deloadDismissed = longPreferencesKey("deload_dismissed")
        val recapDismissed = stringPreferencesKey("recap_dismissed")
        val nudgedOn = stringPreferencesKey("nudged_on")
        val seeded = booleanPreferencesKey("seeded")
        val hcToken = stringPreferencesKey("hc_changes_token")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            unit = this[K.unit]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: d.unit,
            defaultRestSeconds = this[K.rest] ?: d.defaultRestSeconds,
            timerVibrate = this[K.vibrate] ?: d.timerVibrate,
            timerSound = this[K.sound] ?: d.timerSound,
            setCountdownSeconds = this[K.setCountdown] ?: d.setCountdownSeconds,
            keepScreenOn = this[K.screenOn] ?: d.keepScreenOn,
            barWeightKg = this[K.bar] ?: d.barWeightKg,
            plates = this[K.plates]?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: d.plates,
            weeklyGoal = this[K.goal] ?: d.weeklyGoal,
            healthConnect = this[K.health] ?: d.healthConnect,
            trackEffort = this[K.effort] ?: d.trackEffort,
            deloadDismissedAt = this[K.deloadDismissed] ?: d.deloadDismissedAt,
            recapDismissed = this[K.recapDismissed] ?: d.recapDismissed,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toSettings())
            p[K.unit] = s.unit.name
            p[K.rest] = s.defaultRestSeconds
            p[K.vibrate] = s.timerVibrate
            p[K.sound] = s.timerSound
            p[K.setCountdown] = s.setCountdownSeconds
            p[K.screenOn] = s.keepScreenOn
            p[K.bar] = s.barWeightKg
            p[K.plates] = s.plates.joinToString(",")
            p[K.goal] = s.weeklyGoal
            p[K.health] = s.healthConnect
            p[K.effort] = s.trackEffort
            p[K.deloadDismissed] = s.deloadDismissedAt
            p[K.recapDismissed] = s.recapDismissed
        }
    }

    suspend fun isSeeded(): Boolean = context.dataStore.data.first()[K.seeded] == true
    suspend fun markSeeded() { context.dataStore.edit { it[K.seeded] = true } }

    /** The day ("2026-10-30") the last weekly-goal reminder went out, so there's one a day at most. */
    suspend fun nudgedOn(): String? = context.dataStore.data.first()[K.nudgedOn]
    suspend fun setNudgedOn(day: String) { context.dataStore.edit { it[K.nudgedOn] = day } }

    /** Health Connect changes token for incremental body-measurement imports. */
    suspend fun healthToken(): String? = context.dataStore.data.first()[K.hcToken]
    suspend fun setHealthToken(token: String?) {
        context.dataStore.edit { if (token == null) it.remove(K.hcToken) else it[K.hcToken] = token }
    }
}
