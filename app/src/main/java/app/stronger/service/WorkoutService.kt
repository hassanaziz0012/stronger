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
import app.stronger.data.FORGOTTEN_AFTER_MS
import app.stronger.data.Session
import app.stronger.data.SetTimer
import app.stronger.data.SetType
import app.stronger.data.estimatedEnd
import app.stronger.data.lastActivityAt
import app.stronger.ui.formatClock
import app.stronger.ui.formatDate
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
 * a Live Update on Android 16+) with the rest countdown or set stopwatch, and an alert when rest is over.
 */
class WorkoutService : Service() {
    private val scope = MainScope()
    private var watchJob: Job? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val active by lazy { container.active }
    /** The last-activity time already asked about, so "Still training?" goes out once per lull. */
    private var askedAbout = 0L

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
            ACTION_PAUSE -> active.pauseSetTimer()
            ACTION_RESUME -> active.resumeSetTimer()
            ACTION_DONE -> active.finishSetTimer()
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
            session.setTimer?.let { return@collectLatest watchSetTimer(it) }
            val endsAt = session.restEndsAt ?: return@collectLatest askIfStillTraining(session)
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

    /** Alerts when the get-ready countdown ends, so you know to start, and again at the set's goal. */
    private suspend fun watchSetTimer(timer: SetTimer) {
        if (timer.paused) return
        val goAt = timer.startedAt
        if (goAt > System.currentTimeMillis()) {
            delay(goAt - System.currentTimeMillis())
            setStarted(timer)
            active.session.value?.let { nm.notify(ONGOING_ID, build(it)) }
        }
        val target = timer.targetSeconds ?: return
        val goalAt = goAt + target * 1000L
        // A goal passed before this started watching (e.g. after a pause) was already announced.
        if (goalAt <= System.currentTimeMillis()) return
        delay(goalAt - System.currentTimeMillis())
        val s = container.settings.current()
        if (s.timerVibrate) vibrate(longArrayOf(0, 250, 120, 250))
        if (s.timerSound) playChime()
    }

    private suspend fun setStarted(timer: SetTimer) {
        val s = container.settings.current()
        if (s.timerVibrate) vibrate(longArrayOf(0, 400))
        if (s.timerSound) playChime()
        nm.notify(
            ALERT_ID,
            Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Go!")
                .setContentText(setTimerName(timer) + (timer.targetSeconds?.let { " · goal ${formatClock(it.toLong())}" } ?: ""))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .setCategory(Notification.CATEGORY_ALARM)
                .build(),
        )
    }

    private fun setTimerName(timer: SetTimer): String {
        val ex = active.session.value?.exercises?.firstOrNull { it.key == timer.exKey } ?: return "Timed set"
        val name = active.exerciseInfo(ex.exerciseId)?.name ?: "Timed set"
        val number = ex.sets.filter { it.type != SetType.WARMUP }.indexOfFirst { it.key == timer.setKey } + 1
        return if (number > 0) "$name · set $number" else name
    }

    private fun vibrate(pattern: LongArray) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /**
     * After [FORGOTTEN_AFTER_MS] without a ticked set, asks once whether the workout is still going.
     * Finishing later still ends it at the last set, so this is only a reminder.
     */
    private suspend fun askIfStillTraining(session: Session) {
        val last = session.lastActivityAt()
        if (last != askedAbout) nm.cancel(IDLE_ID)
        val at = last + FORGOTTEN_AFTER_MS * 2
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        if (last == askedAbout) return
        askedAbout = last
        val done = session.exercises.sumOf { e -> e.sets.count { it.done } }
        nm.notify(
            IDLE_ID,
            Notification.Builder(this, REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Still training?")
                .setContentText(
                    if (done == 0) "${session.name} has been open for an hour with nothing logged."
                    else "Your last set was ${(System.currentTimeMillis() - last) / 60_000} minutes ago. Finish to save it; it'll end at your last set."
                )
                .setStyle(Notification.BigTextStyle())
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    private suspend fun restOver() {
        val s = container.settings.current()
        if (s.timerVibrate) vibrate(longArrayOf(0, 250, 120, 250, 120, 450))
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
        session.estimatedEnd(active.pace.value, now)?.let { b.setSubText("Done ~" + it.formatDate("h:mm a")) }

        val timer = session.setTimer
        if (timer != null) {
            val goal = timer.targetSeconds?.let { " · goal ${formatClock(it.toLong())}" } ?: ""
            b.setContentText(setTimerName(timer) + goal)
            if (timer.paused) {
                b.setContentTitle("Paused at ${formatClock(timer.elapsedMillis(now) / 1000)}")
                    .setUsesChronometer(false).setShowWhen(false)
                    .addAction(action("Resume", ACTION_RESUME, 5))
            } else {
                b.setContentTitle(if (timer.startedAt > now) "Get ready" else "Timing set")
                    .setWhen(timer.startedAt)
                    .setChronometerCountDown(timer.startedAt > now)
                    .addAction(action("Pause", ACTION_PAUSE, 6))
            }
            b.addAction(action("Done", ACTION_DONE, 7))
        } else if (restEnds != null) {
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
            // A running stopwatch shows its chronometer in the status chip; a paused one its time.
            if (timer != null) { if (timer.paused) b.setShortCriticalText(formatClock(timer.elapsedMillis(now) / 1000)) }
            else if (restEnds == null) b.setShortCriticalText(plural(session.exercises.sumOf { e -> e.sets.count { it.done } }, "set"))
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
        nm.createNotificationChannel(NotificationChannel(REMINDER_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, "Timer alerts", NotificationManager.IMPORTANCE_HIGH).apply {
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
        const val REMINDER_CHANNEL = "reminders"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val IDLE_ID = 3
        private const val ACTION_ADD = "add"
        private const val ACTION_SUB = "sub"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_COMPLETE = "complete"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_DONE = "done"

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, WorkoutService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutService::class.java))
            context.getSystemService(NotificationManager::class.java).run { cancel(ALERT_ID); cancel(IDLE_ID) }
        }
    }
}
