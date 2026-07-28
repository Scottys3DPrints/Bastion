package com.bastion.app

import com.bastion.app.data.content.BenefitTimeline
import com.bastion.app.data.content.Blocklist
import com.bastion.app.data.content.Challenges
import com.bastion.app.data.content.DailyBriefs
import com.bastion.app.data.content.Education
import com.bastion.app.data.content.HabitCatalogue
import com.bastion.app.data.content.MentorScript
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
}
