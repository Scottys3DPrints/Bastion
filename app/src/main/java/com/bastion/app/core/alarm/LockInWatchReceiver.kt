package com.bastion.app.core.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.GuardWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The short-interval check that runs only while the user is locked in.
 *
 * The existing watchdog reconciles on app resume, on boot and on the daily
 * alarm, which is right for making a breach *visible* but far too slow to
 * enforce one. Worse, the service's own `onDestroy` — the fastest signal there
 * is — is not guaranteed to run at all: a force-stop skips it entirely, which
 * is exactly what someone determined to get around the lock would do.
 *
 * So while locked in, and only while locked in, Bastion also asks every minute.
 * That is a real battery cost and it is opt-in by definition: it exists because
 * the user asked to be held to something. It stops the moment the lock is
 * lifted.
 */
class LockInWatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = BastionGraph.from(appContext).settings.current()
                if (!settings.tamperLockEnabled) {
                    LockInWatchScheduler.cancel(appContext)
                    return@launch
                }
                GuardWatchdog.reconcile(appContext)
                GuardWatchdog.enforceIfLockedIn(appContext)
                // Re-armed each time rather than using a repeating alarm, so
                // Doze deferring one tick can never silently end the series.
                LockInWatchScheduler.schedule(appContext)
            } finally {
                pending.finish()
            }
        }
    }
}

object LockInWatchScheduler {

    private const val REQUEST_CODE = 7702
    private const val INTERVAL_MS = 60_000L

    /** Starts the loop if locked in; stops it otherwise. Safe to call anywhere. */
    fun sync(context: Context, lockedIn: Boolean) {
        if (lockedIn) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact and allow-while-idle: a minute of drift costs nothing here,
        // and asking for exactness every minute is the kind of thing that gets
        // an app killed by the system rather than tolerated by it.
        runCatching {
            alarms.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pendingIntent(context),
            )
        }
    }

    fun cancel(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context))
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, LockInWatchReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
