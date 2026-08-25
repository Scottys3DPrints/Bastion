package com.bastion.app

import com.bastion.app.data.db.CategoryEntity
import com.bastion.app.data.db.ConditionType
import com.bastion.app.data.db.Confidence
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.PolicyEntity
import com.bastion.app.data.db.Response
import com.bastion.app.data.db.SurfaceEntity
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.PolicyContext
import com.bastion.app.guard.policy.PolicyEngine
import com.bastion.app.guard.policy.ResolvedSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A net is not a wall, and must not be able to act like one.
 *
 * The title filter is the only thing in Bastion that reads what is written on a
 * screen rather than how the screen is built. It exists because a YouTube watch
 * page is the same page for a lecture and for the thing a man came here to
 * stop, and no view id will ever separate those two. TitleFilter's own comments
 * have always said it is a net; until now that was a statement of intent with
 * nothing enforcing it.
 *
 * Here it is structural. Evidence carries a confidence, confidence caps the
 * response, and a low-confidence match cannot close an app however strongly the
 * policy is written — because the cost of being wrong is throwing a man out of
 * a lecture, and he will not tolerate that twice.
 */
class ConfidenceCapTest {

    private val surface = SurfaceEntity(
        id = "yt_explicit", categoryKey = "PORN", serviceKey = "youtube",
        label = "Videos with explicit titles",
    )
    private val surfaces = mapOf(surface.id to surface)
    private val categories = mapOf(
        "PORN" to CategoryEntity("PORN", "Pornography", chosen = true, response = Response.CLOSE.level),
    )

    private fun ctx() = PolicyContext(
        packageName = "com.google.android.youtube",
        minuteOfDay = 12 * 60,
        isoDayOfWeek = 3,
    )

    private fun sealedPolicy() = PolicyEntity(
        id = "p",
        targetType = TargetType.SURFACE,
        targetKey = "yt_explicit",
        conditionType = ConditionType.ALWAYS,
        response = Response.SEALED.level,
    )

    @Test
    fun `a low confidence signal can never close on its own`() {
        val decision = PolicyEngine.decide(
            present = listOf(ResolvedSurface("yt_explicit", "sig", Confidence.LOW)),
            policies = listOf(sealedPolicy()),
            surfaces = surfaces,
            categories = categories,
            ctx = ctx(),
        )!!
        assertEquals("a title match tops out at Pause", Response.PAUSE, decision.response)
        assertTrue("and the decision knows it was held back", decision.capped)
        assertTrue(
            "and says so in the receipt: ${decision.reason}",
            decision.reason.contains("words on screen"),
        )
    }

    @Test
    fun `a high confidence signal is not capped`() {
        val decision = PolicyEngine.decide(
            present = listOf(ResolvedSurface("yt_explicit", "sig", Confidence.HIGH)),
            policies = listOf(sealedPolicy()),
            surfaces = surfaces,
            categories = categories,
            ctx = ctx(),
        )!!
        assertEquals(Response.SEALED, decision.response)
        assertFalse(decision.capped)
    }

    @Test
    fun `a cap only ever lowers, never raises`() {
        val watchOnly = sealedPolicy().copy(response = Response.WATCH.level)
        val decision = PolicyEngine.decide(
            present = listOf(ResolvedSurface("yt_explicit", "sig", Confidence.LOW)),
            policies = listOf(watchOnly),
            surfaces = surfaces,
            categories = categories,
            ctx = ctx(),
        )!!
        assertEquals(
            "a cap must not promote Watch to Pause",
            Response.WATCH,
            decision.response,
        )
        assertFalse(decision.capped)
    }

    /**
     * The shipped catalogue has to agree with the rule, or the rule is decoration.
     */
    @Test
    fun `every title signal in the shipped catalogue is low confidence`() {
        val titles = Catalogue.signals().filter { it.matchType == MatchType.TITLE }
        assertTrue("the catalogue should still ship a title signal", titles.isNotEmpty())
        titles.forEach {
            assertEquals(
                "${it.id} reads words off the screen and must be capped",
                Confidence.LOW.ordinal,
                it.confidence,
            )
        }
    }

    /** And the ids that name a destination must not have been quietly downgraded. */
    @Test
    fun `signals that name a destination stay trusted`() {
        Catalogue.signals()
            .filter { it.matchType == MatchType.VIEW_ID || it.matchType == MatchType.URL }
            .forEach {
                assertEquals(
                    "${it.id} names a destination and should be trusted",
                    Confidence.HIGH.ordinal,
                    it.confidence,
                )
            }
    }
}
