package com.bastion.app.core.design

import androidx.compose.ui.graphics.Color

/**
 * A quiet fortress at dawn. Deep midnight base, forged bronze for what you have
 * earned, sage for things growing. Amber — never red — marks a slip: mistakes
 * are for learning, not punishment.
 */
object BastionColors {
    val Midnight = Color(0xFF0E1220)
    val MidnightDeep = Color(0xFF090C16)
    val Surface = Color(0xFF151B2E)
    val SurfaceRaised = Color(0xFF1C2440)
    val SurfaceHigh = Color(0xFF242E4F)
    val Outline = Color(0xFF2E3A5E)
    val OutlineSoft = Color(0xFF212942)

    val Bronze = Color(0xFFC8A24B)
    val BronzeBright = Color(0xFFE8C877)
    val BronzeDeep = Color(0xFF8A6E2E)

    /**
     * A border that can actually be seen. 3.6:1 on the screen background.
     *
     * [Outline] and [OutlineSoft] measure 1.7:1 and 1.3:1 — below the 3:1 that
     * WCAG asks of a graphic carrying meaning, and in practice invisible. They
     * are right for a hairline that only has to hint at a seam. Anything whose
     * *presence* is the information — the edge of an unselected chip, the
     * outline marking a day a habit was not due — needs this instead.
     */
    val OutlineStrong = Color(0xFF5A6BA2)

    /**
     * An empty cell or the unfilled part of a track.
     *
     * Tuned against two opposing requirements, which is why it is not simply as
     * bright as possible. It has to be visible against the page — [SurfaceRaised]
     * at 1.2:1 was not, and the grid vanished — but every filled state is then
     * read against *this*, not against the page, so making it brighter squeezes
     * the contrast out of the very marks it exists to frame. At 1.5:1 over the
     * background it is unmistakably there, and kept-over-empty lands at 4.2:1
     * where a brighter track had it at 2.9:1.
     */
    val TrackEmpty = Color(0xFF2A3458)

    val Sage = Color(0xFF6FA287)
    val SageBright = Color(0xFF8FC4A7)
    val SageDeep = Color(0xFF3F6B56)

    /**
     * Part-done, as distinct from done. 4.8:1.
     *
     * [SageDeep] is 2.8:1 and disappears against the background, so a half-
     * finished day looked the same as an untouched one — which is the single
     * thing a counting habit exists to show.
     */
    val SagePartial = Color(0xFF558D72)

    val Steel = Color(0xFF5C7BA6)
    val SteelBright = Color(0xFF89ACD8)

    /** Reserved for slips and cautions. Warm, steady, never alarming. */
    val Amber = Color(0xFFD99A4E)
    val AmberSoft = Color(0xFF6B4B2A)

    val TextPrimary = Color(0xFFEAEEF7)
    val TextSecondary = Color(0xFF96A0BA)

    /**
     * The quietest text that is still readable. 6.0:1.
     *
     * [TextMuted] measures 3.5:1, which is under the 4.5:1 WCAG asks of body
     * text — fine for something decorative, not fine for a label, a unit, a
     * weekday or a legend, all of which are things a man has to read. Use this
     * for anything carrying meaning and keep [TextMuted] for text that is
     * genuinely just texture.
     */
    val TextTertiary = Color(0xFF8792AE)

    val TextMuted = Color(0xFF67718C)

    /** Dawn horizon: the through-line of the whole app. */
    val DawnTop = Color(0xFF141B33)
    val DawnMid = Color(0xFF2C2C4E)
    val DawnGlow = Color(0xFF6B4E58)
    val DawnHorizon = Color(0xFFC8894B)
}
