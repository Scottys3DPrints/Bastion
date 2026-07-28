package com.bastion.app.core.alarm

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bastion.app.BastionApp
import com.bastion.app.MainActivity
import com.bastion.app.R
import com.bastion.app.data.BastionGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** Delivers the morning Daily Brief and matures any pending guard changes. */
class BastionAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val graph = BastionGraph.from(appContext)
                val settings = graph.settings.current()

                if (settings.briefEnabled) {
                    val day = graph.journey.dayOfJourney()
                    val brief = graph.content.briefForDay(day)?.side(settings.faithMode)
                    if (brief != null) notifyBrief(appContext, day, brief.title, brief.microChallenge)
                }

                // Cooling-off requests come due here rather than needing the app open.
                graph.guard.maturedChanges().forEach { graph.guard.markApplied(it.id) }

                DailyBriefScheduler.schedule(appContext, settings.briefHour, settings.briefMinute)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyBrief(context: Context, day: Int, title: String, challenge: String) {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, BastionApp.CHANNEL_DAILY)
            .setContentTitle("Day $day — $title")
            .setContentText(challenge)
            .setStyle(Notification.BigTextStyle().bigText(challenge))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    private companion object { const val NOTIFICATION_ID = 4301 }
}

object DailyBriefScheduler {

    private const val REQUEST_CODE = 7701

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, BastionAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Falls back to an inexact alarm rather than crashing if the user has
        // not granted exact-alarm permission; a brief a few minutes late is fine.
        val canBeExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    fun cancel(context: Context) {
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, BastionAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }
}
