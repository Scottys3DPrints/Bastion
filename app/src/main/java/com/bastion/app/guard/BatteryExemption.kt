package com.bastion.app.guard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android is allowed to put Bastion to sleep, and the way to ask it not
 * to.
 *
 * ## Why this is a protection and not a performance setting
 *
 * Android revokes an accessibility service the moment its app is force-stopped.
 * Measured on the phone, not inferred: set the service, force-stop the app, and
 * `enabled_accessibility_services` comes back empty. Nothing announces it. The
 * guard is simply gone.
 *
 * A man does not force-stop his own blocker. His phone does it for him. Samsung
 * puts unused apps to sleep, battery optimisation stops background work, and a
 * task manager or a "clean up memory" button does it on request — and every one
 * of those takes the whole of Bastion's feed blocking with it, silently, at
 * whatever hour the system decided the app looked idle.
 *
 * So this belongs beside the other layers rather than in a settings drawer. It
 * is not about battery life; it is about whether the guard survives the night.
 *
 * ## What it cannot do
 *
 * Nothing here stops a man force-stopping the app by hand, and nothing should.
 * The exemption only removes the reason the *system* would do it unasked, which
 * is the failure he never agreed to and cannot see coming.
 */
object BatteryExemption {

    /** True when Android has agreed to leave Bastion running. */
    fun isExempt(context: Context): Boolean = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        power?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    /**
     * Opens the system's own request.
     *
     * Falls back to the full battery-optimisation list if the direct dialog is
     * refused or missing, which some OEM builds do. A button that silently does
     * nothing is worse than one that opens the long way round.
     */
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(direct) }.isSuccess
        if (opened) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
