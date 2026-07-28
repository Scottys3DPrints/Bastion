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
    }

    companion object {
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_DAILY = "daily"
        const val CHANNEL_PARTNER = "partner"

        lateinit var instance: BastionApp
            private set
    }
}
