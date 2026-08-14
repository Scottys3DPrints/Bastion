package com.bastion.app.data.repo

import com.bastion.app.data.content.ContentRepository
import com.bastion.app.data.content.MotivationItem
import com.bastion.app.data.content.TriggerKeys
import com.bastion.app.data.db.FeedDao
import com.bastion.app.data.db.FeedSeenEntity
import com.bastion.app.data.prefs.Settings
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * The Well — a feed that ends.
 *
 * Everything here exists to keep one promise: this is something to reach for
 * *instead of* the feed that was taken away, and it must not become the same
 * trap wearing better clothes. So the interesting logic is all about stopping,
 * not about serving:
 *
 *  - the day's portion is a fixed size, decided once and remembered;
 *  - a card that has been seen is not served again, so "you're caught up" is a
 *    true statement rather than a pause before more;
 *  - there is no scoring loop, no engagement signal, nothing that learns what
 *    keeps a thumb moving. Ordering is for resonance — mode, the man's own
 *    triggers, the hour — and then it stops.
 *
 * The one concession to being a feed at all is variety: cards are interleaved
 * so no two of a kind sit together, because a list of forty quotes is a
 * document and the rhythm of unlike things is what makes something feel alive.
 */
class FeedRepository(
    private val content: ContentRepository,
    private val feedDao: FeedDao,
    private val journey: JourneyRepository,
) {

    /** One card in the day's portion. */
    sealed interface Card {
        val key: String

        /** A line from the bundled library. */
        data class Words(val item: MotivationItem) : Card {
            override val key: String get() = item.id
        }

        /**
         * The man's own progress, turned into feed content.
         *
         * Deliberately never comparative — no "most people are on day 30". The
         * only number here is his, and the only claim made about it is that it
         * is real.
         */
        data class Progress(val headline: String, val detail: String) : Card {
            override val key: String get() = "progress_$headline"
        }

        /** The end. Not a pause — the portion is finished. */
        data class Caught(val message: String) : Card {
            override val key: String get() = "caught_up"
        }
    }

    /**
     * Today's portion.
     *
     * @param extra a further batch on top of today's, for pull-to-refresh. It
     *   is bounded and it is opt-in by gesture; what it is not is automatic,
     *   which is the whole difference between refilling a glass and leaving a
     *   tap running.
     */
    suspend fun dailyFeed(settings: Settings, extra: Int = 0): List<Card> {
        val today = LocalDate.now().toEpochDay()
        val hour = java.time.LocalTime.now().hour

        val alreadySeenToday = feedDao.seenOn(today).map { it.itemId }
        val target = DAILY_PORTION + extra

        val pool = candidates(settings, hour, today)
        // Cards already served today come back in their original order, so
        // scrolling up shows what was there rather than reshuffling under the
        // thumb — a feed that rearranges itself while you read it is the
        // disorienting part of the real ones.
        val byId = pool.associateBy { it.id }
        val served = alreadySeenToday.mapNotNull(byId::get)

        val seenEver = feedDao.seenIds().toSet()
        val fresh = pool.filter { it.id !in seenEver }

        // Mixed first, then cut. This was the other way round, and that is the
        // whole reason a day could come out as nothing but scripture: the
        // portion was sliced off the top of a sorted pool and only then
        // interleaved, so if the top of the pool was one type, interleaving a
        // handful of identical things achieved exactly nothing.
        val chosen = served + FeedMix.interleave(fresh, hour).take((target - served.size).coerceAtLeast(0))
        val cards = chosen.map<MotivationItem, Card>(Card::Words).toMutableList()

        progressCard()?.let { card ->
            // Roughly a third of the way in: far enough that the feed has
            // already given something before it talks about him.
            val at = (cards.size / 3).coerceIn(0, cards.size)
            cards.add(at, card)
        }

        cards.add(Card.Caught(closingLine(fresh.size <= chosen.size - served.size)))
        return cards
    }

    /** Records the portion as served, so tomorrow is genuinely different. */
    suspend fun markServed(cards: List<Card>) {
        val today = LocalDate.now().toEpochDay()
        val rows = cards.filterIsInstance<Card.Words>()
            .map { FeedSeenEntity(itemId = it.item.id, epochDay = today) }
        if (rows.isNotEmpty()) feedDao.markSeen(rows)
    }

    /** Lets the well refill when it has genuinely run dry. */
    suspend fun drawAgain() = feedDao.clearSeen()

    suspend fun hasRunDry(settings: Settings): Boolean {
        val seen = feedDao.seenIds().toSet()
        // The day seed does not matter here: this asks whether anything is left
        // at all, and reordering an empty set does not make it non-empty.
        val today = java.time.LocalDate.now().toEpochDay()
        return candidates(settings, java.time.LocalTime.now().hour, today).none { it.id !in seen }
    }

    // --- composition -------------------------------------------------------

    /**
     * Everything eligible, ordered by how likely it is to land — not by how
     * likely it is to keep someone scrolling.
     *
     * Three weights, all of them transparent and none of them learned from
     * behaviour: the mode he chose, the triggers he reported, and the hour of
     * the day. A steadying line at 1am, something with more horizon in it at
     * eight in the morning.
     */
    private suspend fun candidates(
        settings: Settings,
        hour: Int,
        daySeed: Long,
    ): List<MotivationItem> {
        val triggers = TriggerKeys.of(settings.triggers)
        val saved = settings.savedMotivation.toSet()

        return content.motivationFor(settings.faithMode)
            .filter { "library" in it.moments || "daily" in it.moments || "urge" in it.moments }
            // Shuffled first, then stably weighted. Kotlin's sorts are stable,
            // so the shuffle survives inside each band of equal weight — which
            // is where two thousand items actually sit.
            //
            // The tiebreak used to be `id.hashCode().mod(7)`: seven buckets, and
            // the same seven every day this app will ever run. Within a band the
            // order was therefore fixed forever, and the only reason a second
            // day looked different was that the first day's items had been
            // marked as seen.
            .sortedBy { FeedMix.shuffleKey(it.id, daySeed) }
            .sortedByDescending { item ->
                var weight = 0
                if (item.triggers.any(triggers::contains)) weight += 3
                if (item.id in saved) weight += 2
                weight
            }
    }

    /** His own days, said plainly and without comparison. */
    private suspend fun progressCard(): Card.Progress? {
        val state = runCatching { journey.state.first() }.getOrNull() ?: return null
        val days = state.currentStreak
        if (days <= 0) return null
        return Card.Progress(
            headline = "$days ${if (days == 1) "day" else "days"} clean",
            detail = "That is not a number on a screen. That is days you actually lived.",
        )
    }

    private fun closingLine(ranDry: Boolean): String = if (ranDry) {
        "That is everything the well has for now. Come back tomorrow — or draw it again."
    } else {
        "You're caught up. Go be that man today."
    }

    private companion object {
        /**
         * The size of a day's portion.
         *
         * Small enough to finish in a couple of minutes, which is the point: the
         * feed has to be something you *complete*, not something you escape.
         */
        const val DAILY_PORTION = 14
    }
}

/**
 * How a day's portion is ordered, with nothing else in it.
 *
 * Pulled out of the repository because the bug it now carries a test for was
 * invisible while it lived there: ordering was three private helpers tangled
 * with a database, a clock and a settings object, so the only way to ask "is a
 * portion ever all one type?" was to run the app for a day and look.
 *
 * Everything here is a pure function of a list, an hour and a day number.
 */
internal object FeedMix {

    /**
     * A per-day order that is settled rather than random.
     *
     * Deterministic, so scrolling up shows what was there and a recomposition
     * does not rearrange the screen under a thumb — but different every day,
     * which the old hash was not. Splitmix-style mixing because the input is a
     * string hash and a day number, and simply adding those leaves long runs of
     * neighbouring ids sorting together.
     */
    fun shuffleKey(id: String, daySeed: Long): Int {
        var x = id.hashCode().toLong() * -0x61c8864680b583ebL + daySeed * -0x7ee3623a03d3c11L
        x = x xor (x ushr 30)
        x *= -0x40a7b892e31b1a47L
        x = x xor (x ushr 27)
        x *= -0x6b2fb644ecceee15L
        return (x xor (x ushr 31)).toInt()
    }

    /**
     * Late at night wants steadying; the morning wants something to aim at.
     *
     * The hours are the same ones the dawn gradient already uses, so the words
     * and the light agree about what time it is.
     */
    fun leadsAtThisHour(type: String, hour: Int): Boolean {
        val lateNight = hour >= 22 || hour < 5
        // Scripture is on the steadying side, and leaving it off was a silent
        // regression when the library was rebuilt. The types it used to name —
        // urge_line, reframe, affirmation — were the app's own writing, and
        // they are gone; scripture is now the largest thing a man in Faith mode
        // has at midnight. Unclassified, it scored zero at every hour, so the
        // late-night feed lost almost everything it was meant to reach for.
        //
        // The retired names stay listed. They cost nothing and they keep this
        // honest if an older library is ever loaded beside a newer build.
        val steadying = type == "scripture" || type == "prayer" ||
            type == "urge_line" || type == "reframe"
        val aspirational = type == "quote" || type == "story" ||
            type == "affirmation"
        return if (lateNight) steadying else aspirational
    }

    /**
     * Spreads the types out so no two of a kind sit together.
     *
     * This is the only thing here borrowed from real feeds, and it is borrowed
     * because it is the honest part: variety is what makes a stream feel alive.
     * Round-robin across the types rather than a shuffle, so the rhythm is
     * reliable rather than random.
     */
    fun interleave(items: List<MotivationItem>, hour: Int): List<MotivationItem> {
        val queues = items.groupBy { it.type }.mapValues { ArrayDeque(it.value) }
        // The hour decides which type goes first, not which type wins.
        //
        // It used to be a weight, and a weight is the wrong instrument: adding
        // two points to every scripture puts all five hundred of them above all
        // fifteen hundred quotes, so the hour did not colour the feed, it chose
        // the feed. A man opening it at midnight got scripture and nothing else;
        // at eight in the morning, quotes and nothing else. Leading the rotation
        // keeps what the hour was for — steadying late, further-looking early —
        // while every portion still holds both.
        val order = queues.keys.sortedByDescending { leadsAtThisHour(it, hour) }.toMutableList()
        val out = ArrayList<MotivationItem>(items.size)

        var index = 0
        while (out.size < items.size && order.isNotEmpty()) {
            val type = order[index % order.size]
            val queue = queues[type]
            if (queue.isNullOrEmpty()) {
                order.remove(type)
                if (order.isEmpty()) break
                index %= order.size
                continue
            }
            out.add(queue.removeFirst())
            index++
        }
        return out
    }

}
