package com.bastion.app

import com.bastion.app.guard.accessibility.TitleFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The one place Bastion reads what is written on a screen rather than how the
 * screen is built.
 *
 * Every other rule names a container — the Reels viewer, the Shorts player, an
 * address bar — and that works because those screens are the problem whatever
 * is playing on them. YouTube is the case it cannot reach: the watch page is
 * the same page for a lecture and for the thing a man came here to stop.
 *
 * Which makes this the most dangerous matcher in the app, in both directions.
 * Too loose and it closes a video about beekeeping, teaches him the app is
 * broken, and costs the blocks that were right. Too tight and it is decoration.
 * Both failures are tested here rather than argued about.
 */
class TitleFilterTest {

    /** The list Bastion actually ships, read from the asset it ships in. */
    private val shipped: List<String> by lazy {
        Json.parseToJsonElement(File("src/main/assets/content/blocklist.json").readText())
            .jsonObject["onScreen"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private val words = listOf(
        "porn", "sex", "nude", "hot girls", "bikini haul", "onlyfans", "twerk",
    )

    // --- what it has to catch ------------------------------------------------

    @Test
    fun `a title that says what it is gets caught`() {
        assertEquals("porn", TitleFilter.match("Best porn sites reviewed", words))
        assertEquals("nude", TitleFilter.match("She goes nude on camera", words))
        assertEquals("onlyfans", TitleFilter.match("My OnlyFans story", words))
    }

    /** Phrases are matched whole, which is what lets a broad word be listed safely. */
    @Test
    fun `a phrase matches as a phrase`() {
        assertEquals("hot girls", TitleFilter.match("Hot girls of summer 2024", words))
        assertEquals("bikini haul", TitleFilter.match("HUGE bikini haul try on", words))
    }

    /**
     * Punctuation is not a disguise.
     *
     * Titles are full of decoration — dots, dashes, emoji, brackets — and a
     * matcher that treats the whole title as one token slides straight past
     * `S.E.X.Y` while a human reads it without pausing.
     */
    @Test
    fun `decoration between letters does not hide a word`() {
        assertEquals("sex", TitleFilter.match("the s-e-x talk", words))
        assertEquals("porn", TitleFilter.match("(PORN) addiction explained", words))
        assertEquals("twerk", TitleFilter.match("she can *twerk* 🔥", words))
    }

    /**
     * And neither are digits standing in for letters.
     *
     * p0rn and s3x are not clever, but they are free. A filter anyone can step
     * around in one keystroke is decoration, and the fold costs nothing.
     */
    @Test
    fun `digits standing in for letters are folded`() {
        assertEquals("porn", TitleFilter.match("p0rn is not the answer", words))
        assertEquals("sex", TitleFilter.match("s3x education", words))
    }

    @Test
    fun `case never matters`() {
        assertEquals("porn", TitleFilter.match("PORN", words))
        assertEquals("hot girls", TitleFilter.match("HOT GIRLS", words))
    }

    // --- what it must not catch ---------------------------------------------

    /**
     * The failure that would end the feature.
     *
     * This matches words, not substrings — which is the whole reason bare "sex"
     * can be listed here while being kept out of the domain filter, where it
     * would take Essex with it. If that ever inverted, a man would find ordinary
     * videos closing for reasons he could not see, and he would switch the
     * guard off. He would be right to.
     */
    @Test
    fun `a word inside another word is not a match`() {
        assertNull(TitleFilter.match("Visiting Essex on a budget", words))
        assertNull(TitleFilter.match("Middlesex county history", words))
        assertNull(TitleFilter.match("Sussex by the sea", words))
        assertNull(TitleFilter.match("Homophones and homonyms", words))
        assertNull(TitleFilter.match("Nudibranch diving footage", words))
    }

    @Test
    fun `an ordinary title passes`() {
        assertNull(TitleFilter.match("How to rebuild a carburettor", words))
        assertNull(TitleFilter.match("Beekeeping for beginners", words))
        assertNull(TitleFilter.match("", words))
    }

    /** Half a phrase is not the phrase. */
    @Test
    fun `part of a phrase does not match it`() {
        assertNull(TitleFilter.match("A hot day in the garden", words))
        assertNull(TitleFilter.match("Girls football final", words))
        assertNull(TitleFilter.match("Haul truck maintenance", words))
    }

    /**
     * The privacy limit, enforced rather than promised.
     *
     * Only short strings are read, because a title is short and a comment, a
     * description or somebody's writing is not. The cap is what keeps "this
     * reads titles" from quietly becoming "this reads the screen".
     */
    @Test
    fun `anything longer than a title is not read at all`() {
        val essay = "porn " + "a".repeat(TitleFilter.MAX_TITLE)
        assertNull("a paragraph is not a title", TitleFilter.match(essay, words))
        // And the boundary itself still works.
        val atLimit = "porn".padEnd(TitleFilter.MAX_TITLE, ' ')
        assertEquals("porn", TitleFilter.match(atLimit, words))
    }

    @Test
    fun `an empty word list matches nothing`() {
        assertNull(TitleFilter.match("porn", emptyList()))
    }

    // --- the shipped list ----------------------------------------------------

    /**
     * Nothing in the shipped list may be a substring trap in reverse: a word so
     * short or so common that ordinary speech trips it. Checked against real
     * titles rather than by eye.
     */
    @Test
    fun `the shipped words leave ordinary titles alone`() {
        listOf(
            "How to rebuild a carburettor in one afternoon",
            "Beekeeping for beginners: first hive",
            "Visiting Essex on a budget",
            "Full stack tutorial part 3",
            "Sunday sermon: the prodigal son",
            "Bass fishing in cold water",
            "Analysis of the 1972 final",
            "Class notes: thermodynamics",
            "Passing your driving test first time",
            "Assembly language basics",
            "Grand Theft Auto speedrun",
            "Making sourdough at home",
        ).forEach {
            assertNull("shipped list closes '$it'", TitleFilter.match(it, shipped))
        }
    }

    @Test
    fun `the shipped words catch what they are for`() {
        listOf(
            "Try on haul with my new lingerie",
            "Hot girls compilation 2024",
            "She reacts to my OnlyFans",
            "Uncensored version leaked",
            "Twerking tutorial for beginners",
        ).forEach {
            assertNotNull("shipped list misses '$it'", TitleFilter.match(it, shipped))
        }
    }
}
