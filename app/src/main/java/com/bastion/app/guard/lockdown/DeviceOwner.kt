package com.bastion.app.guard.lockdown

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager

/**
 * The doors Android *will* let an app close.
 *
 * Be clear about the one it will not: **no ordinary app can stop a user
 * disabling its own accessibility service.** Google keeps that toggle reachable
 * on purpose, for safety, and there is no public API to hold it down. Any app
 * promising otherwise is either lying or requires root, and Bastion's whole
 * credibility rests on not doing either.
 *
 * So the goal is not an impossible hard lock. It is to close every *other*
 * escape hatch, which turns out to be most of them. Previous reviews found that
 * "just uninstall it" and "clear app data" defeated a lockdown entirely — the
 * countdown, the guarded apps and the partner's passcode all went with the
 * data. Device Owner closes exactly those.
 *
 * Becoming Device Owner needs one command, run once, on a device with no
 * accounts added yet:
 *
 * ```
 * adb shell dpm set-device-owner com.bastion.app/.guard.lockdown.BastionDeviceAdmin
 * ```
 *
 * Everything here is a no-op without it, and everything is gated on being
 * locked in — the restrictions go on when the cooling-off lock is armed and
 * come off the moment it is lifted. A blocker that keeps its claws in after you
 * have honestly decided to stop using it is a different kind of app.
 */
object DeviceOwner {

    /**
     * Built from the running package rather than hardcoded.
     *
     * A debug build is `com.bastion.app.debug`, so a literal
     * `com.bastion.app/...` command copied out of it fails with a confusing
     * "component not found" — the worst kind of instruction, one that looks
     * authoritative and does nothing.
     */
    fun setupCommand(context: Context): String =
        "adb shell dpm set-device-owner " +
            "${context.packageName}/com.bastion.app.guard.lockdown.BastionDeviceAdmin"

    /** The restrictions that only make sense while locked in. */
    private val LOCKED_IN_RESTRICTIONS = listOf(
        // The obvious escape: wipe the phone, lose the lockdown with it.
        UserManager.DISALLOW_FACTORY_RESET,
        // The non-obvious one. Safe boot starts Android with third-party
        // services disabled, which is how a determined user turns off an
        // accessibility service without ever opening Settings.
        UserManager.DISALLOW_SAFE_BOOT,
        // A second user profile is a clean phone with none of this on it.
        UserManager.DISALLOW_ADD_USER,
    )

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isDeviceOwnerApp(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Brings the restrictions into line with whether the user is locked in.
     *
     * Safe to call as often as you like — it is idempotent, and it is called on
     * every resume and boot so the phone's state cannot drift away from the
     * setting that is supposed to govern it.
     *
     * @return true if the restrictions are currently applied.
     */
    fun apply(context: Context, lockedIn: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = BastionDeviceAdmin.component(context)

        return runCatching {
            // Blocking uninstall is the one that closes both "uninstall it" and
            // "clear its data" — Android restricts both for a Device Owner app.
            dpm.setUninstallBlocked(admin, context.packageName, lockedIn)

            LOCKED_IN_RESTRICTIONS.forEach { restriction ->
                if (lockedIn) dpm.addUserRestriction(admin, restriction)
                else dpm.clearUserRestriction(admin, restriction)
            }

            // Screen pinning for the "Guard is down" wall. Allow-listing the
            // package is what lets it pin itself without the system's own
            // "Pin this screen?" prompt, which is dismissible and would defeat
            // the point.
            dpm.setLockTaskPackages(
                admin,
                if (lockedIn) arrayOf(context.packageName) else emptyArray(),
            )
            lockedIn
        }.getOrDefault(false)
    }

    /** Whether the pinned wall can actually pin itself. */
    fun canPinScreen(context: Context): Boolean = isDeviceOwner(context)

    /**
     * What Bastion can honestly claim right now, in one line.
     *
     * Deliberately never says "cannot be turned off". The accessibility toggle
     * is always reachable, and a promise the app cannot keep would poison every
     * promise it can.
     */
    fun statusLine(context: Context, lockedIn: Boolean): String = when {
        !isDeviceOwner(context) ->
            "Uninstall and factory reset are still open. One adb command closes them."
        lockedIn ->
            "Uninstall, data-clear, factory reset and safe boot are blocked while you're locked in."
        else ->
            "Ready. These apply the moment you lock in."
    }
}
