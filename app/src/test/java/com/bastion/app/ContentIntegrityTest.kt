package com.bastion.app

import com.bastion.app.data.content.BenefitTimeline
import com.bastion.app.data.content.Blocklist
import com.bastion.app.data.content.Challenges
import com.bastion.app.data.content.DailyBriefs
import com.bastion.app.data.content.Education
import com.bastion.app.data.content.HabitCatalogue
import com.bastion.app.data.content.MentorScript
import com.bastion.app.data.content.MotivationLibrary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Decodes the real bundled content with the real models.
 *
 * ContentRepository deliberately swallows a malformed content file so that a bad
 * asset degrades one section instead of crashing the app someone opened at 1am.
 * That safety net also means a schema mismatch would ship silently as "the
 * library is empty". This test is what makes that trade safe.
 */
class ContentIntegrityTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private fun asset(name: String) = File("src/main/assets/content/$name").readText()

    @Test
    fun `daily briefs decode with both modes populated`() {
        val briefs = json.decodeFromString<DailyBriefs>(asset("daily_briefs.json"))
        // A floor rather than an exact count. The library is meant to grow, and
        // a test that has to be edited every time content is added teaches
        // people to edit the test rather than to think about the content.
        assertTrue(
            "the brief library has shrunk to ${briefs.days.size} days",
            briefs.days.size >= 90,
        )
        briefs.days.forEachIndexed { index, day ->
            assertEquals("days must be sequential", index + 1, day.day)
            listOf(day.faith, day.discipline).forEach { side ->
                assertTrue("anchor missing on day ${day.day}", side.anchor.isNotBlank())
                assertTrue("anchor reference missing on day ${day.day}", side.anchorRef.isNotBlank())
                assertTrue("title missing on day ${day.day}", side.title.isNotBlank())
                assertTrue("body missing on day ${day.day}", side.body.isNotBlank())
                assertTrue("challenge missing on day ${day.day}", side.microChallenge.isNotBlank())
            }
            assertNotNull("faith prompt missing on day ${day.day}", day.faith.prompt)
            assertNotNull("discipline prompt missing on day ${day.day}", day.discipline.prompt)
        }
    }

    /**
     * No day repeats another day's anchor.
     *
     * The briefs cycle by `% days.size`, so the library's length is exactly how
     * long a man goes before he sees the same morning twice. A duplicate anchor
     * is that repeat arriving early, and it is invisible in review because the
     * two days are eighty entries apart in the file.
     */
    @Test
    fun `every brief anchors on a different verse or quote`() {
        val briefs = json.decodeFromString<DailyBriefs>(asset("daily_briefs.json"))
        val anchors = briefs.days.flatMap { listOf(it.faith.anchor, it.discipline.anchor) }
        val repeated = anchors.groupBy { it }.filterValues { it.size > 1 }.keys
        assertTrue(
            "these anchors are used more than once: ${repeated.map { it.take(50) }}",
            repeated.isEmpty(),
        )
    }

    /**
     * Prayer is faith-only and reflection is discipline-only.
     *
     * The two sides exist so a man in discipline mode is never handed a prayer
     * he did not ask for, and the whole point collapses quietly if one entry
     * out of ninety puts the wrong prompt on the wrong side.
     */
    @Test
    fun `each side carries the prompt its mode expects`() {
        val briefs = json.decodeFromString<DailyBriefs>(asset("daily_briefs.json"))
        briefs.days.forEach { day ->
            assertNotNull("day ${day.day} has no prayer on the faith side", day.faith.prayer)
            assertNull("day ${day.day} puts a prayer on the discipline side", day.discipline.prayer)
            assertNotNull("day ${day.day} has no reflection on the discipline side", day.discipline.reflection)
        }
    }

    @Test
    fun `benefit cards declare an honest confidence level`() {
        val timeline = json.decodeFromString<BenefitTimeline>(asset("benefit_timeline.json"))
        assertTrue(timeline.cards.isNotEmpty())
        val allowed = setOf("established", "emerging", "anecdotal")
        timeline.cards.forEach { card ->
            assertTrue(
                "day ${card.day} has unknown confidence '${card.confidence}'",
                card.confidence.lowercase() in allowed,
            )
        }
    }

    @Test
    fun `education library decodes and every lesson reaches at least one mode`() {
        val education = json.decodeFromString<Education>(asset("education.json"))
        assertTrue(education.lessons.size >= 15)
        education.lessons.forEach { lesson ->
            assertTrue("${lesson.id} body empty", lesson.body.isNotBlank())
            assertTrue(
                "${lesson.id} is visible in neither mode",
                lesson.visibleIn(true) || lesson.visibleIn(false),
            )
        }
        assertEquals(
            "lesson ids must be unique",
            education.lessons.size,
            education.lessons.map { it.id }.distinct().size,
        )
    }

    @Test
    fun `challenges always resolve a task for every day they claim`() {
        val challenges = json.decodeFromString<Challenges>(asset("challenges.json"))
        assertTrue(challenges.challenges.isNotEmpty())
        challenges.challenges.forEach { challenge ->
            for (day in 1..challenge.days) {
                assertNotNull(
                    "${challenge.id} has no task for day $day",
                    challenge.taskFor(day),
                )
            }
        }
    }

    /**
     * A trimming pass once shortened this description to fit a word budget and
     * took the medical warning with it. Cold-water immersion is genuinely unsafe
     * for some people, so the warning outranks the house style on brevity.
     */
    @Test
    fun `the cold water challenge keeps its medical warning`() {
        val challenges = json.decodeFromString<Challenges>(asset("challenges.json"))
        val cold = challenges.challenges.firstOrNull { it.id == "cold-start" }
        assertNotNull("the cold-start challenge is missing", cold)

        val text = cold!!.description.lowercase()
        listOf("heart", "pregnan", "doctor").forEach {
            assertTrue("cold-start description no longer warns about '$it'", text.contains(it))
        }
    }

    @Test
    fun `mentor script has a crisis intent that outranks everything else`() {
        val script = json.decodeFromString<MentorScript>(asset("mentor.json"))
        val crisis = script.intents.firstOrNull { it.id == "crisis" }
        assertNotNull("the crisis intent must exist", crisis)
        crisis!!

        assertTrue(
            "crisis must have the lowest priority value of any intent",
            script.intents.none { it.priority < crisis.priority },
        )
        assertTrue("crisis needs keywords to match on", crisis.matchKeywords.isNotEmpty())

        // Every crisis response must hand the user to a real human, not counsel them.
        listOf(true, false).forEach { faithMode ->
            val responses = crisis.responses.forMode(faithMode)
            assertTrue("crisis has no responses for faithMode=$faithMode", responses.isNotEmpty())
            responses.forEach { response ->
                assertTrue(
                    "a crisis response names no helpline: $response",
                    response.contains("findahelpline", ignoreCase = true) ||
                        response.contains("988") ||
                        response.contains("116 123"),
                )
            }
        }
    }

    @Test
    fun `mentor script has a fallback and no broken follow-up links`() {
        val script = json.decodeFromString<MentorScript>(asset("mentor.json"))
        val ids = script.intents.map { it.id }.toSet()

        assertTrue("a fallback intent is required", "fallback" in ids)
        assertTrue("openers missing", script.openers.faith.isNotEmpty() && script.openers.discipline.isNotEmpty())

        script.intents.forEach { intent ->
            intent.followUps.forEach { followUp ->
                assertTrue(
                    "${intent.id} links to unknown intent '${followUp.intentId}'",
                    followUp.intentId in ids,
                )
            }
            if (intent.id != "fallback") {
                assertTrue(
                    "${intent.id} has no responses in either mode",
                    intent.responses.forMode(true).isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `habit catalogue covers every domain the Becoming profile expects`() {
        val habits = json.decodeFromString<HabitCatalogue>(asset("habits.json"))
        val domains = habits.habits.map { it.domain }.toSet()
        listOf("Discipline", "Faith", "Body", "Mind", "Relationships").forEach {
            assertTrue("no habits in the $it domain", it in domains)
        }
        assertEquals(
            "habit ids must be unique",
            habits.habits.size,
            habits.habits.map { it.id }.distinct().size,
        )
    }

    @Test
    fun `blocklist decodes and keeps bare 'sex' out of the keyword heuristic`() {
        val blocklist = json.decodeFromString<Blocklist>(asset("blocklist.json"))
        assertTrue(blocklist.domains.size > 50)
        assertTrue(blocklist.allow.isNotEmpty())

        // A bare "sex" keyword would block sexual-health and education resources.
        // Over-blocking is not a neutral failure, so this stays out by design.
        assertTrue("'sex' must not be a bare keyword", blocklist.keywords.none { it == "sex" })
    }

    // --- the motivation library ------------------------------------------
    //
    // The library is bundled read-only content that six surfaces now read
    // from, including the panic screen. A typo in a mode or a moment would not
    // crash anything — it would quietly make one of those surfaces come up
    // empty, which is the failure nobody notices until it matters.

    private val motivation: MotivationLibrary by lazy {
        json.decodeFromString(asset("motivation.json"))
    }

    private val allowedTypes = setOf(
        "quote", "scripture", "prayer", "reframe",
        "urge_line", "affirmation", "story", "fact",
    )
    private val allowedModes = setOf("faith", "discipline")
    private val allowedMoments = setOf("urge", "daily", "relapse", "milestone", "library")

    /**
     * The four moments a screen actually asks for by name. "library" is a
     * widening tag the Well also accepts — it grants an item extra reach rather
     * than describing a surface of its own, so an empty pool of it starves
     * nothing and is not worth failing a build over.
     */
    private val servedMoments = setOf("urge", "daily", "relapse", "milestone")
    private val allowedTriggers = setOf(
        "late_night", "boredom", "stress", "loneliness", "tiredness",
        "social_media", "anger", "home_alone", "anxiety", "alcohol",
    )

    @Test
    fun `motivation library decodes and counts itself honestly`() {
        assertTrue("library is empty", motivation.items.isNotEmpty())
        assertEquals("count must match items", motivation.items.size, motivation.count)
    }

    @Test
    fun `every item uses the controlled vocabularies`() {
        motivation.items.forEach { item ->
            assertTrue("blank text on ${item.id}", item.text.isNotBlank())
            assertTrue("bad type on ${item.id}: ${item.type}", item.type in allowedTypes)
            assertTrue("no modes on ${item.id}", item.modes.isNotEmpty())
            item.modes.forEach { assertTrue("bad mode on ${item.id}: $it", it in allowedModes) }
            assertTrue("no moments on ${item.id}", item.moments.isNotEmpty())
            item.moments.forEach { assertTrue("bad moment on ${item.id}: $it", it in allowedMoments) }
            item.triggers.forEach { assertTrue("bad trigger on ${item.id}: $it", it in allowedTriggers) }
            assertTrue("bad length on ${item.id}", item.length in setOf("short", "medium", "long"))
        }
    }

    @Test
    fun `ids and text are unique`() {
        val ids = motivation.items.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        val texts = motivation.items.map { it.text.lowercase() }
        assertEquals("duplicate text", texts.size, texts.toSet().size)
    }

    /**
     * The panic screen falls back to the daily brief and then to a hard-coded
     * line, so it can never be blank — but if this pool ever empties, the
     * trigger-matched wording silently stops happening and nothing says so.
     */
    @Test
    fun `both modes have a real pool of urge lines`() {
        listOf(true, false).forEach { faith ->
            val mode = if (faith) "faith" else "discipline"
            val pool = motivation.items.filter {
                it.verified && it.visibleIn(faith) && "urge" in it.moments
            }
            assertTrue("only ${pool.size} urge items in $mode mode", pool.size >= 20)
        }
    }

    @Test
    fun `every moment can be served in both modes`() {
        servedMoments.forEach { moment ->
            listOf(true, false).forEach { faith ->
                val pool = motivation.items.filter {
                    it.verified && it.visibleIn(faith) && moment in it.moments
                }
                val mode = if (faith) "faith" else "discipline"
                assertTrue("no $moment items in $mode mode", pool.isNotEmpty())
            }
        }
    }

    /** Scripture and prayer are devotional; they have no business in Discipline mode. */
    @Test
    fun `scripture and prayer are faith only`() {
        motivation.items
            .filter { it.type == "scripture" || it.type == "prayer" }
            .forEach { assertEquals("${it.id} leaks into discipline", listOf("faith"), it.modes) }
    }

    /** Anything quoting a real person or a translation must say where it came from. */
    @Test
    fun `quotes and scripture carry their attribution`() {
        motivation.items
            .filter { it.type == "quote" || it.type == "scripture" }
            .forEach {
                assertTrue("${it.id} has no attribution", !it.attribution.isNullOrBlank())
                if (it.type == "scripture") {
                    assertTrue("${it.id} has no reference", !it.sourceRef.isNullOrBlank())
                }
            }
    }

    /**
     * Every volume the library draws from, named, and each held to its own rule.
     *
     * A Bible verse has to say which translation it is, because the wording is
     * the translator's and the reader is owed that. The LDS standard works are
     * not translations in that sense — they have one text apiece — so they
     * carry no translation field, and demanding one would either be a lie or a
     * blocked import.
     *
     * The closed list is the point. This test's real job is to fail when a
     * scripture arrives from a volume nobody decided to include: the library is
     * assembled in bulk, and a bulk import that quietly widens what a faith-mode
     * user is shown is the kind of change that should have to be typed out here
     * first.
     */
    @Test
    fun `every scripture names a volume the library actually carries`() {
        val volumes = setOf(
            "The Bible",
            "The Book of Mormon",
            "Doctrine and Covenants",
            "The Pearl of Great Price",
        )
        motivation.items.filter { it.type == "scripture" }.forEach {
            assertTrue(
                "${it.id} is from '${it.attribution}', which no one added to the list",
                it.attribution in volumes,
            )
            if (it.attribution == "The Bible") {
                assertEquals("${it.id} must name its translation", "WEB", it.translation)
            } else {
                assertEquals(
                    "${it.id} is not a translation and must not claim to be one",
                    null,
                    it.translation,
                )
            }
        }
    }

    /**
     * And each volume is present in usable numbers rather than as a token.
     *
     * One Book of Mormon verse among forty from the Bible is not "the library
     * has the Book of Mormon" — on a daily rotation it is a verse a man sees
     * twice a year. The floor is low on purpose; it is here to catch a volume
     * being dropped by a re-import, not to dictate the balance.
     */
    @Test
    fun `each scripture volume has a real presence`() {
        val byVolume = motivation.items
            .filter { it.type == "scripture" }
            .groupBy { it.attribution }
        listOf(
            "The Bible",
            "The Book of Mormon",
            "Doctrine and Covenants",
            "The Pearl of Great Price",
        ).forEach {
            val n = byVolume[it].orEmpty().size
            assertTrue("$it is down to $n verses", n >= 3)
        }
    }

    /**
     * Every line names a real source, because none of them are the app's.
     *
     * The library used to be part harvest and part house writing — reframes,
     * urge lines, affirmations, all composed here and attributed to nobody.
     * They read fine and they were the weakest thing in the app: a man in a bad
     * hour was being handed encouragement by the same software that was
     * blocking him, dressed as wisdom.
     *
     * Everything now comes from someone. This is the test that keeps it that
     * way, because the easy way to fill a gap in a future import is to write
     * one more line and leave the attribution blank.
     */
    @Test
    fun `nothing in the library is anonymous`() {
        val unattributed = motivation.items.filter { it.attribution.isNullOrBlank() }
        assertTrue(
            "these name no source: " + unattributed.take(5).joinToString { it.id },
            unattributed.isEmpty(),
        )
    }

    /**
     * The panic screen has enough to say, in both modes, without repeating.
     *
     * This is the moment the whole library exists for, and it is the one where
     * a repeat is most expensive: a man who has come here three nights running
     * and been handed the same sentence has learned that nothing is listening.
     * The floor is deliberately far above what one night needs.
     */
    @Test
    fun `the urge moment is deep in both modes`() {
        listOf(true, false).forEach { faith ->
            val pool = motivation.items.count {
                it.verified && it.visibleIn(faith) && "urge" in it.moments
            }
            assertTrue(
                "only $pool urge lines in ${if (faith) "faith" else "discipline"} mode",
                pool >= 500,
            )
        }
    }

    /**
     * And the standard works survived the rebuild.
     *
     * They were asked for outright, and the library they live in gets replaced
     * wholesale from an outside file every time it grows. A wholesale replace
     * is exactly how something quietly asked for goes missing — it does not
     * fail, it simply is not there any more, and the only symptom is a man
     * noticing months later that he never sees one.
     */
    @Test
    fun `the rebuild did not drop the standard works`() {
        val byVolume = motivation.items
            .filter { it.type == "scripture" }
            .groupBy { it.attribution }
        listOf(
            "The Book of Mormon",
            "Doctrine and Covenants",
            "The Pearl of Great Price",
        ).forEach {
            assertTrue("$it is gone from the library", byVolume[it].orEmpty().isNotEmpty())
        }
    }

    /**
     * Lines that famous men did not say.
     *
     * Every one of these arrived in a library drop marked verified, because
     * that is what quote sites assert and quote sites are where these files get
     * assembled. They are matched on their words rather than their ids so that
     * re-importing a library — new ids, same aggregator — cannot quietly
     * restore them.
     *
     * This is not pedantry. Bastion asks a man to believe it about his own
     * character; an app that hands him a fake Einstein has already shown him
     * how carefully it checks things.
     */
    private val knownMisattributed = listOf(
        "Luck is what happens when preparation",          // not Seneca
        "Knowing yourself is the beginning of all wisdom", // not Aristotle
        "It does not matter how slowly you go",            // not in the Analects
        "Excellence is never an accident",                 // Willa Foster, not Aristotle
        "Integrity is doing the right thing, even when",    // not C.S. Lewis
        "You are never too old to set another goal",        // not C.S. Lewis
        "The two most important days in your life",         // not Twain
        "The best way to predict your future is to create", // not Lincoln
        "Adversity introduces a man to himself",            // not Einstein
        "Any fool can know",                                // not Einstein
        "Success is not final, failure is not fatal",       // not Churchill
        "we fall to the level of our training",             // not Archilochus
        "It is never too late to be what you might have",   // not George Eliot
        "the purpose of life is not to be happy",           // Rosten, not Emerson
    )

    @Test
    fun `misattributed lines never reach a screen`() {
        knownMisattributed.forEach { fragment ->
            val shown = motivation.items.filter {
                it.verified && it.text.contains(fragment, ignoreCase = true)
            }
            assertTrue(
                "misattributed line is displayable: ${shown.map { "${it.id} (${it.attribution})" }}",
                shown.isEmpty(),
            )
        }
    }

    /**
     * The counterpart to the test above: the gate has to still be worth having.
     * If a drop-in library ever arrives with everything marked verified, this
     * is what notices.
     */
    @Test
    fun `the verified gate is actually holding something back`() {
        val hidden = motivation.items.count { !it.verified }
        assertTrue("nothing is marked unverified — was the audit lost?", hidden >= 10)
    }

    /** A short item has to actually be short — the widget has one line for it. */
    @Test
    fun `short items fit where short items go`() {
        motivation.items.filter { it.length == "short" }.forEach {
            assertTrue("${it.id} is too long to be short", it.text.length <= 120)
        }
    }
}
