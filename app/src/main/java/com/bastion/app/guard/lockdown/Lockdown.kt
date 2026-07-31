package com.bastion.app.guard.lockdown

import android.content.Context
import android.content.Intent
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.vpn.BastionVpnService

/**
 * The break-glass plan.
 *
 * One button, one decision made in advance, carried out the moment it is
 * pressed. The whole point is that nothing here needs deciding in the moment —
 * a man reaching for this is not in a state to configure anything.
 *
 * Once running it cannot be called off. That is the feature, not an oversight:
 * a lockdown you can cancel thirty seconds later is a gesture, and the man who
 * would cancel it is exactly the man who pressed it. It ends when its clock
 * runs out.
 *
 * What it honestly cannot do: power the phone off. That needs a signature-level
 * permission held by the operating system, and no installable app has it. The
 * screen lock is the closest real equivalent.
 */
object Lockdown {

    /**
     * Running if EITHER clock still says so.
     *
     * The wall clock alone was a one-tap bypass: rolling the device date forward
     * past the end time ended a lockdown instantly, needing no permission at
     * all. elapsedRealtime() is monotonic and cannot be moved by the user, so it
     * is checked alongside — whichever says "still running" wins.
     *
     * The elapsed anchor is meaningless after a reboot, when the counter resets
     * to near zero and would otherwise read as an enormous remaining time. It is
     * therefore ignored unless the remainder it implies is within the lockdown's
     * own length.
     */
    fun isActive(settings: Settings): Boolean = remainingMillis(settings) > 0

    fun remainingMinutes(settings: Settings): Long = remainingMillis(settings) / 60_000L

    private fun remainingMillis(settings: Settings): Long {
        val byWallClock = settings.lockdownUntil - System.currentTimeMillis()
        val total = settings.lockdownHours * 60L * 60L * 1000L
        // runCatching because SystemClock is Android framework: on the JVM, where
        // the lockdown arithmetic is unit-tested, it throws. Falling back to 0
        // leaves the wall clock in charge, which is the pre-existing behaviour.
        val elapsedNow = runCatching { android.os.SystemClock.elapsedRealtime() }.getOrDefault(0L)
        val byElapsed = (settings.lockdownEndElapsed - elapsedNow)
            .takeIf { elapsedNow > 0L && settings.lockdownEndElapsed > 0L && it <= total }
            ?: 0L
        return maxOf(byWallClock, byElapsed).coerceAtLeast(0)
    }

    /**
     * Starts a lockdown and carries out every step the user chose.
     *
     * @return an SMS intent to hand to the user if they asked to tell their
     * partner — composed, never sent, like everything else in Brotherhood.
     */
    suspend fun trigger(context: Context): Intent? {
        val graph = BastionGraph.from(context)
        val settings = graph.settings.current()

        // Extends rather than replaces: pressing it twice must never shorten a
        // lockdown already running.
        val duration = settings.lockdownHours * 60L * 60L * 1000L
        val until = System.currentTimeMillis() + duration
        graph.settings.setLockdownUntil(maxOf(until, settings.lockdownUntil))
        graph.settings.setLockdownEndElapsed(
            maxOf(android.os.SystemClock.elapsedRealtime() + duration, settings.lockdownEndElapsed)
        )

        if (settings.lockdownGrayscale) graph.settings.setGrayscale(true)

        if (settings.lockdownFilter) {
            graph.settings.setVpnEnabled(true)
            // Only starts if consent was already given; the VPN dialog cannot be
            // raised from here, and a lockdown must not stall waiting on a prompt.
            if (BastionVpnService.prepareIntent(context) == null) {
                BastionVpnService.start(context)
            }
        }

        val partnerIntent = if (settings.lockdownTellPartner) {
            graph.social.partnerOnce()?.let { partner ->
                Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${partner.contact}"))
                    .putExtra(
                        "sms_body",
                        "I've just put my phone into lockdown for ${settings.lockdownHours} hours. " +
                            "Telling you rather than white-knuckling it alone.",
                    )
            }
        } else null

        // Last, so everything else is already in place when the screen goes dark.
        if (settings.lockdownLockScreen) BastionDeviceAdmin.lockNow(context)

        return partnerIntent
    }
}
