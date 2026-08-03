package com.bastion.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every way to weaken a guard must cost something.
 *
 * Lock-in is the app's one hard promise: the man who set it up cannot be the man
 * who undoes it in a weak minute. That promise is not kept by any single
 * mechanism — it is kept by the absence of exceptions, and exceptions are added
 * by accident. Someone adds a helpful "just turn this off" link, wires it
 * straight to a setter, and the whole thing quietly becomes advisory.
 *
 * Two were found by audit rather than by anything failing:
 *
 *  - `standDownDns` set the flag directly. One tap on the Guard screen, no
 *    delay, no code, and Bastion stopped watching Private DNS.
 *  - Restoring a backup upserted guarded apps and the partner row. An older file
 *    reverted an app's mode, and a file from before the partner set his code
 *    restored a null hash — which lifted the partner lock outright.
 *
 * This is the cheap check that the next one does not ship. It reads source, which
 * is crude, and that is the point: it needs no device, no emulator, and no one
 * remembering to think about it.
 */
class NoUngatedOffSwitchTest {

    private val main = File("src/main/java/com/bastion/app")

    private fun source(path: String) = File(main, path).readText()

    /**
     * The DNS stand-down is the one that was already wrong. Pinned so it stays
     * fixed: it has to consult the lock before it writes anything.
     */
    @Test
    fun `the dns stand-down waits while locked in`() {
        val watchdog = source("guard/GuardWatchdog.kt")
        val body = watchdog.substringAfter("suspend fun standDownDns")
            .substringBefore("\n    /**")

        assertTrue(
            "standDownDns must check tamperLockEnabled before turning the watch off",
            body.contains("tamperLockEnabled"),
        )
        assertTrue(
            "standDownDns must queue a weakening rather than applying it while locked in",
            body.contains("requestWeakening"),
        )
    }

    /**
     * A backup restores a journey. It must never restore the things that guard
     * him, or exporting a file becomes a saved game to reload from.
     */
    @Test
    fun `restoring a backup cannot roll the guards back`() {
        val backup = source("data/repo/BackupRepository.kt")
        val import = backup.substringAfter("suspend fun import(")

        assertTrue(
            "import must know whether the user is locked in",
            import.contains("tamperLockEnabled"),
        )
        listOf("guardedApps", "learnedRules", "userDomains").forEach { field ->
            assertTrue(
                "$field must be withheld from a restore while locked in",
                import.contains("if (locked) emptyList() else backup.$field"),
            )
        }
        assertTrue(
            "the partner's passcode hash must survive a restore, or the lock lifts itself",
            import.contains("lockPasscodeHash"),
        )
    }

    /**
     * A snapshot of every place the UI touches a protection setting, so a new
     * one cannot appear unreviewed.
     *
     * The first version of this tried to be clever — regex out the calls, skip
     * the ones passing `true`, fail on the rest — and it was worthless twice
     * over. It had a typo that made it match nothing, which is how the typo was
     * found: it passed against a deliberately planted hole. Once fixed, it still
     * could not tell a tightening from a weakening, so it flagged *lengthening*
     * the cooling-off delay as an escape route.
     *
     * Counting is dumber and actually works. The numbers below are the reviewed
     * totals; adding any call changes one and fails here, which forces whoever
     * added it to say which kind it is. This test does not need to understand
     * the code — only to notice it changed.
     */
    @Test
    fun `every protection setter in the UI has been reviewed`() {
        val expected = mapOf(
            // Four turn the filter ON, which is always immediate and never
            // gated, and one turns it off through weakenOrQueue. The fifth is
            // the "two filters, no internet" card: a lateral move when Private
            // DNS actually filters, and queued like anything else when it does
            // not.
            "setVpnEnabled" to 6,
            // Locking in is instant; unlocking goes through the gate.
            "setTamperLock" to 1,
            // Lengthening the delay is a tightening and immediate; shortening
            // queues. Both live in the one call site.
            "setCoolingOffHours" to 1,
            // The DNS watch is only ever stood down from GuardWatchdog, which
            // does its own lock check. No screen writes it.
            "setDnsIntendedOn" to 0,
        )

        val actual = expected.keys.associateWith { setter ->
            File(main, "feature").walkTopDown()
                .filter { it.extension == "kt" }
                .sumOf { file -> Regex("""\.$setter\(""").findAll(file.readText()).count() }
        }

        assertTrue(
            "protection setter call sites changed — review each and update this test. " +
                "expected $expected but found $actual",
            actual == expected,
        )
    }
}
