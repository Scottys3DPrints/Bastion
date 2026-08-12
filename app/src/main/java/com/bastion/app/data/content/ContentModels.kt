package com.bastion.app.data.content

import kotlinx.serialization.Serializable

/*
 * Mirrors the JSON in assets/content. Every model is decoded with
 * ignoreUnknownKeys so that adding a field to the content files can never
 * hard-crash an installed build.
 */

@Serializable
data class BriefSide(
    val anchor: String,
    val anchorRef: String,
    val title: String,
    val body: String,
    val microChallenge: String,
    val prayer: String? = null,
    val reflection: String? = null,
) {
    /** The closing prompt, whichever mode this side belongs to. */
    val prompt: String? get() = prayer ?: reflection
}

@Serializable
data class DailyBrief(
    val day: Int,
    val theme: String,
    val faith: BriefSide,
    val discipline: BriefSide,
) {
    fun side(faithMode: Boolean): BriefSide = if (faithMode) faith else discipline
}

@Serializable
data class DailyBriefs(val version: Int = 1, val days: List<DailyBrief> = emptyList())

@Serializable
data class BenefitCard(
    val day: Int,
    val title: String,
    val body: String,
    val domain: String,
    /** "established", "emerging" or "anecdotal" — surfaced honestly in the UI. */
    val confidence: String = "emerging",
)

@Serializable
data class BenefitTimeline(val version: Int = 1, val cards: List<BenefitCard> = emptyList())

@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val category: String,
    val modes: List<String> = listOf("faith", "discipline"),
    val readMinutes: Int = 3,
    val body: String,
    val keyTakeaway: String = "",
) {
    fun visibleIn(faithMode: Boolean) = modes.contains(if (faithMode) "faith" else "discipline")
}

@Serializable
data class Education(val version: Int = 1, val lessons: List<Lesson> = emptyList())

@Serializable
data class ChallengeTask(val day: Int, val task: String, val detail: String = "")

@Serializable
data class Challenge(
    val id: String,
    val name: String,
    val tagline: String = "",
    val days: Int,
    val difficulty: String = "core",
    val modes: List<String> = listOf("faith", "discipline"),
    val description: String = "",
    val dailyTasks: List<ChallengeTask> = emptyList(),
    val badgeId: String = "",
    val badgeName: String = "",
) {
    fun visibleIn(faithMode: Boolean) = modes.contains(if (faithMode) "faith" else "discipline")

    /**
     * Long programmes may define fewer explicit tasks than they have days
     * (the 90-day reboot repeats a weekly core), so cycle rather than fall off.
     */
    fun taskFor(day: Int): ChallengeTask? {
        if (dailyTasks.isEmpty()) return null
        return dailyTasks.firstOrNull { it.day == day }
            ?: dailyTasks[(day - 1).coerceAtLeast(0) % dailyTasks.size]
    }
}

@Serializable
data class Challenges(val version: Int = 1, val challenges: List<Challenge> = emptyList())

@Serializable
data class MentorResponses(
    val faith: List<String> = emptyList(),
    val discipline: List<String> = emptyList(),
) {
    fun forMode(faithMode: Boolean): List<String> =
        (if (faithMode) faith else discipline).ifEmpty { if (faithMode) discipline else faith }
}

@Serializable
data class MentorFollowUp(val label: String, val intentId: String)

@Serializable
data class MentorIntent(
    val id: String,
    val label: String = "",
    val matchKeywords: List<String> = emptyList(),
    /** Lower wins. The crisis intent sits at 0 so it always takes precedence. */
    val priority: Int = 5,
    val responses: MentorResponses = MentorResponses(),
    val followUps: List<MentorFollowUp> = emptyList(),
)

@Serializable
data class MentorOpeners(
    val faith: List<String> = emptyList(),
    val discipline: List<String> = emptyList(),
)

@Serializable
data class MentorScript(
    val version: Int = 1,
    val intents: List<MentorIntent> = emptyList(),
    val openers: MentorOpeners = MentorOpeners(),
)

@Serializable
data class HabitDef(
    val id: String,
    val name: String,
    val domain: String,
    val modes: List<String> = listOf("faith", "discipline"),
    val defaultTarget: String = "daily",
    val icon: String = "•",
    val why: String = "",
) {
    fun visibleIn(faithMode: Boolean) = modes.contains(if (faithMode) "faith" else "discipline")
}

@Serializable
data class HabitCatalogue(val version: Int = 1, val habits: List<HabitDef> = emptyList())

@Serializable
data class Blocklist(
    val version: Int = 1,
    val domains: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
    /** Substrings that mark a domain as adult when no allow-list entry matches. */
    val keywords: List<String> = emptyList(),
    /**
     * Whole words that mark a *video title* as adult, inside video apps only.
     *
     * Separate from [keywords] because the matching is different in kind, not
     * merely in tuning: a keyword is a substring of a hostname and a word here
     * is a word. That is what lets bare "sex" live in this list and stay out of
     * the other one, and it is the whole reason the two are not shared.
     */
    val onScreen: List<String> = emptyList(),
)

/**
 * One line from the motivation library.
 *
 * Flat and forgiving on purpose, like every other content model here: the JSON
 * is the product and it is expected to grow from a couple of hundred items to
 * a few thousand without a single line of this file changing. Every field
 * except [id], [type] and [text] has a default, so a future asset with new
 * keys can never crash a build already on someone's phone.
 */
@Serializable
data class MotivationItem(
    val id: String,
    /** quote | scripture | prayer | reframe | urge_line | affirmation | story | fact */
    val type: String,
    val modes: List<String> = listOf("faith", "discipline"),
    val text: String,
    val attribution: String? = null,
    val source: String? = null,
    val sourceRef: String? = null,
    /** Scripture only; the translation the text was taken from. */
    val translation: String? = null,
    /** Stories only. */
    val title: String? = null,
    val themes: List<String> = emptyList(),
    /** The app's own trigger taxonomy, so a line can be matched to a man's pattern. */
    val triggers: List<String> = emptyList(),
    /** urge | daily | relapse | milestone | library */
    val moments: List<String> = emptyList(),
    val length: String = "short",
    val publicDomain: Boolean = true,
    /**
     * False means it has not been checked against its source yet, and the app
     * will not show it. Anything quoting a real person or a translation is
     * guilty until verified — a blocker that misattributes a line has no
     * business asking anyone to trust the rest of what it says.
     */
    val verified: Boolean = true,
) {
    fun visibleIn(faithMode: Boolean) =
        modes.contains(if (faithMode) "faith" else "discipline")

    /** "Marcus Aurelius · Meditations", or just the one that exists. */
    fun credit(): String? = listOfNotNull(
        attribution?.takeIf { it.isNotBlank() },
        sourceRef?.takeIf { it.isNotBlank() && it != attribution },
    ).joinToString(" · ").takeIf { it.isNotBlank() }
}

@Serializable
data class MotivationLibrary(
    val version: Int = 1,
    val count: Int = 0,
    val note: String = "",
    val items: List<MotivationItem> = emptyList(),
)
