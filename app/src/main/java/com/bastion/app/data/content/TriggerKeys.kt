package com.bastion.app.data.content

/**
 * Bridges the trigger names a man picks from the ones the library is tagged with.
 *
 * The app stores triggers the way they are shown — "Late night", "Social media"
 * — because that list is written for a human choosing from it. The content
 * library tags with machine keys — `late_night`, `social_media` — because that
 * list is written for filtering. Both are right for their job, and something
 * has to translate, or the trigger matching silently never matches and the
 * whole point of tagging is lost with no error to notice.
 */
object TriggerKeys {

    /** "Late night" → "late_night". Unknown values pass through harmlessly. */
    fun of(displayName: String): String =
        displayName.trim().lowercase().replace(' ', '_')

    fun of(displayNames: Collection<String>): Set<String> =
        displayNames.map(::of).filter { it.isNotBlank() }.toSet()
}
