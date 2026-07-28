package com.bastion.app.domain

/**
 * Rank is the headline number, not the streak.
 *
 * The whole emotional model of Bastion rests here: rank is cumulative and never
 * resets. It is earned by clean days *plus* habits, challenges, check-ins and
 * resisted urges, so a man who slips on day 61 does not go back to zero — he
 * keeps every ounce of who he has become and restarts only the streak.
 *
 * This is the single design decision that stops people deleting the app.
 */
enum class Rank(
    val tier: Int,
    val faithName: String,
    val secularName: String,
    val threshold: Int,
) {
    RECRUIT(1, "Recruit", "Recruit", 0),
    WATCHMAN(2, "Watchman", "Steady", 150),
    GUARDIAN(3, "Guardian", "Disciplined", 450),
    WARRIOR(4, "Warrior", "Forged", 1_000),
    KNIGHT(5, "Knight", "Tempered", 2_000),
    SENTINEL(6, "Sentinel", "Ironclad", 3_800),
    OVERCOMER(7, "Overcomer", "Master", 6_500);

    fun displayName(faithMode: Boolean): String = if (faithMode) faithName else secularName

    companion object {
        fun forPoints(points: Int): Rank = entries.last { points >= it.threshold }

        fun next(rank: Rank): Rank? = entries.getOrNull(rank.ordinal + 1)

        /**
         * Progress through the current rank, 0f..1f. Returns 1f at the final rank
         * so the medallion reads as complete rather than stuck.
         */
        fun progress(points: Int): Float {
            val current = forPoints(points)
            val next = next(current) ?: return 1f
            val span = (next.threshold - current.threshold).toFloat()
            return ((points - current.threshold) / span).coerceIn(0f, 1f)
        }

        fun pointsToNext(points: Int): Int? {
            val next = next(forPoints(points)) ?: return null
            return next.threshold - points
        }
    }
}

/** What each action is worth. Deliberately rewards building, not just abstaining. */
object RankPoints {
    const val CLEAN_DAY = 10
    const val HABIT_COMPLETED = 2
    const val CHALLENGE_DAY = 3
    const val CHECK_IN = 5
    const val URGE_RESISTED = 4
    const val LESSON_READ = 3
    const val PANIC_SESSION_COMPLETED = 6

    /**
     * Logging a slip honestly is worth points. Not a reward for slipping — a
     * reward for the honesty and the analysis that follow it, which is exactly
     * the behaviour that predicts recovery. Never negative: Bastion does not
     * take away what a man has already built.
     */
    const val SLIP_LOGGED_HONESTLY = 2
}
