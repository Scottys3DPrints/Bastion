package com.bastion.app

import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.TargetType
import com.bastion.app.guard.policy.Catalogue
import com.bastion.app.guard.policy.PolicyPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Reels tab and a reel opened from Search are two destinations.
 *
 * They were always switchable one at a time — each signal carries its own
 * `enabled`, and the v1 toggle is copied onto it by id — but they shared a
 * surface, so a block could only ever name "Instagram Reels". A man closing one
 * and keeping the other had no way to tell, from the shield, which of the two
 * had just fired.
 */
class ReelsSurfacesTest {

    private val tab = "ig_reels"
    private val search = "ig_reels_search"

    @Test
    fun `both destinations exist and belong to Instagram`() {
        val surfaces = Catalogue.surfaces().associateBy { it.id }
        listOf(tab, search).forEach { id ->
            val surface = assertNotNull("$id is missing", surfaces[id]).let { surfaces.getValue(id) }
            assertEquals(Catalogue.INSTAGRAM, surface.serviceKey)
        }
        assertTrue(
            "the two must not share a name",
            surfaces.getValue(tab).label != surfaces.getValue(search).label,
        )
    }

    @Test
    fun `the tab keeps its own evidence and search keeps its own`() {
        val bySurface = Catalogue.signals().groupBy { it.surfaceId }

        val tabValues = bySurface.getValue(tab).map { it.matchValue }
        assertTrue("the Reels tab is clips_viewer", "clips_viewer" in tabValues)

        val searchValues = bySurface.getValue(search).map { it.matchValue }
        assertTrue("root_clips_layout belongs to Search", "root_clips_layout" in searchValues)
        assertTrue(
            "and so does the list container",
            "clips_linear_layout_container" in searchValues,
        )

        assertTrue(
            "no evidence may sit on both, or neither could be closed alone",
            tabValues.none { it in searchValues },
        )
    }

    /**
     * The point of the split: guarding Instagram must produce a policy for each,
     * so one can be switched off without touching the other.
     */
    @Test
    fun `guarding Instagram policies each destination separately`() {
        val policies = PolicyPlan.forApps(
            listOf(
                GuardedAppEntity(
                    packageName = Catalogue.PKG_INSTAGRAM,
                    label = "Instagram",
                    mode = BlockMode.FEED_ONLY,
                ),
            ),
        )
        val targets = policies.filter { it.targetType == TargetType.SURFACE }.map { it.targetKey }
        assertTrue("the Reels tab needs its own policy: $targets", tab in targets)
        assertTrue("and Search its own: $targets", search in targets)
    }

    /**
     * Signal ids are the old rule ids, deliberately. Re-homing a signal to a new
     * surface must not change its id, or a switch a man has already set would
     * come back in its default position.
     */
    @Test
    fun `re-homing did not change any signal id`() {
        val ids = Catalogue.signals().map { it.id }
        listOf("root_clips_layout", "clips_linear_layout_container", "clips_viewer").forEach {
            assertTrue(
                "$it lost its legacy id",
                "builtin_${Catalogue.PKG_INSTAGRAM}_$it" in ids,
            )
        }
    }
}
