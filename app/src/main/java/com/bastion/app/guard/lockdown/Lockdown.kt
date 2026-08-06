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

    fun remainingSeconds(settings: Settings): Long = remainingMillis(settings) / 1_000L

    /**
     * "30 seconds", "6 hours", "3 days" — for asking and for telling.
     *
     * One formatter for every place the plan's length is spoken about, so the
     * confirmation, the summary and the message to a partner can never disagree
     * about what was actually set.
     */
    fun describe(seconds: Int): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds < 60 * 60 -> {
            val m = seconds / 60
            if (m == 1) "1 minute" else "$m minutes"
        }
        seconds < 24 * 60 * 60 -> {
            val h = seconds / 3600
            if (h == 1) "1 hour" else "$h hours"
        }
        else -> {
            val d = seconds / 86_400
            if (d == 1) "1 day" else "$d days"
        }
    }

    /** The same length, short enough for a chip: "30s", "6h", "3d". */
    fun describeShort(seconds: Int): String = when {
        seconds < 60 -> "${seconds}s"
        seconds < 60 * 60 -> "${seconds / 60}m"
        seconds < 24 * 60 * 60 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }

    private fun remainingMillis(settings: Settings): Long {
        val byWallClock = settings.lockdownUntil - System.currentTimeMillis()
        // The running lockdown's own length, which is not necessarily the
        // button's: a scheduled lockout carries its own. Falls back to the
        // button's for any lockdown started before that was recorded.
        val total = (settings.lockdownSpanSeconds.takeIf { it > 0 } ?: settings.lockdownSeconds) * 1000L
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
     * One path, whoever pulls the trigger. The nightly schedule does not get a
     * lockdown of its own with its own subtly different rules — it calls this,
     * with a different length and from a place where no one is looking at the
     * screen. Everything downstream of here reads the clocks this writes, so
     * "the same as the button" is a property of the code rather than a promise
     * in a comment.
     *
     * @param durationSeconds how long to hold for. Null means the length the
     * break-glass button is set to, which is what the button passes.
     * @param fromBackground true when nobody is looking at Bastion — an alarm,
     * a boot. Android will not let a background receiver take the screen, so the
     * wall has to be raised the long way round; see [LockdownWallActivity.raise].
     *
     * @return an SMS intent to hand to the user if they asked to tell their
     * partner — composed, never sent, like everything else in Brotherhood. Null
     * when nothing needs handing anywhere, including when [fromBackground] has
     * already delivered it as a notification, because a background process
     * cannot open a message composer and should not try.
     */
    suspend fun trigger(
        context: Context,
        durationSeconds: Int? = null,
        fromBackground: Boolean = false,
    ): Intent? {
        val graph = BastionGraph.from(context)
        val settings = graph.settings.current()

        // Extends rather than replaces: pressing it twice must never shorten a
        // lockdown already running, and neither must the schedule coming due in
        // the middle of one.
        val seconds = durationSeconds ?: settings.lockdownSeconds
        val duration = seconds * 1000L
        val until = System.currentTimeMillis() + duration
        graph.settings.setLockdownUntil(maxOf(until, settings.lockdownUntil))
        graph.settings.setLockdownEndElapsed(
            maxOf(android.os.SystemClock.elapsedRealtime() + duration, settings.lockdownEndElapsed)
        )
        // Never shortens either. The span is what the monotonic clock is sanity-
        // checked against, so a span shorter than the lockdown it guards would
        // quietly discard that clock and hand the wall clock back its bypass.
        val runningSpan = if (isActive(settings)) {
            settings.lockdownSpanSeconds.takeIf { it > 0 } ?: settings.lockdownSeconds
        } else 0
        graph.settings.setLockdownSpanSeconds(maxOf(seconds, runningSpan))

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
                        // A scheduled lockout is not something he just did, and
                        // a message claiming otherwise is a small lie told to
                        // the one person the whole feature exists to be honest
                        // with.
                        if (fromBackground) {
                            "My phone has gone into its nightly lockdown for " +
                                "${describe(seconds)}. Telling you rather than " +
                                "white-knuckling it alone."
                        } else {
                            "I've just put my phone into lockdown for " +
                                "${describe(seconds)}. Telling you rather than " +
                                "white-knuckling it alone."
                        },
                    )
            }
        } else null

        // Last, so every other guard is already in place before the phone goes
        // away. Raising the wall is what makes this a lockout rather than a
        // gesture — see LockdownWallActivity for what that does and does not
        // mean without Device Owner.
        if (settings.lockdownLockScreen) LockdownWallActivity.raise(context, fromBackground)

        // A composer nobody asked for cannot be opened over whatever is on
        // screen at 10pm, and from a receiver Android would refuse anyway. It
        // becomes a notification instead: still his message, still unsent, still
        // his to send or not.
        if (fromBackground) {
            partnerIntent?.let { LockdownNotices.offerPartnerMessage(context, it) }
            return null
        }
        return partnerIntent
    }

    /**
     * Puts the wall back after a reboot, if the clock is still running.
     *
     * Restarting the phone was the whole bypass: the activity dies with the
     * process, and the lockdown was only ever enforced by a screen that no
     * longer existed. The clocks already survive — [remainingMillis] reads them
     * from disk — so all that was missing was somebody to look.
     */
    fun restoreAfterBoot(context: Context, settings: Settings) {
        if (settings.lockdownLockScreen && isActive(settings)) {
            // fromBackground: a boot receiver is the textbook case of a launch
            // Android refuses. It went through the plain door and was quietly
            // turned away, which meant the reboot bypass was only half closed.
            LockdownWallActivity.raise(context, fromBackground = true)
        }
    }
}
