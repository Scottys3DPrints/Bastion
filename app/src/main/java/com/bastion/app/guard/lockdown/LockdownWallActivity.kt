package com.bastion.app.guard.lockdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionTheme
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import kotlinx.coroutines.delay

/**
 * The phone, taken away, for exactly as long as he asked for.
 *
 * "Lock the screen" used to mean `lockNow()` — the display goes dark and he
 * unlocks it a second later with the PIN he already knows. That is a gesture,
 * not a plan, and it is the one thing the break-glass button most needed to
 * actually do. This is the real version: the device is pinned to a screen with
 * a countdown on it and nothing else, and it stays there until the clock runs
 * out.
 *
 * **How much of that is true depends on Device Owner**, and the difference is
 * not cosmetic:
 *
 *  - **With Device Owner** the app pins itself silently. Home, Recents and the
 *    notification shade are all refused by the system. The phone is genuinely
 *    unusable — this is the same mechanism a kiosk or a company-owned handset
 *    uses, enforced below the app layer where nothing installed can reach it.
 *  - **Without it** Android shows its own dismissible "Pin this screen?" prompt
 *    and the pin can be undone by holding Back and Recents together. The wall
 *    still appears and the clock still runs; it is a closed door rather than a
 *    locked one. The screen says which of the two he is looking at rather than
 *    bluffing, because a bluff discovered at 1am costs every other promise the
 *    app has made.
 *
 * **What stays reachable, on purpose.** Android's own lock screen, which is
 * where the Emergency button lives on every phone ever made. Press power, wake
 * it, and the call can be placed; unlocking comes straight back here. A man must
 * always be able to call for help from his own phone, and no rule he sets for
 * himself outranks that. Everything else — Home, Recents, notifications, the app
 * switcher — is closed.
 */
class LockdownWallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Deliberately NOT showWhenLocked.
        //
        // It was set at first, and it quietly cancelled the emergency path: the
        // wall drew itself over the keyguard, so pressing power and waking the
        // phone came straight back here with no lock screen and therefore no
        // Emergency button. Verified on device — the wall survived the power
        // cycle, which looked like a win and was actually the safety valve
        // being welded shut. Letting the keyguard have the screen costs
        // nothing: unlocking lands back on the wall, because the pin is still
        // in force.

        DeviceOwner.allowPinning(this)
        runCatching { startLockTask() }

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        setContent {
            BastionTheme {
                LockdownWallScreen(
                    pinned = DeviceOwner.canPinScreen(this),
                    onDone = { release() },
                )
            }
        }
    }

    /**
     * Re-asserts the pin rather than letting it lapse.
     *
     * Without Device Owner the system prompt can be dismissed, and on some
     * builds the task drops out of lock mode when the screen cycles. Asking for
     * it again each time this screen comes back is cheap and closes that.
     */
    override fun onResume() {
        super.onResume()
        runCatching { startLockTask() }
    }

    private fun release() {
        runCatching { stopLockTask() }
        finish()
    }

    companion object {
        /**
         * Raises the wall. Safe to call when one is already up — `singleTask`
         * means the running instance is reused rather than stacked.
         */
        fun raise(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, LockdownWallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}

/**
 * A clock, and nothing to do but wait.
 *
 * Deliberately bare. There is no button, because there is nothing here he is
 * allowed to decide — that was the point of pressing it. What is on screen is
 * the time left and one line reminding him whose idea this was, because the man
 * reading it at hour two has forgotten and needs telling kindly rather than
 * being left to feel trapped by something anonymous.
 */
@Composable
private fun LockdownWallScreen(pinned: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    var settings by remember { mutableStateOf<Settings?>(null) }
    var remaining by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        // Polled rather than observed: the whole screen is a countdown, so it
        // has to tick anyway, and one loop is easier to reason about than a
        // flow plus a timer racing each other to decide when this closes.
        while (true) {
            val current = graph.settings.current()
            settings = current
            remaining = Lockdown.remainingSeconds(current)
            if (remaining <= 0L) {
                onDone()
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }

    DawnBackground(intensity = 0.2f) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(Space.section),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SectionLabel("The plan you made", color = BastionColors.BronzeBright)
                Spacer(Modifier.height(Space.section))

                Text(
                    clock(remaining),
                    style = MaterialTheme.typography.displayLarge,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(Space.md))
                Text(
                    "left",
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.TextMuted,
                )

                Spacer(Modifier.height(Space.xl))
                Text(
                    // His decision, in his own calmer hour. Naming that is the
                    // difference between a wall and a punishment.
                    "You asked for this in a better hour. It ends on its own.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(Space.section))
                Text(
                    if (pinned) {
                        "Hold the power button for an emergency call."
                    } else {
                        // Never claim the stronger version. Someone who finds out
                        // by escaping learns the app overstates itself, and then
                        // nothing else it says lands either.
                        "Hold the power button for an emergency call.\n\n" +
                            "This phone isn't fully locked — set Bastion as device owner " +
                            "and this screen can't be left at all."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The way out that has to work on every phone.
 *
 * Not the power menu: on the test device a long press opened the Assistant, and
 * which of the two happens is up to the OEM and a setting. Android's own lock
 * screen always carries an Emergency button, and the keyguard is deliberately
 * left enabled during the lockdown so it is reachable.
 */
private const val EMERGENCY_LINE =
    "Need help? Press the power button, then Emergency on the lock screen."

/** "1:59:04" over an hour, "04:31" under it. */
private fun clock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
