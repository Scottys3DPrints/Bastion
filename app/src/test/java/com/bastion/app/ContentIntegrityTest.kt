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
        assertEquals(30, briefs.days.size)
        briefs.days.forEachIndexed { index, day ->
            assertEquals("days must be sequential", index + 1, day.day)
            listOf(day.faith, day.discipline).forEach { side ->
                assertTrue("anchor missing on day ${day.day}", side.anchor.isNotBlank())
                assertTrue("body missing on day ${day.day}", side.body.isNotBlank())
                assertTrue("challenge missing on day ${day.day}", side.microChallenge.isNotBlank())
            }
            assertNotNull("faith prompt missing on day ${day.day}", day.faith.prompt)
            assertNotNull("discipline prompt missing on day ${day.day}", day.discipline.prompt)
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
        allowedMoments.forEach { moment ->
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
                    assertEquals("${it.id} must name its translation", "WEB", it.translation)
                    assertTrue("${it.id} has no reference", !it.sourceRef.isNullOrBlank())
                }
            }
    }

    /** A short item has to actually be short — the widget has one line for it. */
    @Test
    fun `short items fit where short items go`() {
        motivation.items.filter { it.length == "short" }.forEach {
            assertTrue("${it.id} is too long to be short", it.text.length <= 120)
        }
    }
}
