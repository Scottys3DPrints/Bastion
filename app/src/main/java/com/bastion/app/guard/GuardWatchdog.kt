package com.bastion.app.guard

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.bastion.app.BastionApp
import com.bastion.app.R
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.accessibility.BastionAccessibilityService

/**
 * Notices when Bastion Guard has been switched off.
 *
 * The honest limitation this exists to answer: an accessibility service cannot
 * report its own death. Disable it in system settings and it does not get a
 * callback — it simply stops running, silently, and the app carries on looking
 * protected. That gap is the difference between the README's talk of a wall and
 * what the code could actually back up.
 *
 * So intent is recorded separately from reality, and the two are compared
 * whenever there is an opportunity: on app resume, on boot, and on the daily
 * alarm. That is deliberately not continuous — a foreground service purely to
 * watch another service would cost battery for no protection, since anyone able
 * to disable Guard can dismiss a watchdog too.
 *
 * What it does NOT do is anything coercive. It cannot prevent the switch being
 * flipped, and pretending otherwise would be the dishonest version of this
 * feature. It makes the gap visible, and — if the user asked for it — makes it
 * visible to the person holding him accountable.
 */
object GuardWatchdog {

    /** How long before the same nag is allowed again. */
    private const val NAG_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val NOTIFICATION_ID = 4401

    /** True when Guard is off but the user has asked for it to be on. */
    suspend fun isBreached(context: Context): Boolean {
        val graph = BastionGraph.from(context)
        val intended = graph.settings.current().guardIntendedOn
        return intended && !BastionAccessibilityService.isEnabled(context)
    }

    /**
     * Reconciles intent with reality.
     *
     * Called from anywhere that gets to run. Records "on" the first time Guard is
     * seen running, so the user never has to declare the intent separately from
     * the act of switching it on.
     */
    suspend fun reconcile(context: Context) {
        val graph = BastionGraph.from(context)
        val settings = graph.settings.current()
        val running = BastionAccessibilityService.isEnabled(context)

        if (running) {
            if (!settings.guardIntendedOn) graph.settings.setGuardIntendedOn(true)
            clearNotification(context)
            return
        }

        if (!settings.guardIntendedOn) return

        val now = System.currentTimeMillis()
        if (now - settings.guardOffNotifiedAt < NAG_INTERVAL_MS) return

        notify(context)
        graph.settings.setGuardOffNotifiedAt(now)
    }

    /**
     * A message to the partner about the gap — written, never sent.
     *
     * Consistent with the rest of Brotherhood: Bastion composes and the man
     * presses send. An app that reports on someone behind his back is a
     * different and far more fraught product.
     */
    suspend fun partnerAlertIntent(context: Context): Intent? {
        val graph = BastionGraph.from(context)
        val partner = graph.social.partnerOnce() ?: return null
        if (!partner.shareGuardChanges) return null

        return Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${partner.contact}"))
            .putExtra(
                "sms_body",
                "Telling you rather than hiding it: Bastion Guard is off on my phone.",
            )
    }

    private fun notify(context: Context) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.bastion.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, BastionApp.CHANNEL_GUARD)
            .setContentTitle("Bastion Guard is off")
            .setContentText("Guarded feeds are open right now.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun clearNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
