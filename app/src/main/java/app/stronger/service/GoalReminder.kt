package app.stronger.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.stronger.MainActivity
import app.stronger.R
import app.stronger.container
import app.stronger.data.goalNudge
import app.stronger.data.usualStartMinute
import app.stronger.ui.toLocalDate
import app.stronger.ui.workout.firstDayOfWeek
import app.stronger.ui.workout.weekStreak
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Checks once a day, half an hour before you usually train, whether skipping today would break
 * your weekly goal or streak, and only then says so. Rescheduled on app start, boot and update.
 */
object GoalReminder {
    private const val NOTIFICATION_ID = 10
    /** Without enough history to know your habits, assume 6 pm. */
    private const val DEFAULT_MINUTE = 18 * 60
    private const val LEAD_MINUTES = 30

    suspend fun schedule(context: Context) {
        val zone = ZoneId.systemDefault()
        val starts = context.container.repo.workoutsWithSetCounts().first.map { it.startedAt }
        val minute = ((usualStartMinute(starts, zone) ?: DEFAULT_MINUTE) - LEAD_MINUTES).coerceAtLeast(6 * 60)
        val now = ZonedDateTime.now(zone)
        var at = now.toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        if (!at.isAfter(now)) at = at.plusDays(1)
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context, 0, Intent(context, GoalReminderReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    suspend fun check(context: Context) {
        val c = context.container
        val today = LocalDate.now()
        if (c.active.isActive || c.settings.nudgedOn() == today.toString()) return
        val workouts = c.repo.workoutsWithSetCounts().first
        val message = goalNudge(
            days = workouts.map { it.startedAt.toLocalDate() },
            goal = c.settings.current().weeklyGoal,
            streak = weekStreak(workouts),
            today = today,
            firstDay = firstDayOfWeek,
        ) ?: return
        c.settings.setNudgedOn(today.toString())
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WorkoutService.REMINDER_CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, WorkoutService.REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Workout today?")
                .setContentText(message)
                .setStyle(Notification.BigTextStyle())
                .setContentIntent(
                    PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                )
                .setAutoCancel(true)
                .build(),
        )
    }
}

class GoalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        context.container.scope.launch {
            try {
                if (intent.action == null) GoalReminder.check(context)
                GoalReminder.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }
}
