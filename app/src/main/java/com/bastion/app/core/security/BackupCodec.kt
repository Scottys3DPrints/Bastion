package com.bastion.app.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts a backup with a passphrase the user chooses.
 *
 * Deliberately NOT the Android Keystore, unlike [CovenantVault]. A Keystore key
 * never leaves the device — which is exactly right for a file that stays put,
 * and exactly wrong for a backup, whose whole purpose is to be restorable on a
 * phone that does not exist yet. The passphrase is the only thing that can
 * travel, so the passphrase is the key.
 *
 * The consequence is stated plainly in the UI: forget it and the backup is
 * gone. There is no recovery path, because a recovery path would be a second
 * way in for anyone who finds the file.
 *
 * Layout: magic | version | salt(16) | iv(12) | ciphertext+tag.
 */
object BackupCodec {

    private const val MAGIC = "BASTION1"
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    class WrongPassphrase : Exception("Wrong passphrase, or the file is not a Bastion backup")

    fun encrypt(plaintext: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        }
        val body = cipher.doFinal(plaintext)

        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + body
    }

    /**
     * @throws WrongPassphrase on a bad passphrase, a truncated file, or anything
     * that is not a Bastion backup. GCM authenticates, so a wrong key fails
     * loudly here rather than yielding plausible rubbish downstream.
     */
    fun decrypt(payload: ByteArray, passphrase: String): ByteArray {
        val magic = MAGIC.toByteArray(Charsets.US_ASCII)
        val header = magic.size + SALT_BYTES + IV_BYTES
        if (payload.size <= header) throw WrongPassphrase()
        if (!payload.copyOfRange(0, magic.size).contentEquals(magic)) throw WrongPassphrase()

        val salt = payload.copyOfRange(magic.size, magic.size + SALT_BYTES)
        val iv = payload.copyOfRange(magic.size + SALT_BYTES, header)
        val body = payload.copyOfRange(header, payload.size)

        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            }.doFinal(body)
        } catch (e: Exception) {
            throw WrongPassphrase()
        }
    }

    /** Long enough to be worth the iteration count. */
    fun isAcceptable(passphrase: String): Boolean = passphrase.length >= 8

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded
        return SecretKeySpec(derived, "AES")
    }
}
