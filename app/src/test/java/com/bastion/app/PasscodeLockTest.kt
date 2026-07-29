package com.bastion.app

import com.bastion.app.core.security.PasscodeLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The partner lock is only worth having if the stored value resists the person
 * who can read it — which, here, is the user himself in a weak moment.
 */
class PasscodeLockTest {

    @Test
    fun `the right code opens it`() {
        val stored = PasscodeLock.hash("4821")
        assertTrue(PasscodeLock.verify("4821", stored))
    }

    @Test
    fun `a wrong code does not`() {
        val stored = PasscodeLock.hash("4821")
        listOf("4822", "482", "48210", "", " 4821").forEach {
            assertFalse("'$it' should not unlock", PasscodeLock.verify(it, stored))
        }
    }

    @Test
    fun `the same code hashes differently every time`() {
        // A per-code random salt: identical codes must not produce identical
        // stored values, or one cracked code reveals every other.
        assertNotEquals(PasscodeLock.hash("1234"), PasscodeLock.hash("1234"))
    }

    @Test
    fun `both still verify despite different salts`() {
        assertTrue(PasscodeLock.verify("1234", PasscodeLock.hash("1234")))
        assertTrue(PasscodeLock.verify("1234", PasscodeLock.hash("1234")))
    }

    @Test
    fun `a missing or corrupt stored value is never a free pass`() {
        listOf(null, "", "   ", "garbage", "1:2", "notanumber:aaa:bbb", "120000:!!:!!")
            .forEach { assertFalse("'$it' must not unlock", PasscodeLock.verify("1234", it)) }
    }

    @Test
    fun `the stored format carries its own iteration count`() {
        // So the cost can be raised later without invalidating existing codes.
        val parts = PasscodeLock.hash("1234").split(":")
        assertEquals(3, parts.size)
        assertTrue(parts[0].toInt() >= 100_000)
    }
}
