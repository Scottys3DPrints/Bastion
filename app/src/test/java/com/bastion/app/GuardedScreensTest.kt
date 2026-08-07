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

    private fun detect(
        pkg: String = "com.android.settings",
        cls: String? = null,
        ids: Set<String> = emptySet(),
        texts: Set<String> = emptySet(),
    ) = GuardedScreens.detect(pkg, cls, ids, texts, label, host)

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

    /** Bastion's App info page carries the app name, not the service label. */
    @Test
    fun `the app info page is not the accessibility page`() {
        assertNull(
            detect(
                cls = "com.android.settings.applications.InstalledAppDetailsTop",
                texts = setOf("Bastion", "Uninstall", "Force stop"),
            )
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
