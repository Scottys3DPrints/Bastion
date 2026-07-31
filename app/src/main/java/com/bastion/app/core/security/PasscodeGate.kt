package com.bastion.app.core.security

import com.bastion.app.data.prefs.SettingsStore
import com.bastion.app.data.repo.SocialRepository

/**
 * Rate-limits attempts at the partner-held code.
 *
 * PBKDF2 makes one guess cost a fraction of a second. That is plenty against a
 * stolen database and nothing at all against the actual threat here: a man
 * sitting with his own phone at 1am, who can type a four-digit code ten
 * thousand times and has all night. The slow hash never touched that, because
 * he is not attacking the hash — he is using the front door.
 *
 * So the door itself gets a delay. Failures accumulate; past a small allowance
 * each one shuts the dialog for a while, doubling up to a cap. The numbers are
 * chosen so an honest mis-type costs nothing and a systematic search stops
 * being worth starting — after ten wrong codes the wait is already long enough
 * that the urge it was protecting against has usually passed, which is the
 * whole mechanism of the lock rather than a side effect.
 *
 * The counter is deliberately not wiped by anything short of a correct code or
 * clearing the app's data. Clearing app data ends every guard in Bastion at
 * once and is the escape hatch of last resort; pretending otherwise would be
 * the dishonest version of this.
 */
class PasscodeGate(
    private val settings: SettingsStore,
    private val social: SocialRepository,
) {

    /** Milliseconds until a code may be tried again; 0 when it can be tried now. */
    suspend fun waitMillis(): Long =
        (settings.current().passcodeLockedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * Checks a code, applying the penalty on failure.
     *
     * Returns [Result.Wait] without ever hashing when a penalty is outstanding,
     * so the delay cannot be worn down by hammering through it.
     */
    suspend fun attempt(code: String): Result {
        val remaining = waitMillis()
        if (remaining > 0) return Result.Wait(remaining)

        if (social.verifyPasscode(code)) {
            settings.setPasscodeFailures(0)
            settings.setPasscodeLockedUntil(0L)
            return Result.Unlocked
        }

        val failures = settings.current().passcodeFailures + 1
        settings.setPasscodeFailures(failures)

        val delay = penaltyMillis(failures)
        if (delay > 0) settings.setPasscodeLockedUntil(System.currentTimeMillis() + delay)
        return Result.Wrong(delay)
    }

    sealed interface Result {
        data object Unlocked : Result

        /** Wrong code. [waitMillis] is 0 while still inside the free allowance. */
        data class Wrong(val waitMillis: Long) : Result

        /** Not even checked — a penalty from earlier is still running. */
        data class Wait(val millis: Long) : Result
    }
}

/** Wrong entries allowed before any waiting starts. */
private const val FREE_ATTEMPTS = 3

/** First penalty, doubled per failure after that. */
private const val BASE_DELAY_MILLIS = 30_000L

/** Beyond this the delay stops growing; an hour is already decisive. */
private const val MAX_DELAY_MILLIS = 60L * 60L * 1000L

/**
 * How long the door stays shut after [failures] consecutive wrong codes.
 *
 * Separated from the storage it reads so the escalation itself can be checked
 * without a DataStore: getting this wrong in either direction is the whole
 * difference between a lock and an annoyance.
 */
internal fun penaltyMillis(failures: Int): Long {
    val over = failures - FREE_ATTEMPTS
    if (over <= 0) return 0L
    // Guarded before the shift rather than after: `shl` on a Long wraps its
    // count at 64, so a large enough `over` would silently produce a *shorter*
    // delay than a smaller one.
    if (over >= 12) return MAX_DELAY_MILLIS
    return (BASE_DELAY_MILLIS shl (over - 1)).coerceAtMost(MAX_DELAY_MILLIS)
}

/** "45s" / "12 min" — short enough for a dialog's supporting line. */
fun formatWait(millis: Long): String {
    val seconds = (millis + 999) / 1000
    return if (seconds < 60) "${seconds}s" else "${(seconds + 59) / 60} min"
}
