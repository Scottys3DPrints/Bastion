package com.bastion.app.core.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The partner-held passcode.
 *
 * A short numeric code guarding a decision a man is actively trying to talk
 * himself into. It is stored beside the thing it protects, on a device he
 * controls, so the only honest assumption is that an attacker — usually the
 * user himself, in a weak moment — can read the stored value and will try to
 * work backwards from it.
 *
 * That rules out a plain digest. A four-digit code has ten thousand
 * possibilities: an unsalted single-round SHA hash falls to a wordlist
 * instantly. PBKDF2 with a per-code random salt and a deliberately high
 * iteration count makes an exhaustive search cost real time, and the salt means
 * one cracked code teaches nothing about another.
 *
 * Format: `iterations:base64(salt):base64(hash)`, so the cost can be raised
 * later without invalidating codes already set.
 */
object PasscodeLock {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    /**
     * Tuned to be slow enough to matter and fast enough that a man entering a
     * code at 1am does not think the app has hung.
     */
    private const val ITERATIONS = 120_000

    fun hash(passcode: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val derived = derive(passcode, salt, ITERATIONS)
        // java.util.Base64 rather than android.util.Base64: available from API 26
        // (this app's minimum), and it keeps this class pure JVM so the security
        // logic can be tested directly instead of behind an emulator.
        val encoder = Base64.getEncoder()
        return listOf(
            ITERATIONS.toString(),
            encoder.encodeToString(salt),
            encoder.encodeToString(derived),
        ).joinToString(":")
    }

    /** False on any malformed or unreadable stored value — never a free pass. */
    fun verify(passcode: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        val parts = stored.split(":")
        if (parts.size != 3) return false

        return runCatching {
            val decoder = Base64.getDecoder()
            val iterations = parts[0].toInt()
            val salt = decoder.decode(parts[1])
            val expected = decoder.decode(parts[2])
            constantTimeEquals(derive(passcode, salt, iterations), expected)
        }.getOrDefault(false)
    }

    private fun derive(passcode: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passcode.toCharArray(), salt, iterations, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    /**
     * Compares every byte regardless of where the first mismatch is, so the time
     * taken cannot be used to discover the code one digit at a time.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
        return difference == 0
    }
}
