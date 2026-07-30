package com.bastion.app.guard.lockdown

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Device admin, used for exactly one thing: locking the screen.
 *
 * Bastion asks for the narrowest possible policy — `force-lock` and nothing
 * else. It cannot wipe the device, read anything, change a password or watch
 * what happens on screen, and the system's own activation dialog says so.
 *
 * Worth being clear about what Android does not allow, because the obvious wish
 * is to have the phone power itself off: shutting down or rebooting requires a
 * signature-level permission held by the OS. No installable app can do it, and
 * any app claiming to is lying or rooted. Locking the screen is the closest
 * honest equivalent — it puts the phone down for you.
 */
class BastionDeviceAdmin : DeviceAdminReceiver() {

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Nothing to tear down: the only capability was locking the screen.
    }

    companion object {

        fun component(context: Context) = ComponentName(context, BastionDeviceAdmin::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)
                ?.isAdminActive(component(context)) == true

        /** The system's own consent screen; there is no way to self-grant this. */
        fun activationIntent(context: Context): Intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Lets Bastion lock your screen when you trigger a lockdown. " +
                        "It is used for nothing else.",
                )

        fun lockNow(context: Context): Boolean = runCatching {
            if (!isActive(context)) return false
            context.getSystemService(DevicePolicyManager::class.java)?.lockNow()
            true
        }.getOrDefault(false)
    }
}
