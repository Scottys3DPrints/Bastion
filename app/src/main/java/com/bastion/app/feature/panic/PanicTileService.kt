package com.bastion.app.feature.panic

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Hold the Line from the quick settings pull-down.
 *
 * An urge does not wait for you to find an app icon, and the moment a man is
 * hunting through his home screen is the moment he sees something else instead.
 * Two swipes and a tap, from anywhere, including the lock screen.
 */
class PanicTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Hold the Line"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PanicActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
