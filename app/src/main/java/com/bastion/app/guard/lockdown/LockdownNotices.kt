package com.bastion.app.guard.lockdown

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.bastion.app.BastionApp
import com.bastion.app.R

/**
 * The two things a lockdown has to say when nobody is watching the screen.
 *
 * The break-glass button never needed either: a man who has just pressed it is
 * holding the phone, so the wall can be raised directly and the message to his
 * partner can be opened in front of him. A lockdown that starts at ten o'clock
 * on its own has neither luxury — Android will not let a background receiver
 * take the screen or open a composer, and it is right not to.
 *
 * So both become notifications, which is the one channel a background process is
 * allowed. Neither is decoration: the first is how the wall gets raised at all
 * on a phone that is idle, and the second is the difference between telling
 * someone and quietly not.
 */
object LockdownNotices {

    private const val WALL_NOTIFICATION = 4310
    private const val PARTNER_NOTIFICATION = 4311
    private const val REQUEST_WALL = 7710
    private const val REQUEST_PARTNER = 7711

    /**
     * Asks the system to raise the wall on Bastion's behalf.
     *
     * High importance and a full-screen intent, because this is the same class
     * of thing as an alarm going off: it is the screen the user asked to be put
     * in front of, at the time he asked for it. Where the platform declines to
     * honour that it degrades to a heads-up he can tap, and it is marked ongoing
     * so it cannot be swiped away and forgotten — the wall is not optional, only
     * its delivery is.
     */
    fun callToWall(context: Context, wall: Intent) {
        runCatching {
            val pending = PendingIntent.getActivity(
                context,
                REQUEST_WALL,
                wall,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, BastionApp.CHANNEL_LOCKDOWN)
                .setContentTitle("Lockdown has started")
                .setContentText("The plan you made. Tap to see the time left.")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(WALL_NOTIFICATION, notification)
        }
    }

    /** Taken down the moment the wall is actually up. */
    fun clearWallCall(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(WALL_NOTIFICATION)
        }
    }

    /**
     * Offers the message, composed and unsent.
     *
     * Same rule as everywhere else in Brotherhood: Bastion never sends anything
     * to anyone. It writes the message and hands him the composer — here via a
     * tap, because the alternative from a receiver is either nothing at all or
     * an app opening a texting screen over whatever he was doing.
     */
    fun offerPartnerMessage(context: Context, sms: Intent) {
        runCatching {
            val pending = PendingIntent.getActivity(
                context,
                REQUEST_PARTNER,
                sms.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = Notification.Builder(context, BastionApp.CHANNEL_PARTNER)
                .setContentTitle("Tell your partner?")
                .setContentText("Tap to send the message you wrote in advance.")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(PARTNER_NOTIFICATION, notification)
        }
    }
}
