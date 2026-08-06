package com.bastion.app.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.lockdown.Lockdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The lockdown that does not wait to be asked.
 *
 * The break-glass button answers the bad moment that arrives unannounced. This
 * answers the one that arrives at the same time every night — and a man who
 * knows his own worst hour should not have to be awake to it, or honest with
 * himself at ten o'clock, to be covered at eleven.
 *
 * It runs exactly the same plan. Not "the same behaviour" as a matter of two
 * code paths kept carefully in step, but the same call: [Lockdown.trigger],
 * with the schedule's own length. Everything that enforces a lockdown — the
 * accessibility service closing guarded apps, the filter, the drained colour,
 * the wall — reads the clocks that call writes and cannot tell which of the two
 * wrote them.
 */
class ScheduledLockdownReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = BastionGraph.from(appContext).settings.current()
                if (!settings.scheduledLockdownEnabled) {
                    // Switched off since this alarm was set. Nothing to do, and
                    // nothing to re-arm.
                    ScheduledLockdownScheduler.cancel(appContext)
                    return@launch
                }

                ScheduledLockdownScheduler.markWindowServed(appContext, settings)
                Lockdown.trigger(
                    appContext,
                    durationSeconds = settings.scheduledLockdownSeconds,
                    fromBackground = true,
                )

                // Re-armed for tomorrow here rather than set as a repeating
                // alarm, so Doze deferring one night can never silently end the
                // series — the same reason the lock-in watch re-arms itself.
                ScheduledLockdownScheduler.schedule(appContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * When the nightly lockout is due, and what to do about the ones that were
 * missed.
 *
 * The arithmetic is deliberately separate from the alarm plumbing and takes the
 * current time as an argument, so the two questions that actually matter — "when
 * is the next one" and "did I sleep through one" — can be answered in a unit
 * test rather than by setting a phone's clock forward and waiting.
 */
object ScheduledLockdown {

    /** The next time hour:minute comes round, strictly after [now]. */
    fun nextRunAfter(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /**
     * The most recent time hour:minute came round, at or before [now].
     *
     * Yesterday's, before the hour has arrived today. This is what identifies a
     * window, so that "have I already served this one" is a comparison rather
     * than a guess.
     */
    fun windowStartAtOrBefore(now: LocalDateTime, hour: Int, minute: Int): LocalDateTime {
        val today = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today.minusDays(1) else today
    }

    /**
     * How much of a window that has already started is still owed, in seconds.
     *
     * Alarms do not survive a reboot, and a phone that is off at ten o'clock
     * simply never hears the one that was set. Without this, turning the phone
     * off and on again at 10:01 would be the entire bypass — the cheapest one
     * there is, and the first anyone tries.
     *
     * So on every boot and every app resume Bastion asks whether it is standing
     * inside a window it never opened, and serves the remainder if it is. It
     * never extends a window past its end: a lockout missed entirely, or caught
     * with a minute to run, is a lockout of a minute — the promise was a time of
     * night, not a length owed no matter when it is noticed.
     *
     * @return 0 when [now] is outside the window, so callers can treat "nothing
     * owed" and "nothing to do" as the same thing.
     */
    fun catchUpSeconds(now: LocalDateTime, hour: Int, minute: Int, durationSeconds: Int): Int {
        if (durationSeconds <= 0) return 0
        val started = windowStartAtOrBefore(now, hour, minute)
        val elapsed = Duration.between(started, now).seconds
        val remaining = durationSeconds - elapsed
        return if (remaining in 1..durationSeconds.toLong()) remaining.toInt() else 0
    }
}

object ScheduledLockdownScheduler {

    private const val REQUEST_CODE = 7703

    /**
     * Puts the alarm in step with the settings, and closes any window that
     * opened while nobody was listening. Safe to call from anywhere, as often
     * as you like.
     */
    suspend fun sync(context: Context, settings: Settings) {
        if (!settings.scheduledLockdownEnabled) {
            cancel(context)
            return
        }
        schedule(context, settings)
        catchUp(context, settings)
    }

    fun schedule(context: Context, settings: Settings) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = ScheduledLockdown.nextRunAfter(
            LocalDateTime.now(),
            settings.scheduledLockdownHour,
            settings.scheduledLockdownMinute,
        )
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Exact where the platform allows it. A brief arriving a few minutes
        // late costs nothing, which is why the daily alarm shrugs at inexact
        // delivery; a lockout that opens at 10:20 instead of 10:00 has left the
        // twenty minutes it existed for wide open. It still falls back rather
        // than crashing — a late lockout beats none — and the settings card says
        // plainly when the permission is missing.
        val canBeExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent(context))
            }
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }

    /**
     * Writes off the window now open, if there is one, as already dealt with.
     *
     * Called when the alarm actually fires, and again whenever the user changes
     * the schedule. Both are the same statement — "this window has been handled"
     * — and it is the second that makes the setting safe to touch: without it,
     * switching the nightly lockout on at 10:30 would have been read by
     * [catchUp] as a ten o'clock window missed by half an hour, and would have
     * locked the phone in the act of being configured. Nobody would do that
     * twice, and the un-cancellable half hour would have taught them not to.
     */
    suspend fun markWindowServed(context: Context, settings: Settings) {
        val start = ScheduledLockdown.windowStartAtOrBefore(
            LocalDateTime.now(),
            settings.scheduledLockdownHour,
            settings.scheduledLockdownMinute,
        )
        BastionGraph.from(context).settings.setScheduledLockdownLastRun(
            start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
    }

    /**
     * Serves a window that started while the phone was off, or while an alarm
     * was being dropped by whatever the manufacturer calls its battery saver.
     *
     * Alarms do not survive a reboot, so without this, holding the power button
     * at 10:01 was the whole bypass — and it is the cheapest one there is.
     *
     * Two things stop it from firing when it should not. A lockdown already
     * running means there is nothing to catch up on; [Lockdown.trigger] only
     * ever extends, so the clock would be safe, but the partner would be told a
     * second time about a lockdown he has already been told about. And a window
     * already marked served is one the schedule has honoured or been told to
     * skip — see [markWindowServed].
     */
    private suspend fun catchUp(context: Context, settings: Settings) {
        if (Lockdown.isActive(settings)) return
        val now = LocalDateTime.now()
        val owed = ScheduledLockdown.catchUpSeconds(
            now,
            settings.scheduledLockdownHour,
            settings.scheduledLockdownMinute,
            settings.scheduledLockdownSeconds,
        )
        if (owed <= 0) return

        val start = ScheduledLockdown.windowStartAtOrBefore(
            now,
            settings.scheduledLockdownHour,
            settings.scheduledLockdownMinute,
        ).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (start <= settings.scheduledLockdownLastRun) return

        BastionGraph.from(context).settings.setScheduledLockdownLastRun(start)
        Lockdown.trigger(context, durationSeconds = owed, fromBackground = true)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, ScheduledLockdownReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
