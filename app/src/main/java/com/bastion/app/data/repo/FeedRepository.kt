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

        val pool = candidates(settings, hour)
        // Cards already served today come back in their original order, so
        // scrolling up shows what was there rather than reshuffling under the
        // thumb — a feed that rearranges itself while you read it is the
        // disorienting part of the real ones.
        val byId = pool.associateBy { it.id }
        val served = alreadySeenToday.mapNotNull(byId::get)

        val seenEver = feedDao.seenIds().toSet()
        val fresh = pool.filter { it.id !in seenEver }

        val chosen = served + fresh.take((target - served.size).coerceAtLeast(0))
        val cards = interleave(chosen).map<MotivationItem, Card>(Card::Words).toMutableList()

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
        return candidates(settings, java.time.LocalTime.now().hour).none { it.id !in seen }
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
    private suspend fun candidates(settings: Settings, hour: Int): List<MotivationItem> {
        val triggers = TriggerKeys.of(settings.triggers)
        val saved = settings.savedMotivation.toSet()

        return content.motivationFor(settings.faithMode)
            .filter { "library" in it.moments || "daily" in it.moments || "urge" in it.moments }
            .sortedByDescending { item ->
                var weight = 0
                if (item.triggers.any(triggers::contains)) weight += 3
                if (item.id in saved) weight += 2
                weight += toneWeight(item, hour)
                // A stable tiebreak per day, so the order is settled rather
                // than shuffling every time the screen recomposes.
                weight * 10 + (item.id.hashCode().mod(7))
            }
    }

    /**
     * Late at night wants steadying; the morning wants something to aim at.
     *
     * The hours are the same ones the dawn gradient already uses, so the words
     * and the light agree about what time it is.
     */
    private fun toneWeight(item: MotivationItem, hour: Int): Int {
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
        val steadying = item.type == "scripture" || item.type == "prayer" ||
            item.type == "urge_line" || item.type == "reframe"
        val aspirational = item.type == "quote" || item.type == "story" ||
            item.type == "affirmation"
        return when {
            lateNight && steadying -> 2
            !lateNight && aspirational -> 1
            else -> 0
        }
    }

    /**
     * Spreads the types out so no two of a kind sit together.
     *
     * This is the only thing here borrowed from real feeds, and it is borrowed
     * because it is the honest part: variety is what makes a stream feel alive.
     * Round-robin across the types rather than a shuffle, so the rhythm is
     * reliable rather than random.
     */
    private fun interleave(items: List<MotivationItem>): List<MotivationItem> {
        val queues = items.groupBy { it.type }.mapValues { ArrayDeque(it.value) }
        val order = queues.keys.toMutableList()
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
