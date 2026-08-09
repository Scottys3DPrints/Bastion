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

    /**
     * The home screen, whoever ships it.
     *
     * Long-pressing an icon is where an uninstall usually begins, and the menu
     * that opens carries the button. Catching it there is a screen earlier than
     * the installer's confirmation.
     */
    fun isLauncherApp(pkg: String): Boolean =
        pkg in LAUNCHER_PACKAGES || pkg.contains("launcher", ignoreCase = true)

    private val LAUNCHER_PACKAGES = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.oplus.launcher",
        "net.oneplus.launcher",
        "com.huawei.android.launcher",
        "com.microsoft.launcher",
        "ch.deletescape.lawnchair.plah",
    )

    /**
     * Whether a node is the long-press menu's container.
     *
     * Split out so the service can find that container in the tree and read
     * only what is inside it. Kept here, beside the identifiers it tests
     * against, because the two halves of this decision — which node is the
     * menu, and what makes a menu Bastion's — are one rule and drift apart the
     * moment they live in different files.
     */
    fun isLauncherMenuNode(viewId: String?, className: String): Boolean =
        (viewId != null && viewId in LAUNCHER_POPUP_IDS) ||
            className.contains("popupcontainer") ||
            className.contains("deepshortcut") ||
            className.contains("popupmenu")

    /** Any screen this needs to see, so the caller knows when to look. */
    fun isWatchedApp(pkg: String): Boolean =
        isSettingsApp(pkg) || isInstallerApp(pkg) || isLauncherApp(pkg)

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
        /**
         * The class carried by the event itself, alongside the window's.
         *
         * A long-press menu on the home screen is usually not a window at all —
         * most launchers add it as a view inside the workspace they already
         * own — so no window-state change fires and the remembered class stays
         * the launcher's activity for as long as the menu is open. The content
         * change that *does* fire carries the menu's own class, and reading
         * both is what lets this see a screen that never opened a window.
         */
        eventClassName: String? = null,
    ): Guarded? {
        val cls = (className.orEmpty() + " " + eventClassName.orEmpty()).lowercase()

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

        // The long-press menu on the home screen, which is a screen earlier than
        // the confirmation dialog and the point most uninstalls actually begin.
        //
        // Gated on the window being a popup, and that gate is doing the whole
        // job. The home screen itself has Bastion's name under its icon, so
        // matching the label alone would raise the wall over the launcher and
        // keep raising it — the app would be unusable rather than protected.
        // A popup window is a different window from the workspace, and the menu
        // for another app carries that app's name rather than this one's.
        if (isLauncherApp(packageName)) {
            // Recognised by identifier as well as by class, because the class
            // is the half that was failing. Launcher3 and its forks put the
            // menu inside the workspace window rather than a new one, so on
            // those phones nothing ever reported a popup and the wall waited
            // for a confirmation screen the man had already reached.
            val popup = cls.contains("popup") || cls.contains("shortcut") ||
                cls.contains("menu") || viewIds.any { it in LAUNCHER_POPUP_IDS }
            if (popup && appLabel.isNotBlank() &&
                texts.any { it.equals(appLabel, ignoreCase = true) }
            ) return Guarded.UNINSTALL
            return null
        }

        if (!isSettingsApp(packageName)) return null

        // Bastion's own App info page, which is where Settings keeps the
        // uninstall button. Gated on the app's name being on screen so that
        // every *other* app's info page stays open — a man must still be able
        // to manage the rest of his phone.
        // By identifier first, because on Android 12 and later the class is
        // no help: App info is served by the generic `SubSettings` container,
        // the same one behind a dozen other pages, and every fragment name this
        // used to look for stopped appearing in the window class years ago. The
        // page still carries an entity header naming its subject and a row of
        // action buttons, and no other Settings page carries both of those with
        // Bastion's name in the header.
        //
        // The header is what makes the pairing safe. A list of every app also
        // has Bastion's name somewhere on it, and walling that would make the
        // phone unmanageable — but a list has no entity header, because it is
        // not about any one app.
        val appInfo = viewIds.any { it in APP_INFO_IDS } ||
            cls.contains("installedappdetails") ||
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

    /**
     * The header and action-button identifiers on an app's own Settings page.
     *
     * These say "this page is about one app" rather than naming which one; the
     * name comes from the header text matched beside them. Both halves are
     * needed — the identifiers alone would wall every app's page and take the
     * rest of the phone with them.
     */
    private val APP_INFO_IDS = setOf(
        "entity_header_title",
        "entity_header_content",
        "app_detail_title",
        "uninstall_button",
        "action_buttons",
        "two_buttons_panel",
        "app_info_layout",
        "installed_app_details",
    )

    /**
     * The long-press menu's own container, whoever ships the launcher.
     *
     * Paired with the app's name found *inside* that container, never merely
     * somewhere on screen: the workspace behind the menu has Bastion's name
     * under its icon, so a looser test would raise the wall every time any
     * other app was long-pressed — and, on a launcher that keeps the menu in
     * the same window, would keep raising it over the home screen itself.
     */
    private val LAUNCHER_POPUP_IDS = setOf(
        "deep_shortcuts_container",
        "popup_container",
        "shortcuts_container",
        "system_shortcut",
        "deep_shortcut",
        "widget_shortcut",
        "app_menu",
        "quick_action_menu",
    )

    private val ACCESSIBILITY_IDS = setOf(
        "accessibility_settings",
        "accessibility_services_category",
        "accessibility_service_switch",
        "toggle_service_switch",
    )
}
