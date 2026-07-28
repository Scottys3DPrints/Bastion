package com.bastion.app.core.design

import androidx.compose.ui.graphics.Color

/**
 * Colours for data marks only.
 *
 * Distinct from [BastionColors], which dresses the interface. Chart colour has a
 * job to do that decoration does not: it must stay separable for a colourblind
 * reader, hold its hue against the near-black surface, and never be the *only*
 * thing carrying meaning.
 *
 * These were not chosen by eye — they were validated against the #0E1220 surface
 * for lightness band, chroma floor, colour-vision-deficiency separation and
 * contrast. The interface's Sage and Bronze failed that test badly when used as
 * data: Bronze against Amber came out at ΔE 3.4 for *normal* vision, meaning
 * nobody could have told a clean bar from a slip bar.
 *
 *   clean ↔ slip:  ΔE 19.8 normal · 8.4 protan · 24.4 tritan — passes
 *
 * Colour still never travels alone. Slip cells carry a marker as well as a hue.
 */
object ChartColors {

    /** Clean days, resisted urges — anything that went well. */
    val Clean = Color(0xFF199E70)

    /** Slips. Warm amber by deliberate choice: this app never shouts in red. */
    val Slip = Color(0xFFC98500)

    /** Third series, only if one is ever genuinely needed. */
    val Neutral = Color(0xFF3987E5)

    /** Unlogged or out-of-range calendar cells. Recessive, not a data colour. */
    val Empty = Color(0xFF1C2440)

    /** Grid lines and axes: present, never competing with the marks. */
    val Grid = Color(0xFF212942)

    /** Single-series magnitude bars. One hue; the axis carries the meaning. */
    val Magnitude = Color(0xFF199E70)

    /** Fill under a line, at low alpha. */
    fun fill(color: Color) = color.copy(alpha = 0.18f)
}
