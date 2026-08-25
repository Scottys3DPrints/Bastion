package com.bastion.app.guard.policy

import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.SignalEntity

/**
 * Noticing that a signal has stopped working, before a man does.
 *
 * ## The failure this exists for
 *
 * A view id belongs to another company's app. Instagram renames one in a
 * Tuesday release, and from that moment the signal matches nothing. Nothing
 * throws, nothing is logged, no screen changes. The guard reports itself as
 * fully armed and quietly does less every month.
 *
 * Learn Mode is the repair, and it is entirely reactive: it needs the man to
 * notice a feed got through, work out that a rule has drifted rather than that
 * the app is broken, remember the tool exists, and go and find it. Every one of
 * those steps is a step he takes while looking at a feed he was trying not to
 * look at.
 *
 * Bastion already holds both halves of the answer and had never put them
 * together. It knows how much a man uses each app, and it can know when each
 * signal last matched. A signal inside an app he opens constantly, which has
 * never once fired, is not proof of drift — but it is a good enough reason to
 * ask, and asking costs a card on a screen he already visits.
 *
 * ## What it deliberately will not say
 *
 * Only signals scoped to an app can drift this way, because only they depend on
 * someone else's layout. An address signal is a fact about a destination and
 * does not rot. And an uninstalled app is silence, not drift — reporting it
 * would fill the screen with noise about apps he has already dealt with, which
 * is how a warning system teaches people to ignore it.
 */
object SignalHealth {

    /** Opens within the window before a silent signal is worth asking about. */
    const val MIN_USES = 30

    /** How far back usage is counted. */
    const val WINDOW_DAYS = 14

    private val LAYOUT_KINDS = setOf(MatchType.VIEW_ID, MatchType.TEXT, MatchType.CONTENT_DESC)

    /**
     * One signal worth asking about, with the evidence that raised it.
     *
     * The count travels with the finding so the card can say "you have opened
     * Instagram 41 times and this has never matched" rather than "a rule may be
     * broken". The first is a fact he can check; the second is a mood.
     */
    data class Drifted(
        val signal: SignalEntity,
        val packageName: String,
        val opensInWindow: Int,
    )

    /**
     * @param opensInWindow opens per package over the last [WINDOW_DAYS]
     * @param installed packages present on the phone right now
     * @param windowStart epoch millis; a match older than this counts as silence
     */
    fun drifted(
        signals: List<SignalEntity>,
        opensInWindow: Map<String, Int>,
        installed: Set<String>,
        windowStart: Long,
    ): List<Drifted> = signals.mapNotNull { signal ->
        val scope = signal.scopePackage ?: return@mapNotNull null
        if (!signal.enabled) return@mapNotNull null
        if (signal.matchType !in LAYOUT_KINDS) return@mapNotNull null
        if (scope !in installed) return@mapNotNull null
        val opens = opensInWindow[scope] ?: 0
        if (opens < MIN_USES) return@mapNotNull null
        if (signal.lastMatchedAt >= windowStart) return@mapNotNull null
        Drifted(signal, scope, opens)
    }
}
