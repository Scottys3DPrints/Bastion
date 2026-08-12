package com.bastion.app.guard.accessibility

/**
 * Whether a video title on screen is one a man trying to quit should not open.
 *
 * A deliberately different tool from the domain filter, and the difference is
 * the whole reason this exists. `DomainFilter` matches substrings inside a
 * hostname, which is why bare `sex` is kept out of it — `essex.gov.uk` would be
 * blocked by a rule meant for something else. Here the unit is a word, so `sex`
 * can be listed without taking Essex with it, and titles are where the material
 * that gets past a domain list actually lives: YouTube's own domain is never
 * going to be blocked, and the recommendation rail does not care what a
 * blocklist thinks.
 *
 * ## The privacy line
 *
 * Bastion's contract is that message bodies, posts and field contents are never
 * read. This does not bend it and must not be allowed to:
 *
 *  - it runs only in the apps named in [BastionAccessibilityService.WATCH_APPS],
 *    which are video apps, never a messaging app;
 *  - only short strings are considered, because a title is short and a
 *    paragraph of somebody's writing is not;
 *  - nothing read here is stored, logged, or kept past the comparison. The
 *    function takes a string and returns a word from a list Bastion shipped.
 *
 * The thing being compared against is always Bastion's own list. No text from
 * the screen is ever the output.
 */
object TitleFilter {

    /**
     * The matched word, or null.
     *
     * Returns the word rather than a boolean so the wall can say why it closed
     * something. A man told only "blocked" learns nothing and assumes the app
     * is broken when it is wrong; a man told which word it caught can see at
     * once whether it was right, and that is the difference between a filter he
     * trusts and one he switches off.
     */
    fun match(text: String, words: List<String>): String? {
        if (text.isEmpty() || text.length > MAX_TITLE || words.isEmpty()) return null
        val plain = normalise(text)
        if (plain.isBlank()) return null
        // Once folded as well, so the obvious dodge does not work. p0rn and s3x
        // are not clever, but they are free, and a filter that any teenager can
        // step around in one keystroke is decoration.
        val folded = fold(plain)
        // And once with spaced-out letters put back together, because s-e-x
        // survives normalising as three separate letters. Runs of three or more
        // single letters only: two in a row is ordinary English ("a b side"),
        // three is somebody spelling a word out.
        val joined = joinSpacedLetters(plain)
        val jfolded = if (joined == plain) folded else fold(joined)
        return words.firstOrNull { word ->
            val needle = " " + normalise(word).trim() + " "
            if (needle.isBlank()) false
            else plain.contains(needle) || folded.contains(needle) ||
                joined.contains(needle) || jfolded.contains(needle)
        }
    }

    /**
     * Down to words with spaces around them, so a match is a word and not a
     * fragment.
     *
     * Everything that is not a letter or a digit becomes a space, which handles
     * punctuation, emoji, hyphens and the decorative junk titles are full of —
     * `S.E.X.Y` and `s-e-x-y` both fall apart into letters rather than sliding
     * past a list that expected one token. The whole string is wrapped in
     * spaces so the first and last words have boundaries like every other.
     */
    private fun normalise(raw: String): String {
        val out = StringBuilder(raw.length + 2)
        out.append(' ')
        var lastWasSpace = true
        raw.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) {
                out.append(ch)
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        if (!lastWasSpace) out.append(' ') else if (out.length == 1) return ""
        return out.toString()
    }

    /**
     * Runs of single letters, rejoined into the word they were spelling.
     *
     * `s-e-x` and `s.e.x` both normalise to three one-letter tokens, which a
     * word matcher walks straight past while a human reads them without
     * pausing. Three is the floor because two single letters in a row happen in
     * ordinary titles and three almost never do by accident.
     */
    private fun joinSpacedLetters(plain: String): String {
        val tokens = plain.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.size < MIN_SPELLED_RUN) return plain
        val out = StringBuilder(" ")
        var run = mutableListOf<String>()
        fun flush() {
            if (run.size >= MIN_SPELLED_RUN) out.append(run.joinToString("")).append(' ')
            else run.forEach { out.append(it).append(' ') }
            run = mutableListOf()
        }
        tokens.forEach { token ->
            if (token.length == 1) run.add(token) else { flush(); out.append(token).append(' ') }
        }
        flush()
        return out.toString()
    }

    /** See [joinSpacedLetters]. */
    private const val MIN_SPELLED_RUN = 3

    /** Digits back to the letters they stand in for, inside words only. */
    private fun fold(plain: String): String {
        val out = StringBuilder(plain.length)
        plain.forEach { ch ->
            out.append(
                when (ch) {
                    '0' -> 'o'
                    '1' -> 'i'
                    '3' -> 'e'
                    '4' -> 'a'
                    '5' -> 's'
                    '7' -> 't'
                    '@' -> 'a'
                    else -> ch
                }
            )
        }
        return out.toString()
    }

    /**
     * A title is short. Anything longer is a description, a comment or a
     * transcript, and reading those is not what this was allowed to do.
     */
    const val MAX_TITLE = 140
}
