package com.bastion.app.core

import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app speaks one language, so its dates have to speak it too.
 *
 * Every date and time in Bastion was formatted with the *system* locale, while
 * every word in Bastion is English and there are no translations. On a phone set
 * to another language that produced a screen half in each: "Today at 8 ق.ظ",
 * a day picker reading `شنبه 15` under the heading "DAY", and a time grid of
 * `2 ب.ظ` under "ROUGHLY WHAT TIME".
 *
 * It is not a cosmetic complaint. The log flow is the one screen a man uses
 * while he is upset, at speed, wanting it over with — and it was asking him to
 * pick a day and an hour out of a script the rest of the app never uses. The
 * calendar, the habit week strip and the streak history had the same split.
 *
 * The honest fix is not to translate the dates back; it is to admit what the
 * app is. Bastion is written in English. Until it is translated, an English
 * date beside an English sentence is the consistent thing, and it is what a man
 * reading the sentence can actually parse.
 *
 * One place, so this cannot drift back a formatter at a time.
 */
object AppDates {

    /**
     * Deliberately [Locale.UK] rather than [Locale.ENGLISH].
     *
     * The app writes "8 September", not "September 8", and the tone throughout
     * is British — "whilst" would be too far, but day-before-month is already
     * how every hand-written string on these screens reads. Locale.ENGLISH
     * would quietly flip the order in the patterns that use it.
     */
    val LOCALE: Locale = Locale.UK

    /** A pattern formatter that ignores whatever language the phone is set to. */
    fun pattern(pattern: String): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, LOCALE)
}
