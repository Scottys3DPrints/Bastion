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
    // 4401 belongs to the accessibility service's own guard-down notice; sharing
    // it meant whichever posted second silently replaced the first.
    private const val NOTIFICATION_ID = 4402
    /** The locked-in wall's own notice; separate so clearing one never clears the other. */
    private const val NOTIFICATION_WALL = 4403
    private const val REQUEST_WALL = 4403

    /** True when Guard is off but the user has asked for it to be on. */
    suspend fun isBreached(context: Context): Boolean {
        val graph = BastionGraph.from(context)
        val intended = graph.settings.current().guardIntendedOn
        return intended && !BastionAccessibilityService.isEnabled(context)
    }

    /**
     * True when Private DNS has been turned off after having been set.
     *
     * Deliberately symmetrical with [isBreached]. Private DNS is the stronger
     * of the two content filters — it works below the app layer and survives
     * Bastion being killed — but it is a system setting, so Bastion can neither
     * switch it on nor hold it down. Noticing is the whole of what it can do,
     * and noticing is most of what matters.
     */
    suspend fun isDnsBreached(context: Context): Boolean {
        val settings = BastionGraph.from(context).settings.current()
        return settings.dnsIntendedOn &&
            !com.bastion.app.guard.vpn.DnsFilters.privateDnsIsSet(context)
    }

    /** Which layer, if any, is down. Guard first — it is the broader one. */
    suspend fun downLayer(context: Context): String? = when {
        isBreached(context) -> LAYER_GUARD
        isDnsBreached(context) -> LAYER_DNS
        else -> null
    }

    const val LAYER_GUARD = "guard"
    const val LAYER_DNS = "dns"

    /** How long the current breach has been running; 0 when Guard is up. */
    suspend fun breachDurationMillis(context: Context): Long {
        val since = BastionGraph.from(context).settings.current().guardOffSince
        return if (since == 0L) 0L else (System.currentTimeMillis() - since).coerceAtLeast(0L)
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

        reconcileDns(context, settings)

        // The Device Owner restrictions track the lock, on every reconcile, so
        // the phone's actual state cannot drift away from the setting that is
        // supposed to govern it — including after a reboot or an update.
        com.bastion.app.guard.lockdown.DeviceOwner.apply(context, settings.tamperLockEnabled)

        if (running) {
            if (!settings.guardIntendedOn) graph.settings.setGuardIntendedOn(true)
            // The breach is over, so its record ends with it. Both flags are
            // cleared here and only here, which is what makes the next breach a
            // new one rather than a continuation of this one.
            if (settings.guardOffSince != 0L) graph.settings.setGuardOffSince(0L)
            if (settings.lockdownBreachAlerted) graph.settings.setLockdownBreachAlerted(false)
            clearNotification(context)
            // Only when nothing is down; Private DNS may still be off even
            // though Guard came back, and clearing here would drop the notice
            // for a breach that is still live.
            if (downLayer(context) == null) clearWallNotification(context)
            return
        }

        if (!settings.guardIntendedOn) return

        val now = System.currentTimeMillis()
        // Stamped before the nag interval is consulted: the start of a breach is
        // a fact about the breach, not about whether we happen to be nagging.
        // Recording it after the early return meant a breach that began inside
        // a quiet window was never dated at all.
        if (settings.guardOffSince == 0L) graph.settings.setGuardOffSince(now)

        if (now - settings.guardOffNotifiedAt < NAG_INTERVAL_MS) return

        val since = settings.guardOffSince.takeIf { it != 0L } ?: now
        notify(context, now - since)
        graph.settings.setGuardOffNotifiedAt(now)
    }

    /**
     * Records that Private DNS is set, and notices when it stops being.
     *
     * Same shape as the Guard reconcile: intent is recorded the first time the
     * thing is seen working, so the user never declares it separately, and the
     * gap between intent and reality is what counts as a breach.
     */
    private suspend fun reconcileDns(
        context: Context,
        settings: com.bastion.app.data.prefs.Settings,
    ) {
        val graph = BastionGraph.from(context)
        val hostname = com.bastion.app.guard.vpn.DnsFilters.privateDnsHostname(context)

        if (hostname != null) {
            if (!settings.dnsIntendedOn) graph.settings.setDnsIntendedOn(true)
            if (settings.dnsHostname != hostname) graph.settings.setDnsHostname(hostname)
            if (settings.dnsOffSince != 0L) graph.settings.setDnsOffSince(0L)
            return
        }

        if (!settings.dnsIntendedOn) return
        if (settings.dnsOffSince == 0L) {
            graph.settings.setDnsOffSince(System.currentTimeMillis())
        }
    }

    /**
     * A deliberate "I am done with Private DNS", so the nag can be honoured.
     *
     * Without this the intent would be a one-way latch and someone who
     * genuinely stopped using Private DNS would be walled forever — the same
     * trap [standDown] exists to avoid for Guard.
     */
    suspend fun standDownDns(context: Context) {
        val graph = BastionGraph.from(context)
        graph.settings.setDnsIntendedOn(false)
        graph.settings.setDnsOffSince(0L)
    }

    /**
     * Puts the wall in front of a locked-in user whose Guard has gone down.
     *
     * Separate from [reconcile] because it is the one response that interrupts
     * rather than informs, and it must only ever happen while locked in. That
     * is the whole bargain of locking in: the user asked, in a calmer hour, to
     * be made to work for this.
     *
     * Deliberately not called when the lock is off — an app that seizes the
     * screen because a setting changed, without being asked to, is malware
     * with good intentions.
     */
    suspend fun enforceIfLockedIn(context: Context) {
        val settings = BastionGraph.from(context).settings.current()
        if (!settings.tamperLockEnabled) return
        val layer = downLayer(context) ?: return

        val wall = Intent(context, com.bastion.app.guard.lockdown.GuardDownActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(com.bastion.app.guard.lockdown.GuardDownActivity.EXTRA_LAYER, layer)

        // Works only when Bastion is already the app in front. From the
        // one-minute alarm it does not, and the system says so out loud:
        //
        //   ActivityTaskManager: Background activity launch blocked!
        //   callingUidProcState: RECEIVER
        //
        // Android 10 onwards refuses to let a background receiver take over the
        // screen, which is a rule worth having and one Bastion does not get an
        // exception from.
        runCatching { context.startActivity(wall) }

        // So the background path goes through the one door the platform does
        // leave open: a full-screen intent. Where the system honours it the
        // wall comes up by itself; where it does not — Android 14 reserves that
        // for calling and alarm apps — it lands as a high-priority heads-up
        // that opens the wall on a tap. A notification you have to tap is
        // weaker than a screen you cannot leave, and it is what is actually
        // available.
        runCatching {
            val pending = PendingIntent.getActivity(
                context,
                REQUEST_WALL,
                wall,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val title = if (layer == LAYER_DNS) {
                "You're locked in — Private DNS is off"
            } else {
                "You're locked in — Guard is off"
            }
            val notification = Notification.Builder(context, BastionApp.CHANNEL_GUARD)
                .setContentTitle(title)
                .setContentText("Tap to turn it back on.")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pending)
                .setFullScreenIntent(pending, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_WALL, notification)
        }
    }

    /** Taken down the moment Guard is back, from [reconcile]. */
    private fun clearWallNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_WALL)
        }
    }

    /**
     * A deliberate "I am done with Guard", as opposed to a breach.
     *
     * [guardIntendedOn] was a one-way latch: set true the first time Guard ran
     * and never cleared. Someone who genuinely stopped using the feature was
     * therefore nagged every six hours forever, with the only escape being to
     * clear the app's data — which takes the whole journey with it. A tool that
     * can only be entered is a trap, and a nag no one can honour is one people
     * learn to ignore, which costs the warnings that do matter.
     *
     * The friction stays where it belongs: the caller gates this behind the
     * partner passcode when the lock is armed. What this does not do is pretend
     * the decision never happened — it clears the intent, not the history.
     */
    suspend fun standDown(context: Context) {
        val graph = BastionGraph.from(context)
        graph.settings.setGuardIntendedOn(false)
        graph.settings.setGuardOffSince(0L)
        graph.settings.setLockdownBreachAlerted(false)
        clearNotification(context)
    }

    /**
     * Turning Guard off mid-lockdown is the one breach worth its own alert.
     *
     * Every other breach is ambiguous — a system update, a factory reset of
     * accessibility settings, a genuine mistake. This one is not: a lockdown is
     * running precisely because the user asked, in advance, not to be able to
     * undo it, and disabling Guard is the way around that. It is the moment the
     * partner exists for.
     *
     * Returns the message to send, once per breach, or null. Like everything in
     * Brotherhood it is composed and handed over — Bastion never sends.
     */
    suspend fun lockdownBreachAlert(context: Context): Intent? {
        val graph = BastionGraph.from(context)
        val settings = graph.settings.current()

        if (!com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) return null
        if (!isBreached(context)) return null
        if (settings.lockdownBreachAlerted) return null

        val partner = graph.social.partnerOnce() ?: return null
        if (!partner.shareGuardChanges) return null

        graph.settings.setLockdownBreachAlerted(true)
        val left = com.bastion.app.guard.lockdown.Lockdown.remainingSeconds(settings)
        val remaining = when {
            left >= 3600 -> "${left / 3600}h"
            left >= 60 -> "${left / 60}m"
            else -> "${left}s"
        }
        return Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${partner.contact}"))
            .putExtra(
                "sms_body",
                "Being straight with you: I turned Bastion Guard off while a lockdown " +
                    "was still running — $remaining left on it. That's the one I asked " +
                    "you to hold me to.",
            )
    }

    /**
     * A message to the partner about the gap — written, never sent.
     *
     * Consistent with the rest of Brotherhood: Bastion composes and the man
     * presses send. An app that reports on someone behind his back is a
     * different and far more fraught product.
     */
    suspend fun partnerAlertIntent(context: Context): Intent? {
        // A breach during a lockdown outranks the generic message, and says so
        // in its own words.
        lockdownBreachAlert(context)?.let { return it }

        val graph = BastionGraph.from(context)
        val partner = graph.social.partnerOnce() ?: return null
        if (!partner.shareGuardChanges) return null

        val down = describeDuration(breachDurationMillis(context))
        return Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${partner.contact}"))
            .putExtra(
                "sms_body",
                "Telling you rather than hiding it: Bastion Guard is off on my phone — $down.",
            )
    }

    private fun notify(context: Context, downFor: Long) {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, com.bastion.app.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, BastionApp.CHANNEL_GUARD)
            .setContentTitle("Bastion Guard is off")
            .setContentText("Guarded feeds have been open ${describeDuration(downFor)}.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * "since just now" / "for 3 hours" / "for 2 days".
     *
     * Deliberately coarse. The number that matters is the order of magnitude —
     * whether this happened a moment ago or has quietly been true all week — and
     * a precise "for 3h 14m" reads like telemetry rather than a nudge.
     */
    fun describeDuration(millis: Long): String {
        val minutes = millis / 60_000L
        return when {
            minutes < 5 -> "since just now"
            minutes < 60 -> "for $minutes minutes"
            minutes < 120 -> "for an hour"
            minutes < 24 * 60 -> "for ${minutes / 60} hours"
            minutes < 48 * 60 -> "for a day"
            else -> "for ${minutes / (24 * 60)} days"
        }
    }

    private fun clearNotification(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
