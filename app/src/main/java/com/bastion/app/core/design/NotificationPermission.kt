package com.bastion.app.core.design

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Asking for permission to post notifications.
 *
 * From Android 13 this is a runtime permission, and Bastion declared it without
 * ever requesting it — so every notification the app tried to post was dropped
 * on the floor in silence. That included the Daily Brief, which is one of the
 * four pillars, and the warning that Guard has been switched off. A feature that
 * fails invisibly is worse than one that is missing, because nobody goes looking.
 */
class NotificationPermissionState internal constructor(
    private val context: Context,
    private val onRequest: () -> Unit,
    granted: Boolean,
) {
    var granted: Boolean = granted
        internal set

    /** True on Android 12 and below, where the permission does not exist. */
    val needed: Boolean
        get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

    fun requestIfNeeded() {
        if (!needed || granted) return
        onRequest()
    }

    internal fun refresh() {
        granted = isGranted(context)
    }
}

@Composable
fun rememberNotificationPermission(): NotificationPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(isGranted(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    return remember(granted) {
        NotificationPermissionState(
            context = context,
            onRequest = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            granted = granted,
        )
    }
}

private fun isGranted(context: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
