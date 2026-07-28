package com.bastion.app.core.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * At-rest encryption for the covenant signature.
 *
 * App-private storage stops other apps reading this, but it does not stop
 * anything with the filesystem: a rooted phone, a recovery image, a forensic
 * dump. The signature is a man's own handwriting attached to the most private
 * admission in his life, so private-by-permission is not enough on its own.
 *
 * The key lives in the Android Keystore, hardware-backed where the device
 * supports it, and never leaves it. Losing the key means losing the file — which
 * is the correct trade here, because this is a keepsake rather than the data the
 * app runs on. Nothing is ever transmitted anywhere regardless.
 */
class CovenantVault(private val context: Context) {

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encrypted(file: File) = EncryptedFile.Builder(
        context,
        file,
        masterKey,
        EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
    ).build()

    /**
     * Writes [bytes] encrypted, returning the path, or null if the platform
     * refuses. A failure here must never take onboarding down with it — the
     * covenant text and the date matter more than the picture of the signature.
     */
    suspend fun write(name: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.filesDir, name)
            // EncryptedFile refuses to open an output stream over an existing
            // file, so a re-signed covenant has to clear the old one first.
            if (file.exists()) file.delete()

            encrypted(file).openFileOutput().use { it.write(bytes) }
            file.absolutePath
        }.getOrElse {
            Log.e(TAG, "Could not write $name encrypted", it)
            null
        }
    }

    suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            encrypted(file).openFileInput().use { it.readBytes() }
        }.getOrElse {
            Log.e(TAG, "Could not read $path", it)
            null
        }
    }

    private companion object { const val TAG = "CovenantVault" }
}
