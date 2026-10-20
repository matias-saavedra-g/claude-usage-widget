package com.usage.claudewidget.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.usage.claudewidget.R
import com.usage.claudewidget.data.Storage
import com.usage.claudewidget.data.UsageSnapshot
import com.usage.claudewidget.ui.ClaudeApp
import com.usage.claudewidget.work.RefreshScheduler

/**
 * Notifies when the 5-hour window resets. An alarm is set for the reset time after each
 * successful fetch; [notifyIfDue] also runs before each fetch to catch alarms lost to a reboot.
 */
object ResetNotifier {
    private const val CHANNEL_ID = "usage_reset"
    private const val NOTIFICATION_ID = 5
    private const val ALARM_REQUEST = 5

    // Alarms may fire a little early; and a reset more than one window late is no longer news.
    private const val EARLY_SLACK_MS = 60_000L
    private const val MAX_LATE_MS = 5 * 60 * 60 * 1000L

    /** Arm (or cancel) the reset alarm for a freshly fetched snapshot. */
    fun schedule(context: Context, s: UsageSnapshot) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        val resetAt = s.fiveHour.resetsAtEpochMs
        if (s.fiveHour.utilization <= 0f || resetAt <= System.currentTimeMillis()) {
            am.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, resetAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, resetAt, pi)
        }
    }

    /** Post the notification if the stored 5-hour window has reset and we haven't told the user yet. */
    fun notifyIfDue(context: Context, now: Long = System.currentTimeMillis()) {
        val s = Storage.get(context)
        val reset = s.fiveHourReset
        if (reset <= 0L || s.fiveHourUtil <= 0f || s.notifiedReset == reset) return
        if (now < reset - EARLY_SLACK_MS || now > reset + MAX_LATE_MS) return
        s.notifiedReset = reset
        post(context)
    }

    private fun post(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, ClaudeApp.launchIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_star)
            .setContentTitle(context.getString(R.string.reset_title))
            .setContentText(context.getString(R.string.reset_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reset_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ALARM_REQUEST, Intent(context, ResetAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Fires at the 5-hour reset time: notify, then refresh so the widget shows the new window. */
class ResetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ResetNotifier.notifyIfDue(context)
        RefreshScheduler.refreshNow(context)
    }
}
