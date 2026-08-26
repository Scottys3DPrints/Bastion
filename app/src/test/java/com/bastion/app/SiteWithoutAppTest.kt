package com.bastion.app

import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.PolicyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Blocking a site you do not have the app for.
 *
 * ## The bug this exists for, found on a real phone
 *
 * Every `facebook.com` rule was filed under `com.facebook.katana`, and a rule
 * only fired when the package that owned it was guarded. On a phone with
 * Messenger but no Facebook app, that package could never be guarded — it was
 * not installed, so the picker never listed it — and therefore every Facebook
 * rule was dead. Reels played in Chrome, in Messenger's web view and in the
 * Google app, and there was nothing in the interface that could be switched on
 * to stop them.
 *
 * The interface said Facebook was covered. Nothing was covered. That is worse
 * than a block that fires wrongly, because a man arranges his day around it.
 *
 * Two things fix it and both are pinned here: Messenger counts as Facebook, and
 * a site can be guarded with no app at all.
 */
class SiteWithoutAppTest {

    private fun app(pkg: String, mode: BlockMode = BlockMode.FEED_ONLY, enabled: Boolean = true) =
        GuardedAppEntity(packageName = pkg, label = pkg, mode = mode, enabled = enabled)

    // --- Messenger is Facebook -------------------------------------------

    @Test
    fun `messenger belongs to the facebook service`() {
        assertEquals(
            Catalogue.FACEBOOK,
            Catalogue.SERVICE_BY_PACKAGE[Catalogue.PKG_MESSENGER],
        )
    }

    @Test
    fun `guarding messenger closes facebook everywhere`() {
        val policies = PolicyPlan.forApps(listOf(app(Catalogue.PKG_MESSENGER)))
        val targets = policies.filter { it.targetType == TargetType.SURFACE }.map { it.targetKey }
        assertTrue("Facebook's reels must be covered: $targets", "fb_reels" in targets)
        assertTrue("and the site itself: $targets", "fb_site" in targets)
    }

    // --- a site needs no app ---------------------------------------------

    @Test
    fun `every offered site has evidence that works with no app installed`() {
        assertTrue("no sites are offered at all", Catalogue.SITES.isNotEmpty())
        Catalogue.SITES.forEach { (service, pkg, label) ->
            val surfaces = Catalogue.surfacesOfService(service).map { it.id }.toSet()
            assertTrue("$label has no surfaces", surfaces.isNotEmpty())

            val reachable = Catalogue.signals()
                .filter { it.surfaceId in surfaces }
                // Evidence that needs the app on the phone is no use for a site,
                // and evidence that ships switched off is no use to anyone.
                .filter { it.enabled && it.scopePackage == null && it.matchType == MatchType.URL }
            assertTrue(
                "$label is offered as a site but has no address to recognise it by, " +
                    "so guarding it would read as protection and do nothing",
                reachable.isNotEmpty(),
            )
            assertNotNull(
                "$label names a package that is not a known service door",
                Catalogue.SERVICE_BY_PACKAGE[pkg],
            )
        }
    }

    /**
     * The one the first draft of this file got wrong.
     *
     * X's only evidence is `immersive_player`, a view id inside the X app. With
     * the app absent there is nothing to match, so offering X as a site would
     * have been a switch wired to nothing — the exact failure the whole
     * restructure exists to end, reintroduced by a hand-written list.
     */
    @Test
    fun `a service with no address is not offered as a site`() {
        assertTrue(
            "X has no scope-free address and must not be offered",
            Catalogue.SITES.none { (service, _, _) -> service == Catalogue.X },
        )
        assertTrue(
            "Facebook must be offered — it is the case this was built for",
            Catalogue.SITES.any { (service, _, _) -> service == Catalogue.FACEBOOK },
        )
    }

    @Test
    fun `guarding a site with no app still produces policies`() {
        val policies = PolicyPlan.forApps(listOf(app(Catalogue.PKG_FACEBOOK)))
        assertTrue(
            "guarding Facebook must close something even with no Facebook app",
            policies.any { it.targetType == TargetType.SURFACE && it.targetKey == "fb_site" },
        )
    }

    // --- the ordering bug ------------------------------------------------

    /**
     * Two packages, one service, and the order they are touched in must not
     * decide what is protected.
     *
     * Per-app rewriting cleared a service's surface policies and rebuilt them
     * from the one app being applied, so unguarding Messenger took Facebook's
     * protection with it — and unguarding Facebook took Messenger's.
     */
    @Test
    fun `one package of a service leaving does not disarm the other`() {
        val both = PolicyPlan.forApps(
            listOf(app(Catalogue.PKG_MESSENGER), app(Catalogue.PKG_FACEBOOK)),
        )
        assertTrue(both.any { it.targetKey == "fb_site" && it.enabled })

        val messengerGone = PolicyPlan.forApps(listOf(app(Catalogue.PKG_FACEBOOK)))
        assertTrue(
            "Facebook is still guarded, so its site must still be closed",
            messengerGone.any { it.targetKey == "fb_site" && it.enabled },
        )

        val facebookGone = PolicyPlan.forApps(listOf(app(Catalogue.PKG_MESSENGER)))
        assertTrue(
            "Messenger is still guarded, so Facebook must still be closed",
            facebookGone.any { it.targetKey == "fb_site" && it.enabled },
        )
    }

    /** A surface is protected when anything that reaches it says so. */
    @Test
    fun `a switched-off app does not switch off a service another app guards`() {
        val policies = PolicyPlan.forApps(
            listOf(
                app(Catalogue.PKG_MESSENGER, enabled = false),
                app(Catalogue.PKG_FACEBOOK, enabled = true),
            ),
        )
        val site = policies.first { it.targetKey == "fb_site" }
        assertTrue("the guarded one must win over the switched-off one", site.enabled)

        val other = PolicyPlan.forApps(
            listOf(
                app(Catalogue.PKG_FACEBOOK, enabled = true),
                app(Catalogue.PKG_MESSENGER, enabled = false),
            ),
        )
        assertTrue("and in the other order too", other.first { it.targetKey == "fb_site" }.enabled)
    }

    // --- the plan still says what it always said --------------------------

    @Test
    fun `the four old modes still land where the migration puts them`() {
        val plan = PolicyPlan.forApps(
            listOf(
                app("com.example.full", BlockMode.FULL),
                app("com.example.schedule", BlockMode.SCHEDULE),
                app("com.example.limit", BlockMode.TIME_LIMIT),
            ),
        )
        assertEquals(3, plan.size)
        assertTrue(plan.all { it.targetType == TargetType.APP })
        assertEquals(
            setOf("ALWAYS", "CURFEW", "DAILY_BUDGET"),
            plan.map { it.conditionType.name }.toSet(),
        )
    }

    /** An app nobody has a service for guards itself and nothing else. */
    @Test
    fun `feed-only on an unknown app closes nothing it should not`() {
        assertTrue(PolicyPlan.forApps(listOf(app("com.unknown.app"))).isEmpty())
    }
}
