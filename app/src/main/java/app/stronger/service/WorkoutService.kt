package app.stronger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.VibratorManager
import app.stronger.MainActivity
import app.stronger.R
import app.stronger.container
import app.stronger.data.Session
import app.stronger.ui.plural
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps a workout alive while the app is in the background: an ongoing notification (promoted to
 * a Live Update on Android 16+) with the rest countdown, and an alert when rest is over.
 */
class WorkoutService : Service() {
    private val scope = MainScope()
    private var watchJob: Job? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val active by lazy { container.active }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ADD -> active.adjustRest(15)
            ACTION_SUB -> active.adjustRest(-15)
            ACTION_SKIP -> active.skipRest()
            ACTION_COMPLETE -> active.completeNextSet()
        }
        val session = active.session.value
        if (session == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(ONGOING_ID, build(session), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (watchJob == null) watchJob = scope.launch { watch() }
        return START_STICKY
    }

    private suspend fun watch() {
        active.session.collectLatest { session ->
            if (session == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@collectLatest
            }
            nm.notify(ONGOING_ID, build(session))
            val endsAt = session.restEndsAt ?: return@collectLatest
            // Tick the progress bar while resting, then alert once rest is over.
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < endsAt) {
                delay(1000)
                nm.notify(ONGOING_ID, build(session))
            }
            if (currentCoroutineContext().isActive) {
                // A rest that ran out while the app was dead isn't worth buzzing about.
                if (System.currentTimeMillis() - endsAt < 5_000) restOver()
                active.skipRest()
            }
        }
    }

    private suspend fun restOver() {
        val s = container.settings.current()
        if (s.timerVibrate) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250, 120, 450), -1)
            )
        }
        if (s.timerSound) playChime()
        nm.notify(
            ALERT_ID,
            Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Rest over")
                .setContentText(nextSetText(active.session.value) ?: "Time for your next set")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setTimeoutAfter(30_000)
                .setCategory(Notification.CATEGORY_ALARM)
                .build(),
        )
    }

    /**
     * Plays the notification sound as a notification event (alarm usage mutes media on headphones
     * while it plays) and asks other apps to duck rather than pause until it's done.
     */
    private fun playChime() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        val player = MediaPlayer()
        val done = { player.release(); audio.abandonAudioFocusRequest(focus); Unit }
        runCatching {
            player.setAudioAttributes(attrs)
            player.setDataSource(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            player.setOnCompletionListener { done() }
            player.setOnErrorListener { _, _, _ -> done(); true }
            player.prepare()
            audio.requestAudioFocus(focus)
            player.start()
        }.onFailure { done() }
    }

    private fun nextSetText(session: Session?): String? {
        session ?: return null
        for (ex in session.exercises) {
            val index = ex.sets.indexOfFirst { !it.done }
            if (index < 0) continue
            val name = active.exerciseInfo(ex.exerciseId)?.name ?: "Next exercise"
            return "$name · set ${index + 1} of ${ex.sets.size}"
        }
        return null
    }

    private fun build(session: Session): Notification {
        val now = System.currentTimeMillis()
        val restEnds = session.restEndsAt?.takeIf { it > now }
        val next = nextSetText(session)
        val b = Notification.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setCategory(Notification.CATEGORY_WORKOUT)
            .setUsesChronometer(true)
            .setShowWhen(true)

        if (restEnds != null) {
            val total = session.restTotalSeconds.coerceAtLeast(1)
            val left = ((restEnds - now) / 1000).toInt().coerceIn(0, total)
            b.setContentTitle("Resting")
                .setContentText(next?.let { "Up next: $it" } ?: session.name)
                .setWhen(restEnds)
                .setChronometerCountDown(true)
                .addAction(action("−15s", ACTION_SUB, 1))
                .addAction(action("+15s", ACTION_ADD, 2))
                .addAction(action("Skip", ACTION_SKIP, 3))
            if (Build.VERSION.SDK_INT >= 36) {
                b.setStyle(
                    Notification.ProgressStyle()
                        .setProgressSegments(listOf(Notification.ProgressStyle.Segment(total)))
                        .setProgress(total - left)
                        .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification))
                )
            }
        } else {
            b.setContentTitle(session.name)
                .setContentText(next ?: "All sets done. Finish when you're ready")
                .setWhen(session.startedAt)
                .setChronometerCountDown(false)
            if (next != null) b.addAction(action("Complete set", ACTION_COMPLETE, 4))
        }
        if (Build.VERSION.SDK_INT >= 36) {
            b.setRequestPromotedOngoing(true)
            if (restEnds == null) b.setShortCriticalText(plural(session.exercises.sumOf { e -> e.sets.count { it.done } }, "set"))
        }
        return b.build()
    }

    private fun action(title: String, action: String, code: Int) = Notification.Action.Builder(
        null, title,
        PendingIntent.getService(this, code, Intent(this, WorkoutService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_WORKOUT, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannels() {
        nm.createNotificationChannel(
            NotificationChannel(ONGOING_CHANNEL, "Workout in progress", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // Sound and vibration are played directly so settings can toggle them.
                enableVibration(false)
            }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ONGOING_CHANNEL = "workout"
        private const val ALERT_CHANNEL = "rest_timer"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val ACTION_ADD = "add"
        private const val ACTION_SUB = "sub"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_COMPLETE = "complete"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkoutService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
    }
}
