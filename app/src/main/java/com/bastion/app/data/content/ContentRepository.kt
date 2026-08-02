package com.bastion.app.data.content

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Reads the bundled content library. Everything is lazy and cached — the whole
 * set is small enough to keep in memory once, and the panic flow must never
 * wait on disk.
 */
class ContentRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var briefs: DailyBriefs? = null
    private var timeline: BenefitTimeline? = null
    private var education: Education? = null
    private var challenges: Challenges? = null
    private var mentor: MentorScript? = null
    private var habits: HabitCatalogue? = null
    private var blocklist: Blocklist? = null
    private var motivation: MotivationLibrary? = null

    suspend fun dailyBriefs(): DailyBriefs = briefs
        ?: load("daily_briefs.json", DailyBriefs()).also { briefs = it }

    suspend fun benefitTimeline(): BenefitTimeline = timeline
        ?: load("benefit_timeline.json", BenefitTimeline()).also { timeline = it }

    suspend fun education(): Education = education
        ?: load("education.json", Education()).also { education = it }

    suspend fun challenges(): Challenges = challenges
        ?: load("challenges.json", Challenges()).also { challenges = it }

    suspend fun mentorScript(): MentorScript = mentor
        ?: load("mentor.json", MentorScript()).also { mentor = it }

    suspend fun habitCatalogue(): HabitCatalogue = habits
        ?: load("habits.json", HabitCatalogue()).also { habits = it }

    suspend fun blocklist(): Blocklist = blocklist
        ?: load("blocklist.json", Blocklist()).also { blocklist = it }

    /**
     * Brief for a given day of the journey. Content is 30 days long but a man
     * may well be on day 340, so it cycles rather than running dry.
     */
    suspend fun briefForDay(dayOfJourney: Int): DailyBrief? {
        val all = dailyBriefs().days
        if (all.isEmpty()) return null
        val exact = all.firstOrNull { it.day == dayOfJourney }
        if (exact != null) return exact
        val index = ((dayOfJourney - 1).coerceAtLeast(0)) % all.size
        return all[index]
    }

    suspend fun motivation(): MotivationLibrary = motivation
        ?: load("motivation.json", MotivationLibrary()).also { motivation = it }

    /**
     * Everything this man is allowed to see.
     *
     * Unverified items are filtered out here rather than at each call site, so
     * there is exactly one place where "has this been checked" is enforced and
     * no new surface can accidentally skip it.
     */
    suspend fun motivationFor(faithMode: Boolean): List<MotivationItem> =
        motivation().items.filter { it.verified && it.visibleIn(faithMode) }

    /**
     * One line for a moment.
     *
     * Prefers items tagged with a trigger the man actually has, which is what
     * makes this better than a random quote generator — the same ninety
     * seconds hits differently when the words name the thing he is in.
     *
     * @param avoidId the line shown last time, so the same one never lands
     *   twice running; on a small pool this is the difference between the
     *   feature feeling alive and feeling broken.
     * @param daySeed pass an epoch day to make the choice stable for a whole
     *   day — "today's word" must not change every time the home screen
     *   recomposes.
     */
    suspend fun motivationForMoment(
        faithMode: Boolean,
        moment: String,
        userTriggers: Set<String> = emptySet(),
        avoidId: String? = null,
        daySeed: Long? = null,
        maxLength: String? = null,
    ): MotivationItem? {
        val pool = motivationFor(faithMode)
            .filter { moment in it.moments && it.id != avoidId }
            .filter { maxLength == null || it.length == maxLength }
        // Falling back to the unfiltered pool rather than returning null: a
        // panic screen with no words on it is the worst possible failure, so
        // every filter here is a preference, not a requirement.
        val usable = pool.ifEmpty {
            motivationFor(faithMode).filter { moment in it.moments }
        }
        if (usable.isEmpty()) return null

        val focused = usable.filter { it.triggers.any(userTriggers::contains) }.ifEmpty { usable }
        return if (daySeed != null) focused[daySeed.mod(focused.size)] else focused.random()
    }

    /** Benefit cards earned so far, newest unlock first. */
    suspend fun unlockedBenefits(cleanDays: Int): List<BenefitCard> =
        benefitTimeline().cards.filter { it.day <= cleanDays }.sortedByDescending { it.day }

    suspend fun nextBenefit(cleanDays: Int): BenefitCard? =
        benefitTimeline().cards.filter { it.day > cleanDays }.minByOrNull { it.day }

    private suspend inline fun <reified T> load(asset: String, fallback: T): T =
        withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("content/$asset").bufferedReader().use { it.readText() }
            }.mapCatching { json.decodeFromString<T>(it) }
                .getOrElse { error ->
                    // A malformed content file degrades that one section; it must
                    // never take down the app someone opened at 1am.
                    Log.e(TAG, "Could not load content/$asset", error)
                    fallback
                }
        }

    private companion object { const val TAG = "ContentRepository" }
}
