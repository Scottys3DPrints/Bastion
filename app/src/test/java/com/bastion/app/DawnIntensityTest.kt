package com.bastion.app

import com.bastion.app.core.design.dawnIntensityForHour
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dawn is not decoration — it is the app's one ambient signal, and the
 * quietest it ever gets must be the small hours, when urges peak and a bright
 * screen works against the person holding it.
 */
class DawnIntensityTest {

    @Test
    fun `every hour produces a sane value`() {
        (0..23).forEach { hour ->
            val value = dawnIntensityForHour(hour)
            assertTrue("hour $hour gave $value", value in 0f..1f)
        }
    }

    @Test
    fun `1am is the darkest the app ever gets`() {
        val night = dawnIntensityForHour(1)
        (0..23).forEach { hour ->
            assertTrue(
                "hour $hour is darker than 1am",
                dawnIntensityForHour(hour) >= night,
            )
        }
    }

    @Test
    fun `sunrise is the brightest`() {
        val sunrise = dawnIntensityForHour(6)
        (0..23).forEach { hour ->
            assertTrue("hour $hour outshines sunrise", dawnIntensityForHour(hour) <= sunrise)
        }
    }

    @Test
    fun `the small hours are markedly darker than daylight`() {
        assertTrue(dawnIntensityForHour(2) < dawnIntensityForHour(12) / 2f)
    }
}
