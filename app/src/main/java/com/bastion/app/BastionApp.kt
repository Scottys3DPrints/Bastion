package com.bastion.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.bastion.app.data.BastionGraph

class BastionApp : Application() {

    lateinit var graph: BastionGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        graph = BastionGraph(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD, getString(R.string.notif_channel_guard), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_guard_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, getString(R.string.notif_channel_daily), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.notif_channel_daily_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PARTNER, getString(R.string.notif_channel_partner), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.notif_channel_partner_desc) }
        )
        // High, and its own channel rather than riding on Guard's.
        //
        // A full-screen intent is only honoured on a high-importance channel, and
        // this is the one notification whose whole job is to put a screen in
        // front of someone — the wall, when a scheduled lockdown starts on a
        // phone nobody is holding. On Guard's low-importance channel it would
        // arrive silently in the shade, which is indistinguishable from the
        // feature not working.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LOCKDOWN, getString(R.string.notif_channel_lockdown), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.notif_channel_lockdown_desc) }
        )
    }

    companion object {
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_DAILY = "daily"
        const val CHANNEL_PARTNER = "partner"
        const val CHANNEL_LOCKDOWN = "lockdown"

        lateinit var instance: BastionApp
            private set
    }
}
