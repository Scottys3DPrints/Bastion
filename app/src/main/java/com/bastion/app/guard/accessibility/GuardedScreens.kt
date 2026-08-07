package com.bastion.app.guard.accessibility

/**
 * Which system settings screens are off-limits while the settings lock is on.
 *
 * Every other guard in the app is reactive: it notices Guard has gone down, or
 * that Private DNS has been switched off, and puts a wall up afterwards. By then
 * the switch has already been flipped, and for the seconds it takes to notice,
 * the protection is off.
 *
 * This is the other kind. It fires on *arrival* — the accessibility screen is
 * where Guard gets turned off and the Private DNS screen is where the resolver
 * gets changed, so while a man is locked in, standing on either of them is
 * itself the thing to interrupt. He asked in a calmer hour to be made to work
 * for this; the wall is what that request looks like at the moment it matters.
 *
 * Pure string matching on purpose, so it can be checked against the real screen
 * identifiers on a laptop. Detecting the wrong screen is the expensive failure —
 * a wall over Wi-Fi settings is an app that has to be uninstalled — and the only
 * way to be sure is to be able to test the rules.
 */
object GuardedScreens {

    enum class Guarded { ACCESSIBILITY, PRIVATE_DNS, UNINSTALL }

    /**
     * The system's package installer, whoever ships it.
     *
     * Uninstalling is not done by the launcher or by Settings, whatever the
     * journey looked like. All of them hand off to this, which is why one rule
     * here covers dragging the icon to the bin, the long-press menu, and the
     * button on the app's own settings page.
     */
    fun isInstallerApp(pkg: String): Boolean = pkg in INSTALLER_PACKAGES

    private val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.oplus.packageinstaller",
        "com.oppo.packageinstaller",
        "com.vivo.packageinstaller",
    )

    /** Any screen this needs to see, so the caller knows when to look. */
    fun isWatchedApp(pkg: String): Boolean = isSettingsApp(pkg) || isInstallerApp(pkg)

    /**
     * Settings, whoever ships it.
     *
     * OEMs fork this: Samsung and Xiaomi both add their own settings packages
     * alongside the AOSP one, and a rule that only knew `com.android.settings`
     * would quietly not apply on a large share of real phones.
     *
     * `com.android.settings.intelligence` is excluded deliberately — that is the
     * settings *search* provider, which is in the foreground while a man types a
     * query and has no screen of its own to guard.
     */
    fun isSettingsApp(pkg: String): Boolean =
        pkg != "com.android.settings.intelligence" &&
            (pkg == "com.android.settings" || pkg.endsWith(".settings"))

    /**
     * Which guarded screen is in front, if any.
     *
     * Three signals, any one of which is enough, because no single one survives
     * every OEM:
     *
     *  - **The class name.** Reliable on AOSP and on most skins for the
     *    accessibility list, useless for Private DNS on several of them, where
     *    it is a dialog whose class varies.
     *  - **A view identifier.** Stable where it exists, absent where the OEM
     *    rewrote the screen.
     *  - **Text on screen.** Only ever matched against strings Bastion itself
     *    owns — the accessibility service's own label, and the DNS hostname the
     *    user set. Never against words like "Accessibility", which would work in
     *    English and fail in every other language the phone might be in.
     *
     * The label match is the one that carries the accessibility case in
     * practice: the per-service page is a generic `SubSettings` on most builds,
     * and the thing that identifies it is Bastion's own name on it.
     */
    fun detect(
        packageName: String,
        className: String?,
        viewIds: Set<String>,
        texts: Set<String>,
        serviceLabel: String,
        dnsHostname: String,
        appLabel: String = "",
    ): Guarded? {
        val cls = className.orEmpty().lowercase()

        // Uninstalling, wherever it was started from.
        //
        // This is the bypass that takes everything else with it — the guards,
        // the covenant, the counted days — so it is checked before anything
        // else and from both directions: the installer's own confirmation
        // dialog, and Bastion's page in Settings where the button lives.
        if (isInstallerApp(packageName)) {
            // The class name has to say uninstall, and that is not fussiness.
            //
            // Bastion updates itself through a PackageInstaller session, which
            // puts up a dialog from this same package, carrying this same app's
            // name. Matching on the name alone would wall the update — trapping
            // a locked-in man on a broken version with no way to fix it, where
            // the only escape left is the uninstall this is trying to prevent.
            // "Uninstall" appears in the class on AOSP and on every fork of it
            // seen so far; "install" does not imply it.
            if (cls.contains("uninstall")) return Guarded.UNINSTALL
            return null
        }

        if (!isSettingsApp(packageName)) return null

        // Bastion's own App info page, which is where Settings keeps the
        // uninstall button. Gated on the app's name being on screen so that
        // every *other* app's info page stays open — a man must still be able
        // to manage the rest of his phone.
        val appInfo = cls.contains("installedappdetails") ||
            cls.contains("applicationdetails") ||
            cls.contains("appinfodashboard")
        if (appInfo && appLabel.isNotBlank() && texts.any { it.equals(appLabel, ignoreCase = true) }) {
            return Guarded.UNINSTALL
        }

        val dns = cls.contains("privatedns") ||
            viewIds.any { it in PRIVATE_DNS_IDS } ||
            (dnsHostname.isNotBlank() && texts.any { it.equals(dnsHostname, ignoreCase = true) })
        // Private DNS first. On the builds where the DNS dialog opens over the
        // accessibility screen it would otherwise be reported as accessibility,
        // and the wall would name the wrong door.
        if (dns) return Guarded.PRIVATE_DNS

        val accessibility = cls.contains("accessibilitysettings") ||
            cls.contains("accessibilitydetail") ||
            viewIds.any { it in ACCESSIBILITY_IDS } ||
            (serviceLabel.isNotBlank() && texts.any { it.equals(serviceLabel, ignoreCase = true) })
        if (accessibility) return Guarded.ACCESSIBILITY

        return null
    }

    /**
     * Deliberately narrow. `private_dns` on its own is not here: it appears as a
     * preference key on network screens that merely *link* to the dialog, and
     * walling the Wi-Fi page because it carries a link is the kind of false
     * positive that gets an app deleted.
     */
    private val PRIVATE_DNS_IDS = setOf(
        "private_dns_mode",
        "private_dns_mode_dialog",
        "private_dns_hostname",
        "edit_text_private_dns",
    )

    private val ACCESSIBILITY_IDS = setOf(
        "accessibility_settings",
        "accessibility_services_category",
        "accessibility_service_switch",
        "toggle_service_switch",
    )
}
