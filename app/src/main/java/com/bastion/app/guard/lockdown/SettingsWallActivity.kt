package com.bastion.app.guard.lockdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
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
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionTheme
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.accessibility.GuardedScreens
import kotlinx.coroutines.delay

/**
 * The wall for standing on a screen you locked yourself out of.
 *
 * Every other wall in the app arrives after the fact — Guard is already off,
 * Private DNS has already been changed — and spends its first seconds catching
 * up with a decision that has already been made. This one arrives first. While
 * the settings lock is on, opening the accessibility screen or the Private DNS
 * screen puts this in front of it before anything can be flipped.
 *
 * **It is a door, not a cage.** Back and Leave both work and both take you out
 * to the home screen. That is deliberate and it is the difference between an
 * interruption and a trap: the man is not being held prisoner in Settings, he is
 * being refused *that screen* while the lock he asked for is on. Come back to it
 * and the wall comes back too, every time, until the lock is lifted — which is
 * exactly the cost he chose when he set the cooling-off hours.
 *
 * What it deliberately does not do is offer a way through. There is no "let me
 * in anyway" button, because the wait *is* the mechanism; the way out is the
 * lock expiring or the partner's code, both of which live on the Guard screen
 * and neither of which this wall tries to reinvent.
 */
class SettingsWallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Allow-listed but not pinned. Pinning here would be the cage: the whole
        // point is that he can walk away from the screen, and a lock task he
        // cannot leave would strand him inside Settings with nowhere to go.
        DeviceOwner.allowPinning(this)

        val guarded = runCatching {
            GuardedScreens.Guarded.valueOf(intent?.getStringExtra(EXTRA_SCREEN).orEmpty())
        }.getOrDefault(GuardedScreens.Guarded.ACCESSIBILITY)

        // Back leaves too, rather than being held as the other walls hold it.
        // Held, it would drop the user back onto the guarded screen underneath,
        // the service would raise this again, and a rule would look like a crash
        // loop. Sending both exits to the same place is what makes this a door.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = leave()
            },
        )

        setContent {
            BastionTheme {
                SettingsWallScreen(guarded = guarded, onLeave = { leave() })
            }
        }
    }

    /**
     * Out to the home screen, not back into Settings.
     *
     * `finish()` alone returns to whatever was underneath, which is the very
     * screen this exists to refuse — the wall would close and immediately be
     * raised again by the service, which reads as a crash loop rather than as a
     * rule.
     */
    private fun leave() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_SCREEN = "com.bastion.app.GUARDED_SCREEN"

        /**
         * Raises the wall. `singleTask`, so arriving twice reuses the instance
         * rather than stacking walls behind each other.
         */
        fun raise(context: Context, guarded: GuardedScreens.Guarded) {
            runCatching {
                context.startActivity(
                    Intent(context, SettingsWallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_SCREEN, guarded.name)
                )
            }
        }
    }
}

@Composable
private fun SettingsWallScreen(
    guarded: GuardedScreens.Guarded,
    onLeave: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val isDns = guarded == GuardedScreens.Guarded.PRIVATE_DNS
    val isUninstall = guarded == GuardedScreens.Guarded.UNINSTALL

    var remaining by remember { mutableLongStateOf(-1L) }
    var stillLocked by remember { mutableStateOf(true) }

    // Polled rather than observed. The lock lifts when a matured change request
    // is applied by the watchdog, which is a background write this screen has no
    // flow onto — and a wall that will not admit it has been satisfied is worse
    // than one second of latency.
    LaunchedEffect(Unit) {
        while (true) {
            val settings = graph.settings.current()
            // Either reason is enough to hold this screen. Reading only the
            // settings lock would close the wall the instant it was raised
            // during a lockdown, since a lockdown does not require that lock to
            // be on — and the lockdown hour is exactly when uninstalling gets
            // attempted.
            val lockdown = Lockdown.isActive(settings)
            stillLocked = settings.tamperLockEnabled || lockdown
            if (!stillLocked) {
                onLeave()
                return@LaunchedEffect
            }
            // Whichever wait is actually holding him. A lockdown has its own
            // clock and it is the honest number to show while one is running;
            // otherwise it is the pending unlock, and -1 means he has not asked
            // for one yet, which is a different sentence again.
            remaining = if (lockdown) Lockdown.remainingMillis(settings)
            else graph.guard.pendingUnlockRemainingMillis()
            delay(1_000)
        }
    }

    DawnBackground(intensity = 0.15f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Space.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel(
                if (isUninstall) "Not this way" else "You locked these",
                color = BastionColors.Amber,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                when {
                    isUninstall -> "Uninstalling is locked."
                    isDns -> "Private DNS is locked."
                    else -> "Accessibility is locked."
                },
                style = MaterialTheme.typography.displaySmall,
                color = BastionColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                when {
                    // Named plainly, because the cost is the point. Removing the
                    // app does not just lift the guards — it takes the covenant,
                    // the counted days and the whole record with it, and a man
                    // reaching for it at 1am has usually not thought that far.
                    isUninstall ->
                        "Removing Bastion would take your covenant, your streak and " +
                            "every log with it. That is exactly why you locked this."
                    isDns ->
                        "This is the screen where the resolver gets changed, so it is " +
                            "shut while you are locked in."
                    else ->
                        "This is the screen where Guard gets switched off, so it is " +
                            "shut while you are locked in."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Space.lg))
            Text(
                when {
                    remaining > 0L ->
                        "The wait you asked for has ${describe(remaining)} left. " +
                            "This screen opens on its own when it runs out."
                    remaining == 0L ->
                        "Your wait has run out. Open Bastion and the lock will lift."
                    else ->
                        "Nothing is counting down yet. Ask to unlock in Bastion, " +
                            "serve the wait you set yourself, and this opens."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (remaining > 0L) BastionColors.BronzeBright else BastionColors.TextTertiary,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(Space.xl))
            // The only button, and it goes away rather than through. Offering a
            // way past would make the wait optional, and a wait a man can skip
            // at the moment he wants to skip it is not a wait.
            PrimaryButton("Leave it alone", onLeave, Modifier.fillMaxWidth())
        }
    }
}

/** "2 hours", "35 minutes", "40 seconds" — the wait, in words. */
private fun describe(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds >= 3600 -> {
            val h = seconds / 3600
            if (h == 1L) "1 hour" else "$h hours"
        }
        seconds >= 60 -> {
            val m = seconds / 60
            if (m == 1L) "1 minute" else "$m minutes"
        }
        else -> "$seconds seconds"
    }
}
