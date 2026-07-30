package com.bastion.app.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs Bastion's own updates through the PackageInstaller session API.
 *
 * The reason is not tidiness. Handing an APK to the system with ACTION_VIEW
 * makes whatever app opened it the installer of record — a browser, a file
 * manager, sometimes nothing coherent at all. Android 13 treats accessibility
 * and notification access for apps from such sources as "restricted settings"
 * and re-gates them on update, which is why Bastion Guard had to be re-enabled
 * by hand through App info every single time.
 *
 * Installing itself through a session makes Bastion the installer of record, so
 * an update arrives from a source the system already knows and the gate is not
 * raised again.
 *
 * Honest limits: the very first install is still a sideload and will still need
 * "Allow restricted settings" once. Some OEM builds are stricter than stock
 * Android and may re-prompt anyway. This removes the common case, not every
 * case, which is why [openAppInfo] exists for when it does happen.
 */
object SelfInstaller {

    private const val TAG = "SelfInstaller"
    private const val ACTION_RESULT = "com.bastion.app.INSTALL_RESULT"

    /**
     * Streams [apk] into a session and commits it.
     *
     * @return null on success, or a message explaining why it could not start.
     * A returned message means the caller should fall back to [installByIntent].
     */
    fun install(context: Context, apk: File): String? = runCatching {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Replacing ourselves, as ourselves: no second confirmation.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("bastion", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(resultSender(context, sessionId))
        }
        null
    }.getOrElse {
        Log.e(TAG, "Session install failed", it)
        it.message ?: "Could not start the install"
    }

    /**
     * The pre-Android-12 path, and the fallback when a session cannot be opened.
     * Leaves the installer of record to whoever handles the intent, so the
     * restricted-settings gate may return.
     */
    fun installByIntent(context: Context, apk: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    /** Where "Allow restricted settings" lives, for the times it is still needed. */
    fun openAppInfo(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun resultSender(context: Context, sessionId: Int): android.content.IntentSender =
        PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(ACTION_RESULT).setPackage(context.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).intentSender

    /**
     * Receives the session outcome.
     *
     * STATUS_PENDING_USER_ACTION still happens on the first self-install, before
     * Bastion is the installer of record — the system hands back a confirmation
     * intent, which is launched rather than dropped.
     */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let(context::startActivity) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
                else -> Log.w(
                    TAG,
                    "Install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )
            }
        }
    }
}
