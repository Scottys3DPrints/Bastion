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
