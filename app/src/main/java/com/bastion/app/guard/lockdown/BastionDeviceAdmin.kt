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

        /**
         * Opens the system's consent screen. There is no way to self-grant this.
         *
         * Callers must not reach for the intent and add flags to it, which is
         * why this exists and [activationIntent] is now private: adding
         * FLAG_ACTIVITY_NEW_TASK makes the screen refuse to appear at all.
         * Android's own DeviceAdminAdd logs "Cannot start ADD_DEVICE_ADMIN as a
         * new task" and finishes immediately, so the button looked dead — it
         * launched something that killed itself before drawing a frame.
         *
         * @return false if there is no activity to show it, so the caller can
         * say so instead of appearing to do nothing.
         */
        fun requestActivation(context: Context): Boolean = runCatching {
            context.startActivity(activationIntent(context))
            true
        }.getOrDefault(false)

        private fun activationIntent(context: Context): Intent =
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
