package com.bastion.app.core.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.vpn.BastionVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restores the guards after a reboot.
 *
 * A filter that quietly stops working after a restart is worse than no filter,
 * because the user believes he is covered. The accessibility service is restored
 * by the system on its own; the alarm and the DNS filter are not, so they are
 * re-armed here.
 */
class BastionBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = BastionGraph.from(appContext).settings.current()
                if (settings.briefEnabled) {
                    DailyBriefScheduler.schedule(appContext, settings.briefHour, settings.briefMinute)
                }
                if (settings.vpnFilterEnabled && BastionVpnService.prepareIntent(appContext) == null) {
                    BastionVpnService.start(appContext)
                }
                // A reboot is a common moment for a guard to quietly not come back.
                com.bastion.app.guard.GuardWatchdog.reconcile(appContext)

                // Alarms do not survive a reboot, so the lock-in watch has to
                // be re-armed or the enforcement quietly stops at the first
                // restart — which is a restart away from being no enforcement.
                LockInWatchScheduler.sync(appContext, settings.tamperLockEnabled)
                com.bastion.app.guard.GuardWatchdog.enforceIfLockedIn(appContext)
                com.bastion.app.guard.GuardWatchdog.holdFullBlocks(appContext)

                // A running lockdown outlives the process that raised it. The
                // clocks are on disk and already survive; without this, holding
                // the power button was the whole bypass.
                com.bastion.app.guard.lockdown.Lockdown.restoreAfterBoot(appContext, settings)

                // Same reasoning one step earlier: the nightly alarm did not
                // survive the reboot either, and a phone restarted at 10:01
                // would otherwise skip the whole window and set the next one for
                // tomorrow. sync re-arms it and serves whatever tonight is still
                // owed.
                ScheduledLockdownScheduler.sync(appContext, settings)
            } finally {
                pending.finish()
            }
        }
    }
}
