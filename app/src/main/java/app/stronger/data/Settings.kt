package app.stronger.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    val keepScreenOn: Boolean = true,
    val barWeightKg: Double = 20.0,
    /** Plate sizes in the user's unit, heaviest first. */
    val plates: List<Double> = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25),
    val weeklyGoal: Int = 3,
)

class SettingsStore(private val context: Context) {
    private object K {
        val unit = stringPreferencesKey("unit")
        val rest = intPreferencesKey("rest")
        val vibrate = booleanPreferencesKey("vibrate")
        val sound = booleanPreferencesKey("sound")
        val screenOn = booleanPreferencesKey("screen_on")
        val bar = doublePreferencesKey("bar")
        val plates = stringPreferencesKey("plates")
        val goal = intPreferencesKey("goal")
        val seeded = booleanPreferencesKey("seeded")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            unit = this[K.unit]?.let { runCatching { WeightUnit.valueOf(it) }.getOrNull() } ?: d.unit,
            defaultRestSeconds = this[K.rest] ?: d.defaultRestSeconds,
            timerVibrate = this[K.vibrate] ?: d.timerVibrate,
            timerSound = this[K.sound] ?: d.timerSound,
            keepScreenOn = this[K.screenOn] ?: d.keepScreenOn,
            barWeightKg = this[K.bar] ?: d.barWeightKg,
            plates = this[K.plates]?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: d.plates,
            weeklyGoal = this[K.goal] ?: d.weeklyGoal,
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
            p[K.screenOn] = s.keepScreenOn
            p[K.bar] = s.barWeightKg
            p[K.plates] = s.plates.joinToString(",")
            p[K.goal] = s.weeklyGoal
        }
    }

    suspend fun isSeeded(): Boolean = context.dataStore.data.first()[K.seeded] == true
    suspend fun markSeeded() { context.dataStore.edit { it[K.seeded] = true } }
}
