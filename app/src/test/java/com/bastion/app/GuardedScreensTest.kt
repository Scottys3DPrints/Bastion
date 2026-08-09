package com.bastion.app

import com.bastion.app.guard.accessibility.GuardedScreens
import com.bastion.app.guard.accessibility.GuardedScreens.Guarded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which settings screens get walled, and — far more important — which do not.
 *
 * The asymmetry is the whole reason this is testable logic rather than an `if`
 * buried in the service. A missed detection costs one screen of protection. A
 * false positive puts a wall over Wi-Fi settings while a man is trying to get
 * online, and that is not a bug he reports, it is an app he uninstalls.
 */
class GuardedScreensTest {

    private val label = "Bastion Guard"
    private val host = "family.cloudflare-dns.com"

    private val appName = "Bastion"

    private fun detect(
        pkg: String = "com.android.settings",
        cls: String? = null,
        ids: Set<String> = emptySet(),
        texts: Set<String> = emptySet(),
        eventCls: String? = null,
    ) = GuardedScreens.detect(pkg, cls, ids, texts, label, host, appName, eventCls)

    // --- which app counts as Settings --------------------------------------

    @Test
    fun `the settings app is recognised however the oem ships it`() {
        assertTrue(GuardedScreens.isSettingsApp("com.android.settings"))
        assertTrue(GuardedScreens.isSettingsApp("com.samsung.android.settings"))
        assertTrue(GuardedScreens.isSettingsApp("com.miui.settings"))
    }

    /** The settings *search* provider. It has no screen of its own to guard. */
    @Test
    fun `settings search is not settings`() {
        assertFalse(GuardedScreens.isSettingsApp("com.android.settings.intelligence"))
        assertNull(detect(pkg = "com.android.settings.intelligence", texts = setOf(label)))
    }

    @Test
    fun `ordinary apps are never guarded`() {
        assertFalse(GuardedScreens.isSettingsApp("com.instagram.android"))
        // Even one that happens to have our own name on screen.
        assertNull(detect(pkg = "com.instagram.android", texts = setOf(label)))
    }

    // --- the accessibility screen ------------------------------------------

    @Test
    fun `the accessibility list is caught by its class name`() {
        assertEquals(
            Guarded.ACCESSIBILITY,
            detect(cls = "com.android.settings.Settings\$AccessibilitySettingsActivity"),
        )
    }

    /**
     * The per-service page is a generic SubSettings on most builds, so the class
     * name says nothing. What identifies it is Bastion's own name on it — which
     * is also why the label is the signal rather than the word "Accessibility":
     * that would work in English and fail in every other language.
     */
    @Test
    fun `the per-service page is caught by our own label`() {
        assertEquals(
            Guarded.ACCESSIBILITY,
            detect(cls = "com.android.settings.SubSettings", texts = setOf(label, "On")),
        )
    }

    @Test
    fun `a known view id is enough on a skin that renamed the activity`() {
        assertEquals(
            Guarded.ACCESSIBILITY,
            detect(cls = "com.oem.settings.SomeOtherName", ids = setOf("accessibility_settings")),
        )
    }

    // --- private dns --------------------------------------------------------

    @Test
    fun `the private dns dialog is caught by its class name`() {
        assertEquals(
            Guarded.PRIVATE_DNS,
            detect(cls = "com.android.settings.network.PrivateDnsModeDialogFragment"),
        )
    }

    @Test
    fun `the private dns dialog is caught by the hostname the user set`() {
        assertEquals(
            Guarded.PRIVATE_DNS,
            detect(cls = "com.android.settings.SubSettings", texts = setOf(host)),
        )
    }

    @Test
    fun `a private dns view id is enough`() {
        assertEquals(Guarded.PRIVATE_DNS, detect(ids = setOf("private_dns_mode")))
    }

    /**
     * A dialog can open over the accessibility screen, and both signals are then
     * present at once. DNS has to win, or the wall names the wrong door.
     */
    @Test
    fun `dns wins when both are on screen at once`() {
        assertEquals(
            Guarded.PRIVATE_DNS,
            detect(
                cls = "com.android.settings.SubSettings",
                ids = setOf("accessibility_settings", "private_dns_mode"),
                texts = setOf(label, host),
            ),
        )
    }

    // --- the failures that would get the app deleted ------------------------

    @Test
    fun `ordinary settings screens are left alone`() {
        assertNull(detect(cls = "com.android.settings.Settings\$WifiSettingsActivity"))
        assertNull(detect(cls = "com.android.settings.Settings\$BluetoothSettingsActivity"))
        assertNull(detect(cls = "com.android.settings.Settings\$SoundSettingsActivity"))
        assertNull(detect(cls = "com.android.settings.homepage.SettingsHomepageActivity"))
    }

    /**
     * A network page that merely *links* to Private DNS is not the dialog.
     *
     * `private_dns` alone is a preference key on those pages, which is exactly
     * why it is not one of the ids being matched — walling the connections
     * screen because it carries a link would make the phone unusable.
     */
    @Test
    fun `a page that only links to private dns is not the dialog`() {
        assertNull(
            detect(
                cls = "com.android.settings.Settings\$NetworkDashboardActivity",
                ids = setOf("private_dns", "wifi_settings", "mobile_network_settings"),
            )
        )
    }

    // --- uninstalling -------------------------------------------------------

    /**
     * Every route to uninstalling ends at the system package installer, whatever
     * the journey looked like — dragging the icon to the bin, the long-press
     * menu, or the button on the app's own settings page. One rule covers all
     * three because they all hand off to the same confirmation dialog.
     */
    @Test
    fun `the uninstall confirmation is caught wherever it was started from`() {
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                pkg = "com.google.android.packageinstaller",
                cls = "com.android.packageinstaller.UninstallerActivity",
            ),
        )
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                pkg = "com.android.packageinstaller",
                cls = "com.android.packageinstaller.UninstallAppProgress",
            ),
        )
    }

    /**
     * The one that would be catastrophic to get wrong.
     *
     * Bastion updates itself through a PackageInstaller session, which puts up a
     * dialog from the same package carrying the same app name. Walling it would
     * trap a locked-in man on a broken version with no way to fix it — and the
     * only escape left would be the uninstall this is trying to prevent.
     */
    @Test
    fun `the install dialog for an update is never walled`() {
        assertNull(
            detect(
                pkg = "com.google.android.packageinstaller",
                cls = "com.android.packageinstaller.PackageInstallerActivity",
                texts = setOf("Bastion", "Update"),
            )
        )
        assertNull(
            detect(
                pkg = "com.google.android.packageinstaller",
                cls = "com.android.packageinstaller.InstallInstalling",
                texts = setOf("Bastion"),
            )
        )
    }

    /** Bastion's App info page is where Settings keeps the uninstall button. */
    @Test
    fun `bastion's app info page is guarded`() {
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                cls = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = setOf("Bastion", "Uninstall", "Force stop"),
            ),
        )
    }

    /**
     * And every other app's is not. A man must still be able to manage the rest
     * of his phone, and walling all of App info would make the phone the app's
     * hostage rather than the guard's.
     */
    @Test
    fun `another app's info page stays open`() {
        assertNull(
            detect(
                cls = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = setOf("Instagram", "Uninstall", "Force stop"),
            )
        )
    }

    /**
     * The full app list carries Bastion's name among a hundred others, and it is
     * not the uninstall screen.
     *
     * What keeps it open is the entity header. A list is not about any one app,
     * so it has no header naming one — and that absence is the whole difference
     * between "Bastion appears here" and "this page is Bastion's".
     */
    @Test
    fun `the list of all apps is not the uninstall screen`() {
        assertNull(
            detect(
                cls = "com.android.settings.Settings\$ManageApplicationsActivity",
                texts = setOf("Bastion", "Instagram", "Chrome"),
            )
        )
        // And under the generic container every modern build actually reports,
        // where the class says nothing either way.
        assertNull(
            detect(
                cls = "com.android.settings.SubSettings",
                ids = setOf("apps_list", "recycler_view"),
                texts = setOf("Bastion", "Instagram", "Chrome"),
            )
        )
    }

    /**
     * App info under the container name every modern build actually reports.
     *
     * This is the case that was missing, and it was not an edge: since Android
     * 12 the App info page is served by `SubSettings`, the same generic shell
     * behind a dozen other pages, and every fragment name this looked for
     * stopped appearing in the window class years ago. The page was walled on
     * paper and open on the phone.
     */
    @Test
    fun `app info is caught under the generic container`() {
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                cls = "com.android.settings.SubSettings",
                ids = setOf("entity_header_title", "action_buttons"),
                texts = setOf("Bastion", "Uninstall", "Force stop"),
            ),
        )
    }

    /** Another app's page, under the same container, still opens. */
    @Test
    fun `app info for another app is not walled under the generic container`() {
        assertNull(
            detect(
                cls = "com.android.settings.SubSettings",
                ids = setOf("entity_header_title", "action_buttons"),
                texts = setOf("Instagram", "Uninstall", "Force stop"),
            )
        )
    }

    // --- the long-press menu, a screen earlier -------------------------------

    /**
     * Long-pressing the icon is where an uninstall usually begins, and the menu
     * that opens carries the button. Catching it here is a whole screen before
     * the installer's confirmation.
     */
    @Test
    fun `the long-press menu on the icon is guarded`() {
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                pkg = "com.google.android.apps.nexuslauncher",
                cls = "com.android.launcher3.popup.PopupContainerWithArrow",
                texts = setOf("Bastion", "App info", "Uninstall"),
            ),
        )
    }

    /**
     * And the home screen itself is not, which is the failure that would matter.
     *
     * The workspace has Bastion's name under its icon, so matching the label
     * alone would raise the wall over the launcher and keep raising it — the
     * phone would be unusable rather than protected. The popup gate is what
     * separates them, and it is the only thing that does.
     */
    @Test
    fun `the home screen is never walled`() {
        assertNull(
            detect(
                pkg = "com.google.android.apps.nexuslauncher",
                cls = "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                texts = setOf("Bastion", "Chrome", "Messages", "Phone"),
            )
        )
    }

    /**
     * The menu that never opened a window, which is most of them.
     *
     * Launcher3 and its forks add the long-press menu as a view inside the
     * workspace they already own, so no window-state change fires and the
     * remembered class stays the launcher's activity for as long as the menu is
     * up. Every test above passed while the phone did nothing, because they all
     * handed in a class the phone never reported.
     *
     * Two things fix it and both are asserted here: the class carried by the
     * content-changed event, and the container's own identifier.
     */
    @Test
    fun `the menu is caught when no window opened for it`() {
        // By the event's class, with the window still reporting the launcher.
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                pkg = "com.google.android.apps.nexuslauncher",
                cls = "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
                eventCls = "com.android.launcher3.popup.PopupContainerWithArrow",
                texts = setOf("Bastion", "App info", "Uninstall"),
            ),
        )
        // And by identifier alone, for a skin that named its class something
        // this has never heard of.
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                pkg = "com.miui.home",
                cls = "com.miui.home.launcher.Launcher",
                ids = setOf("deep_shortcuts_container"),
                texts = setOf("Bastion", "App info", "Uninstall"),
            ),
        )
    }

    /**
     * And the widened test must not widen the failure that matters.
     *
     * With the menu recognised by identifier, the home screen behind it is
     * still the home screen: it has no menu container in it, so nothing fires.
     * The service narrows what it reads to the menu's own subtree for the same
     * reason — see menuIn — because the workspace underneath has Bastion's name
     * on it whichever app was actually pressed.
     */
    @Test
    fun `the home screen is still not walled once identifiers count`() {
        assertNull(
            detect(
                pkg = "com.miui.home",
                cls = "com.miui.home.launcher.Launcher",
                ids = setOf("workspace", "hotseat", "page_indicator"),
                texts = setOf("Bastion", "Chrome", "Messages"),
            )
        )
        // A menu is open, but it is not Bastion's — the name is absent from
        // what the service read inside it.
        assertNull(
            detect(
                pkg = "com.miui.home",
                cls = "com.miui.home.launcher.Launcher",
                ids = setOf("deep_shortcuts_container"),
                texts = setOf("Instagram", "App info", "Uninstall"),
            )
        )
    }

    /** Another app's long-press menu carries that app's name, not this one's. */
    @Test
    fun `the long-press menu for a different app stays open`() {
        assertNull(
            detect(
                pkg = "com.google.android.apps.nexuslauncher",
                cls = "com.android.launcher3.popup.PopupContainerWithArrow",
                texts = setOf("Chrome", "App info", "Uninstall"),
            )
        )
    }

    /** Uninstall outranks the rest, since it is the one that ends everything. */
    @Test
    fun `uninstall is reported ahead of the settings screens`() {
        assertEquals(
            Guarded.UNINSTALL,
            detect(
                cls = "com.android.settings.applications.InstalledAppDetailsTop",
                ids = setOf("accessibility_settings"),
                texts = setOf("Bastion", label),
            ),
        )
    }

    @Test
    fun `a blank label or hostname never matches by itself`() {
        // A user who has not set a hostname must not have every blank string on
        // a settings screen count as a match.
        assertNull(
            GuardedScreens.detect(
                "com.android.settings", "com.android.settings.SubSettings",
                emptySet(), setOf("", "Network"), "", "",
            )
        )
    }

    @Test
    fun `a settings screen with nothing identifying on it is not guarded`() {
        assertNull(detect(cls = null, ids = emptySet(), texts = emptySet()))
    }
}
