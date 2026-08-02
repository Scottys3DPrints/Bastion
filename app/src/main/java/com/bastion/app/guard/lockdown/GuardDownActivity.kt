package com.bastion.app.guard.lockdown

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import kotlinx.coroutines.launch

/**
 * The wall you hit for switching Guard off while locked in.
 *
 * Not a punishment and not a trick. The user asked, in advance and in a calmer
 * hour, to be made to work for this — that is the entire content of "locking
 * in". So the moment the guard he asked for goes down, he lands here, and the
 * only cheap way out is to turn it back on. Leaving it off is still possible;
 * it just costs the partner's code or the cooling-off wait, which are the same
 * two prices every other weakening already carries.
 *
 * What it honestly is not: unbypassable, and the details matter because they
 * were measured rather than assumed.
 *
 *  - **Back is held.** Always.
 *  - **Home is held only under Device Owner**, which lets this pin itself.
 *    Without it, Home leaves — verified on device, not reasoned about — and
 *    what brings the user back is the wall's notification rather than the
 *    screen re-taking itself. Android refuses background activity launches
 *    outright (`Background activity launch blocked!`), and that is a rule
 *    worth having.
 *
 * So the point is to be a dead end for the weak-moment self, not a cage, and
 * the screen says exactly that rather than bluffing.
 */
class GuardDownActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Silent only because Device Owner allow-listed this package; without
        // it the system shows a dismissible "Pin this screen?" prompt, which
        // would be worse than not pinning at all.
        if (DeviceOwner.canPinScreen(this)) runCatching { startLockTask() }

        // Back does nothing. Deliberately silent — a toast saying "you can't do
        // that" invites a fight with the app instead of a decision.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )

        // Wrapped like every other screen: the theme is also where the
        // layout direction is pinned, and a wall that mirrored itself on a
        // Persian phone while the rest of the app did not would be the one
        // screen where looking broken matters most.
        setContent {
            com.bastion.app.core.design.BastionTheme {
                GuardDownScreen(onResolved = { finishAndRelease() })
            }
        }
    }

    /**
     * Home only comes back under screen pinning.
     *
     * Re-launching from here was tried and does not work: by the time this
     * fires the app is on its way to the background, and Android blocks the
     * start. Rather than leave a line of code that looks like enforcement and
     * is not, the honest mechanism is the wall's own notification, posted by
     * the watchdog and cleared when Guard comes back.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
    }

    private var resolved = false

    private fun finishAndRelease() {
        resolved = true
        runCatching { stopLockTask() }
        finish()
    }
}

@Composable
private fun GuardDownScreen(onResolved: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    var askingCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableStateOf(0L) }
    var hasPartnerCode by remember { mutableStateOf(false) }
    var coolingOffHours by remember { mutableStateOf(2) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        hasPartnerCode = graph.social.hasPasscode()
        coolingOffHours = graph.settings.current().coolingOffHours
        waitMillis = graph.passcodeGate.waitMillis()
    }

    // Closes itself the instant Guard is back on, so the honest way out needs
    // no confirmation step at all.
    val running by BastionAccessibilityService.isRunning.collectAsStateWithLifecycle()
    LaunchedEffect(running) { if (running) onResolved() }

    DawnBackground(intensity = 0.15f) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(Space.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SectionLabel("You locked in", color = BastionColors.Amber)
            Spacer(Modifier.height(Space.md))
            Text(
                "Guard is off.",
                style = MaterialTheme.typography.displaySmall,
                color = BastionColors.TextPrimary,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                "You asked to be made to work for this. Turning it back on is one tap. " +
                    "Leaving it off costs the partner's code, or the wait.",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )

            Spacer(Modifier.height(Space.xl))
            PrimaryButton(
                "Turn Guard back on",
                { BastionAccessibilityService.openSettings(context) },
                Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(Space.md))
            if (!askingCode) {
                QuietButton(
                    if (hasPartnerCode) "Leave it off — enter the code"
                    else "Leave it off — wait $coolingOffHours" + "h",
                    {
                        if (hasPartnerCode) askingCode = true
                        else scope.launch {
                            // No partner code set, so the cooling-off delay is
                            // the only price there is. Queued like any other
                            // weakening rather than granted here.
                            graph.guard.requestWeakening("Leave Guard off", "unlock")
                            onResolved()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    BastionColors.TextMuted,
                )
            } else {
                androidx.compose.material3.OutlinedTextField(
                    value = code,
                    onValueChange = { entered -> code = entered.filter(Char::isDigit).take(12); wrong = false },
                    singleLine = true,
                    enabled = waitMillis == 0L,
                    isError = wrong || waitMillis > 0,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    label = { Text("Your partner's code") },
                    supportingText = when {
                        waitMillis > 0 -> {
                            {
                                Text(
                                    "Too many tries. " +
                                        com.bastion.app.core.security.formatWait(waitMillis) + " to go.",
                                    color = BastionColors.Amber,
                                )
                            }
                        }
                        wrong -> {
                            { Text("Not that one.", color = BastionColors.Amber) }
                        }
                        else -> null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.md))
                QuietButton(
                    "Unlock",
                    {
                        scope.launch {
                            when (val result = graph.passcodeGate.attempt(code)) {
                                is com.bastion.app.core.security.PasscodeGate.Result.Unlocked ->
                                    onResolved()
                                is com.bastion.app.core.security.PasscodeGate.Result.Wrong -> {
                                    wrong = true
                                    waitMillis = result.waitMillis
                                }
                                is com.bastion.app.core.security.PasscodeGate.Result.Wait ->
                                    waitMillis = result.millis
                            }
                        }
                    },
                    Modifier.fillMaxWidth(),
                    BastionColors.TextMuted,
                )
            }

            Spacer(Modifier.height(Space.section))
            Text(
                // The limit, stated plainly. Every other promise in this app
                // depends on this one not being oversold.
                "Android won't let any app make its accessibility permission permanent. " +
                    "This is a wall, not a cage — which is why your partner holds the code.",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
        }
    }
}
