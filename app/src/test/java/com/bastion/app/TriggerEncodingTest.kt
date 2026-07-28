package com.bastion.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors how onboarding answers are stored.
 *
 * They were pipe-joined, so any trigger containing "|" silently split in two —
 * and these feed the pattern analysis, so a corrupted list quietly degrades the
 * insight engine rather than failing loudly.
 */
class TriggerEncodingTest {

    private fun encode(values: List<String>) = Json.encodeToString(values)

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { Json.decodeFromString<List<String>>(raw) }
            .getOrElse { raw.split('|').filter { it.isNotBlank() } }
    }

    @Test
    fun `round trips ordinary values`() {
        val triggers = listOf("Late night", "Boredom", "Stress")
        assertEquals(triggers, decode(encode(triggers)))
    }

    @Test
    fun `survives a value containing the old separator`() {
        val triggers = listOf("Tired | wired", "Boredom")
        assertEquals(triggers, decode(encode(triggers)))
    }

    @Test
    fun `survives quotes commas and newlines`() {
        val triggers = listOf("""He said "later"""", "a,b", "two\nlines")
        assertEquals(triggers, decode(encode(triggers)))
    }

    /** An install written by the old build must not lose its answers. */
    @Test
    fun `still reads the legacy pipe format`() {
        assertEquals(listOf("Late night", "Stress"), decode("Late night|Stress"))
    }

    @Test
    fun `blank and null decode to empty`() {
        assertEquals(emptyList<String>(), decode(null))
        assertEquals(emptyList<String>(), decode(""))
    }
}
