package com.bastion.app.guard.lockdown

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager

/**
 * The doors Android *will* let an app close.
 *
 * Be clear about the one it will not: **no ordinary app can stop a user
 * disabling its own accessibility service.** Google keeps that toggle reachable
 * on purpose, for safety, and there is no public API to hold it down — checked
 * against the SDK rather than assumed, and there is no `DISALLOW_CONFIG_
 * ACCESSIBILITY` to be found. Any app promising otherwise is either lying or
 * requires root, and Bastion's whole credibility rests on not doing either.
 *
 * What a Device Owner *can* do is make flipping it pointless. An app suspended
 * by policy will not launch at all, with no accessibility service in the loop,
 * so the apps a man set to Fully blocked stay shut whatever he does on that
 * screen — see [suspendApps] and GuardWatchdog.holdFullBlocks. The toggle is
 * still reachable. It just stops being a way out.
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

    /** The resolver Private DNS is held at. Cloudflare's family filter. */
    const val HELD_DNS_HOSTNAME = "family.cloudflare-dns.com"

    /**
     * Whether the last attempt to hold Private DNS actually took.
     *
     * Reported rather than assumed. The system refuses to switch the mode when
     * it cannot validate the resolver, and an app that says "held" when it is
     * not is worse than one that never claimed it.
     */
    @Volatile
    var privateDnsHeld: Boolean = false
        private set

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
        // A second VPN would take the tunnel away from whatever is filtering.
        UserManager.DISALLOW_CONFIG_VPN,
        // Force-stopping Bastion from App info kills the accessibility service
        // and the watchdog until something launches the app again — a two-tap
        // way to switch the guard off that never touches the accessibility
        // screen at all. This closes the whole App info page.
        //
        // The cost is real and worth stating: while locked in, no app on the
        // phone can be force-stopped or uninstalled from Settings. It is
        // cleared the moment lock-in is lifted.
        UserManager.DISALLOW_APPS_CONTROL,
        // DISALLOW_INSTALL_APPS and DISALLOW_INSTALL_UNKNOWN_SOURCES are
        // deliberately NOT here, though the obvious bypass is "I'll install a
        // different browser".
        //
        // Bastion is sideloaded and updates itself through a PackageInstaller
        // session — it *is* an install from an unknown source. Blocking those
        // would mean a locked-in user could never update the app, and the only
        // way out would be uninstalling, which takes the whole journey with it.
        // Tested: with the restriction on, installing fails outright with
        // "User restriction prevents installing".
        //
        // Closing a bypass is not worth trapping someone on a broken version
        // with no way to fix it. The same door is closed less brutally by
        // suspending the specific apps a user names — see [suspendApps].
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

            allowPinning(context)

            privateDnsHeld = holdPrivateDns(dpm, admin, lockedIn)
            applyChromePolicy(dpm, admin, lockedIn)
            lockedIn
        }.getOrDefault(false)
    }

    /**
     * Holds Private DNS on, rather than merely noticing it go off.
     *
     * This is the difference between watching and holding. Bastion could
     * already tell when Private DNS was switched off and put a wall in front of
     * it; a Device Owner can set the thing itself, and Android then shows the
     * option as managed and greys it out. While locked in, it cannot be turned
     * off from Settings at all.
     *
     * Cleared on unlock, not left behind. A blocker that keeps holding a system
     * setting after the user has honestly decided to stop is a different and
     * worse kind of app.
     *
     * Deliberately NOT paired with an always-on VPN. Bastion's own filter is a
     * VpnService that claims the device resolver, and strict Private DNS needs
     * to reach its host on port 853 — with the tunnel in the way it cannot, and
     * the phone loses name resolution entirely. That combination was reported
     * from a real phone as "Private DNS server cannot be accessed", and forcing
     * both would guarantee it. One layer holds the web, and this is the
     * stronger one.
     */
    private fun holdPrivateDns(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        lockedIn: Boolean,
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        return runCatching {
            if (!lockedIn) {
                dpm.setGlobalPrivateDnsModeOpportunistic(admin)
                return@runCatching false
            }

            // The return code matters, and ignoring it was a real bug: on a
            // device that cannot reach the resolver, this sets the hostname,
            // leaves the mode alone, and answers PRIVATE_DNS_SET_ERROR_HOST_NOT
            // _SERVING. Bastion would then have told the user Private DNS was
            // held while it was switched off — the precise kind of claim this
            // app cannot afford to get wrong.
            //
            // It also blocks on network validation, which is why this only ever
            // runs from the watchdog's background reconcile.
            val result = dpm.setGlobalPrivateDnsModeSpecifiedHost(admin, HELD_DNS_HOSTNAME)
            val held = result == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR
            if (!held) {
                android.util.Log.w(
                    "DeviceOwner",
                    "Could not hold Private DNS at $HELD_DNS_HOSTNAME (code $result)",
                )
            }
            held
        }.getOrDefault(false)
    }

    /**
     * Whether locking in will take the web layer over via Private DNS.
     *
     * The caller needs to know because Bastion's own filter has to stand down
     * when it does — see [holdPrivateDns] for why the two cannot both run.
     */
    fun willHoldPrivateDns(context: Context): Boolean =
        isDeviceOwner(context) &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q

    /**
     * Managed Chrome settings, which close the hole a blocklist never sees.
     *
     * The everyday bypass is not a blocked porn domain — it is image and video
     * search on domains that must stay allowed. google.com and youtube.com
     * cannot be blocked without breaking the phone, so the filtering has to
     * happen inside the search itself. Chrome obeys these because the device is
     * managed; there is no equivalent for a browser that is not.
     *
     * `DnsOverHttpsMode=off` is the important one for this app in particular:
     * Chrome's own Secure DNS bypasses whatever resolver the system is using,
     * including a held Private DNS, which would otherwise undo the layer above.
     */
    private fun applyChromePolicy(
        dpm: DevicePolicyManager,
        admin: android.content.ComponentName,
        lockedIn: Boolean,
    ) {
        val restrictions = if (!lockedIn) android.os.Bundle() else android.os.Bundle().apply {
            putBoolean("ForceGoogleSafeSearch", true)
            putString("ForceYouTubeRestrict", "strict")
            putString("IncognitoModeAvailability", "disabled")
            // Closes Chrome's Secure DNS, which would otherwise route around
            // the resolver the rest of this depends on.
            putString("DnsOverHttpsMode", "off")
        }
        CHROME_PACKAGES.forEach { pkg ->
            runCatching { dpm.setApplicationRestrictions(admin, pkg, restrictions) }
        }
    }

    /** Chrome ships under more than one id depending on the build. */
    private val CHROME_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
    )

    /**
     * Stops other apps opening while locked in.
     *
     * Aimed at a second browser the user already has — Firefox, Samsung
     * Internet, Brave — which is the obvious way around anything applied to
     * Chrome. The list is the user's to choose: suspending apps someone did not
     * ask to have suspended would be a different product.
     */
    fun suspendApps(context: Context, packages: List<String>, suspended: Boolean): Boolean {
        if (!isDeviceOwner(context)) return false
        if (packages.isEmpty()) return true
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching {
            dpm.setPackagesSuspended(
                BastionDeviceAdmin.component(context),
                packages.toTypedArray(),
                suspended,
            )
            true
        }.getOrDefault(false)
    }

    /** Whether the pinned wall can actually pin itself. */
    fun canPinScreen(context: Context): Boolean = isDeviceOwner(context)

    /**
     * Allow-lists Bastion for silent screen pinning, and decides what stays
     * reachable while it is pinned.
     *
     * Called whenever a wall goes up rather than only while locked in. Break
     * glass is available to a man who has never locked in at all, and it was
     * the one path where the allow-list had not been set — so the strongest
     * thing the app can do was reserved for the people who needed it least.
     * Allow-listing costs nothing on its own: it permits pinning, it does not
     * pin.
     *
     * [DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS] is the power
     * button's own menu, and it is where Android puts Emergency. It stays on,
     * always, and is the reason this is a lockout rather than a trap. A man has
     * to be able to call an ambulance from his own phone; no rule he sets for
     * himself outranks that.
     *
     * Everything else is off: Home, Recents, the notification shade, the
     * keyguard's own shortcuts. Those are the ways out, and closing them is the
     * entire point.
     */
    fun allowPinning(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = BastionDeviceAdmin.component(context)
        return runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        // The clock and the battery. A wall that also hides how
                        // much charge is left makes a man reach for the phone he
                        // is trying not to reach for.
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        // The keyguard, which is the emergency path that
                        // actually exists on every phone.
                        //
                        // The power menu was the first answer and it is not
                        // dependable: on the test device a long press opened the
                        // Assistant instead, and which of the two it is depends
                        // on the OEM and on a setting. Leaving the keyguard
                        // enabled means pressing power and waking the phone
                        // gives Android's own lock screen, whose Emergency
                        // button is always there. Unlocking returns to the wall,
                        // so it costs nothing.
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD,
                )
            }
            true
        }.getOrDefault(false)
    }

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
        lockedIn && privateDnsHeld ->
            "Held while you're locked in: apps you set to Fully blocked can't be " +
                "opened at all — the phone refuses them, so switching Guard off in " +
                "Accessibility doesn't bring them back. Private DNS can't be changed, " +
                "Chrome forces SafeSearch and won't go incognito, and uninstall, " +
                "data-clear, force-stop, factory reset, safe boot and other VPNs are " +
                "blocked."
        lockedIn ->
            // Everything except the DNS hold, and it says which is missing.
            "Held while you're locked in: apps you set to Fully blocked can't be " +
                "opened at all, Chrome forces SafeSearch and won't go incognito, and " +
                "uninstall, data-clear, force-stop, factory reset, safe boot and other " +
                "VPNs are blocked. Private DNS could not be held — the phone couldn't " +
                "reach $HELD_DNS_HOSTNAME to check it."
        else ->
            "Ready. These apply the moment you lock in."
    }
}
