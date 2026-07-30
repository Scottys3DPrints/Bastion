package com.bastion.app

import com.bastion.app.core.security.BackupCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is the one copy of a man's journey that outlives his phone. It has to
 * come back byte-for-byte, and it has to be useless to anyone who finds it.
 */
class BackupCodecTest {

    private val plaintext = """{"days":[{"epochDay":20000,"status":"CLEAN"}]}""".toByteArray()

    @Test
    fun `round trips exactly`() {
        val sealed = BackupCodec.encrypt(plaintext, "correct horse battery")
        assertArrayEquals(plaintext, BackupCodec.decrypt(sealed, "correct horse battery"))
    }

    @Test
    fun `the wrong passphrase fails loudly rather than returning rubbish`() {
        val sealed = BackupCodec.encrypt(plaintext, "correct horse battery")
        // GCM authenticates, so this is caught here rather than surfacing as a
        // confusing JSON parse error — or worse, a partial restore.
        assertThrows(BackupCodec.WrongPassphrase::class.java) {
            BackupCodec.decrypt(sealed, "correct horse batteru")
        }
    }

    @Test
    fun `tampering is detected`() {
        val sealed = BackupCodec.encrypt(plaintext, "correct horse battery")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        assertThrows(BackupCodec.WrongPassphrase::class.java) {
            BackupCodec.decrypt(sealed, "correct horse battery")
        }
    }

    @Test
    fun `a foreign file is rejected, not misread`() {
        assertThrows(BackupCodec.WrongPassphrase::class.java) {
            BackupCodec.decrypt("just some text".toByteArray(), "correct horse battery")
        }
        assertThrows(BackupCodec.WrongPassphrase::class.java) {
            BackupCodec.decrypt(ByteArray(0), "correct horse battery")
        }
    }

    /** Fresh salt and IV each time, so two exports never look alike. */
    @Test
    fun `the same data encrypts differently every time`() {
        val a = BackupCodec.encrypt(plaintext, "correct horse battery")
        val b = BackupCodec.encrypt(plaintext, "correct horse battery")
        assertNotEquals(a.toList(), b.toList())
        assertArrayEquals(plaintext, BackupCodec.decrypt(a, "correct horse battery"))
        assertArrayEquals(plaintext, BackupCodec.decrypt(b, "correct horse battery"))
    }

    @Test
    fun `the ciphertext does not leak the plaintext`() {
        val sealed = BackupCodec.encrypt(plaintext, "correct horse battery")
        assertFalse(sealed.decodeToString().contains("epochDay"))
    }

    @Test
    fun `short passphrases are refused`() {
        assertFalse(BackupCodec.isAcceptable("short"))
        assertTrue(BackupCodec.isAcceptable("long enough"))
    }
}
