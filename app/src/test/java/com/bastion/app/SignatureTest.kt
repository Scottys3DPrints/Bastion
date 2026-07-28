package com.bastion.app

import androidx.compose.ui.geometry.Offset
import com.bastion.app.feature.onboarding.hasRealSignature
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The covenant is the emotional centre of the app and is signed exactly once.
 * A blank one is worse than no feature at all.
 */
class SignatureTest {

    private fun line(points: Int, step: Float = 20f): List<Offset> =
        (0 until points).map { Offset(it * step, 40f) }

    @Test
    fun `no strokes is not a signature`() {
        assertFalse(hasRealSignature(emptyList()))
    }

    @Test
    fun `a single tap is not a signature`() {
        // The exact bug: one stroke of one point satisfied isNotEmpty(), while
        // the renderer's `1 until size` loop drew nothing at all.
        assertFalse(hasRealSignature(listOf(listOf(Offset(10f, 10f)))))
    }

    @Test
    fun `a few jittery points are not a signature`() {
        val jitter = (0 until 6).map { Offset(10f + it, 10f) }
        assertFalse(hasRealSignature(listOf(jitter)))
    }

    @Test
    fun `an actual drawn line is a signature`() {
        assertTrue(hasRealSignature(listOf(line(points = 12))))
    }

    @Test
    fun `several short strokes together count`() {
        val strokes = listOf(line(points = 4), line(points = 4), line(points = 4))
        assertTrue(hasRealSignature(strokes))
    }
}
