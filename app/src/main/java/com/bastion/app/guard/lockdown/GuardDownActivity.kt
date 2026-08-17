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
        val layer = intent?.getStringExtra(EXTRA_LAYER)
            ?: com.bastion.app.guard.GuardWatchdog.LAYER_GUARD

        setContent {
            com.bastion.app.core.design.BastionTheme {
                GuardDownScreen(layer = layer, onResolved = { finishAndRelease() })
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

    companion object {
        /** Which protection went down; see GuardWatchdog.LAYER_*. */
        const val EXTRA_LAYER = "com.bastion.app.LAYER"
    }

    private fun finishAndRelease() {
        resolved = true
        runCatching { stopLockTask() }
        finish()
    }
}

@Composable
private fun GuardDownScreen(layer: String, onResolved: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    var askingCode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableStateOf(0L) }
    var hasPartnerCode by remember { mutableStateOf(false) }
    var coolingOffMinutes by remember { mutableStateOf(120) }
    var hostname by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }

    var lastSeenVersion by remember { mutableStateOf(0) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        hasPartnerCode = graph.social.hasPasscode()
        coolingOffMinutes = graph.settings.current().coolingOffMinutes
        hostname = graph.settings.current().dnsHostname
        waitMillis = graph.passcodeGate.waitMillis()
        lastSeenVersion = graph.settings.current().lastSeenVersionCode
    }

    val isDns = layer == com.bastion.app.guard.GuardWatchdog.LAYER_DNS

    // An update ran since this app last opened, and the guard is down. The two
    // together are the signature of Android revoking the service on install
    // rather than of a man switching it off.
    val droppedByUpdate = !isDns &&
        lastSeenVersion != 0 &&
        lastSeenVersion != com.bastion.app.BuildConfig.VERSION_CODE

    // Closes itself the instant the layer is back, so the honest way out needs
    // no confirmation step at all.
    //
    // Guard publishes a flow; Private DNS is a system setting with nothing to
    // observe, so that one is polled. A second of latency after fixing it is
    // nothing next to a wall that will not admit it has been satisfied.
    val running by BastionAccessibilityService.isRunning.collectAsStateWithLifecycle()
    LaunchedEffect(running, isDns) { if (!isDns && running) onResolved() }
    LaunchedEffect(isDns) {
        while (isDns) {
            if (com.bastion.app.guard.vpn.DnsFilters.privateDnsIsSet(context)) {
                onResolved()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(1_000)
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
            SectionLabel("You locked in", color = BastionColors.Amber)
            Spacer(Modifier.height(Space.md))
            Text(
                if (isDns) "Private DNS is off." else "Guard is off.",
                style = MaterialTheme.typography.displaySmall,
                color = BastionColors.TextPrimary,
            )
            Spacer(Modifier.height(Space.md))
            Text(
                when {
                    isDns ->
                        "You asked to be made to work for this. Set it back to " +
                            (hostname.ifBlank { "your provider's hostname" }) +
                            ". Leaving it off costs the partner's code, or the wait."
                    // Blaming the right party.
                    //
                    // Android revokes an accessibility service when its APK is
                    // replaced, and Bastion updates itself in place — so every
                    // update drops the guard, silently, and this screen met a
                    // man with "you asked to be made to work for this" about a
                    // thing he had not done. Being accused of quitting by the
                    // app you just updated to keep going is the kind of wrong
                    // that makes someone stop trusting the rest of it.
                    droppedByUpdate ->
                        "The update to this version switched it off — Android does " +
                            "that to every accessibility service when an app is " +
                            "replaced, and there is nothing Bastion can do to stop " +
                            "it. One tap puts it back."
                    else ->
                        "You asked to be made to work for this. Turning it back on is one tap. " +
                            "Leaving it off costs the partner's code, or the wait."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )

            Spacer(Modifier.height(Space.xl))
            PrimaryButton(
                if (isDns) "Open Private DNS settings" else "Turn Guard back on",
                {
                    if (isDns) openPrivateDns(context)
                    else BastionAccessibilityService.openSettings(context)
                },
                Modifier.fillMaxWidth(),
            )
            if (isDns && hostname.isNotBlank()) {
                Spacer(Modifier.height(Space.sm))
                // The hostname is the part nobody remembers under pressure, and
                // a wall that demands something you cannot recall is just a
                // locked door.
                com.bastion.app.core.design.LinkButton(
                    if (copied) "Copied" else "Copy $hostname",
                    BastionColors.SageBright,
                ) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(hostname))
                    copied = true
                }
            }

            Spacer(Modifier.height(Space.md))
            if (!askingCode) {
                QuietButton(
                    if (hasPartnerCode) "Leave it off — enter the code"
                    else "Leave it off — wait ${com.bastion.app.data.repo.GuardRepository.Delay.describe(coolingOffMinutes)}",
                    {
                        if (hasPartnerCode) askingCode = true
                        else scope.launch {
                            // No partner code set, so the cooling-off delay is
                            // the only price there is. Queued like any other
                            // weakening rather than granted here.
                            graph.guard.requestWeakening(
                                if (isDns) "Leave Private DNS off" else "Leave Guard off",
                                "unlock",
                            )
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
                if (isDns) {
                    "Private DNS is a system setting — Bastion can't switch it on or hold " +
                        "it down, only notice. This is a wall, not a cage, which is why " +
                        "your partner holds the code."
                } else {
                    "Android won't let any app make its accessibility permission permanent. " +
                        "This is a wall, not a cage — which is why your partner holds the code."
                },
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
        }
    }
}

/**
 * Deep-links to Private DNS where the OEM exposes it, and to the network
 * screen where it does not.
 *
 * The direct action is not present on every build, so the fallbacks matter: a
 * wall whose only button does nothing is the worst version of this screen.
 */
private fun openPrivateDns(context: android.content.Context) {
    val candidates = listOf(
        android.content.Intent("android.settings.PRIVATE_DNS_SETTINGS"),
        android.content.Intent("android.settings.WIRELESS_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (opened) return
    }
}

