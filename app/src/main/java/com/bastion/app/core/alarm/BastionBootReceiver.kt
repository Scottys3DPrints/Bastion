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
            } finally {
                pending.finish()
            }
        }
    }
}
