package com.bastion.app.feature.guardui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.BastionBottomSheet
import com.bastion.app.core.design.BastionRow
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.Section
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import com.bastion.app.guard.browser.FilteredBrowserActivity
import com.bastion.app.guard.lockdown.BastionDeviceAdmin
import com.bastion.app.guard.vpn.BastionVpnService
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GuardScreen(onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()

    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val guardedApps by graph.guard.guardedApps.collectAsStateWithLifecycle(initialValue = emptyList())
    val feedRules by graph.guard.feedRules.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingChanges by graph.guard.pendingChanges.collectAsStateWithLifecycle(initialValue = emptyList())
    val serviceRunning by BastionAccessibilityService.isRunning.collectAsStateWithLifecycle()
    val filterRunning by BastionVpnService.isRunning.collectAsStateWithLifecycle()
    val blockedCount by BastionVpnService.blockedCount.collectAsStateWithLifecycle()

    var showAppPicker by remember { mutableStateOf(false) }
    var showLearnMode by remember { mutableStateOf(false) }

    // Every weakening is confirmed before it is enqueued. The cooling-off lock
    // makes a mis-tap expensive to undo, so the tap has to be deliberate.
    var confirmUnguard by remember { mutableStateOf<GuardedAppEntity?>(null) }
    var confirmFilterOff by remember { mutableStateOf(false) }
    var confirmRelax by remember { mutableStateOf<Pair<GuardedAppEntity, BlockMode>?>(null) }
    var confirmUnlock by remember { mutableStateOf(false) }
    var blockedByLockdown by remember { mutableStateOf(false) }

    // When the partner lock is armed, weakening anything needs the code the
    // partner holds. `pendingWeakening` parks the confirmed action until it is
    // entered — this is the difference between the lock existing in the schema
    // and the lock actually being a wall.
    var pendingWeakening by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var lockArmed by remember { mutableStateOf(false) }
    LaunchedEffect(settings.partnerLockEnabled) {
        lockArmed = settings.partnerLockEnabled && graph.social.hasPasscode()
    }

    /** Runs [action] now, or holds it behind the partner's code if the lock is armed. */
    fun weaken(action: suspend () -> Unit) {
        if (lockArmed) pendingWeakening = action else scope.launch { action() }
    }

    /**
     * Queues a weakening behind the cooling-off delay, but ONLY once the user has
     * locked in.
     *
     * Before that, every change is instant. Setting the guards up means adding
     * the wrong app, changing your mind about a mode, removing something you
     * added by mistake — and a delay on all of that made the app feel broken
     * rather than firm. The lock is a decision you make when the setup is right,
     * not a tax on arriving at it.
     */
    fun weakenOrQueue(description: String, payload: String, immediate: suspend () -> Unit) {
        // A running lockdown refuses every weakening outright, whether or not the
        // cooling-off lock is on. The two are deliberately independent, and
        // without this the danger button would be undone in seconds by simply
        // deleting the guarded apps — which is exactly what a man in that state
        // would think to try.
        if (com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
            blockedByLockdown = true
            return
        }
        if (!settings.tamperLockEnabled) {
            weaken(immediate)
        } else {
            weaken { graph.guard.requestWeakening(description, payload) }
        }
    }

    // A cooling-off timer that visibly never moves reads as broken, and this one
    // is the app's proof that the lock is real. Computed once at composition, it
    // sat frozen until some unrelated state happened to recompose.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pendingChanges.isNotEmpty()) {
        while (pendingChanges.isNotEmpty()) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    LaunchedEffect(Unit) { graph.guard.seedIfEmpty(); graph.guard.syncBuiltInRules() }

    // Reads the breach; recording the intent happens app-wide in MainActivity,
    // because it must not depend on this screen being the one in front.
    // Survives rotation but not a return to the tab: coming back to Guard
    // should show the status, which is what it is for.
    var managing by remember { mutableStateOf(false) }
    var guardBreached by remember { mutableStateOf(false) }
    var breachedFor by remember { mutableStateOf("") }
    val ownerClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var ownerCommandCopied by remember { mutableStateOf(false) }
    LaunchedEffect(ownerCommandCopied) {
        if (ownerCommandCopied) {
            kotlinx.coroutines.delay(1_800)
            ownerCommandCopied = false
        }
    }
    var confirmStandDown by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleResumeEffect(serviceRunning) {
        scope.launch {
            guardBreached = com.bastion.app.guard.GuardWatchdog.isBreached(context)
            breachedFor = com.bastion.app.guard.GuardWatchdog.describeDuration(
                com.bastion.app.guard.GuardWatchdog.breachDurationMillis(context)
            )
        }
        onPauseOrDispose { }
    }

    val vpnConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            BastionVpnService.start(context)
            scope.launch { graph.settings.setVpnEnabled(true) }
        }
    }

    BastionScaffold(
        // The title says which of the two screens this is, and Back leaves
        // configuration the same way it leaves any pushed route.
        title = if (managing) "Protection" else "Guard",
        dawnIntensity = 0.4f,
        onBack = if (managing) ({ managing = false }) else null,
        action = {
            IconButton(onClick = onOpenProfile) {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "You, partner and settings",
                    tint = BastionColors.TextMuted,
                )
            }
        },
    ) {

        // Status is the default screen's whole job, so it steps aside
        // while configuration is open. Two screens, one purpose each.
        if (!managing) {
            if (guardBreached) {
                BastionCard(accent = BastionColors.Amber) {
                    Text(
                        "You asked for Guard to be on",
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        // How long matters more than the fact: "since just now"
                        // is a slip, "for 3 days" is a decision.
                        "It's off. Guarded feeds have been open $breachedFor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(Space.lg))
                    PrimaryButton(
                        "Turn it back on",
                        { BastionAccessibilityService.openSettings(context) },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.sm))
                    // Written, never sent — the same rule as the rest of Brotherhood.
                    QuietButton(
                        "Tell my partner",
                        {
                            scope.launch {
                                com.bastion.app.guard.GuardWatchdog.partnerAlertIntent(context)
                                    ?.let { context.startActivity(it) }
                            }
                        },
                        Modifier.fillMaxWidth(),
                        BastionColors.SageBright,
                    )
                    Spacer(Modifier.height(Space.xs))
                    // The honest exit. Without it the only way to stop the
                    // six-hourly nag was to clear the app's data, which takes
                    // the whole journey with it.
                    LinkButton("I'm done with Guard") { confirmStandDown = true }
                }
            }

                // Both DNS filters on at once means no working name resolution
            // at all, and Android says so with "Private DNS server cannot be
            // accessed". Loud and above the strength card, because until it
            // is resolved nothing else on this screen matters.
            if (com.bastion.app.guard.vpn.DnsFilters.bothRunning(context, settings.vpnFilterEnabled)) {
                BastionCard(accent = BastionColors.Amber) {
                    Text(
                        "Two filters, no internet",
                        style = MaterialTheme.typography.titleMedium,
                        color = BastionColors.TextPrimary,
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "Private DNS is set to " +
                            (com.bastion.app.guard.vpn.DnsFilters.privateDnsHostname(context)
                                ?: "a hostname") +
                            ", and Bastion's content filter is on. They both take over DNS, " +
                            "so neither works and the phone loses internet. Keep one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(Space.md))
                    // Dropping Bastion's filter is only a lateral move if the
                    // resolver actually filters. Otherwise it is a weakening
                    // wearing a helpful label, and it goes through the same gate
                    // as every other one.
                    val dnsActuallyFilters =
                        com.bastion.app.guard.vpn.DnsFilters.privateDnsFilters(context)

                    if (!dnsActuallyFilters) {
                        Text(
                            "That resolver isn't one Bastion knows to block adult sites, " +
                                "so turning its own filter off would leave you with neither. " +
                                "Point Private DNS at family.cloudflare-dns.com instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.Amber,
                        )
                        Spacer(Modifier.height(Space.md))
                    }
                    PrimaryButton(
                        "Turn Bastion's filter off",
                        {
                            // Private DNS is the one worth keeping: it filters
                            // below the app layer and survives Bastion being
                            // killed. Bastion cannot switch it on for you, but
                            // it can get out of its way.
                            if (dnsActuallyFilters) {
                                scope.launch {
                                    graph.settings.setVpnEnabled(false)
                                    BastionVpnService.stop(context)
                                }
                            } else {
                                weakenOrQueue("Turn off the content filter", "vpn:off") {
                                    graph.settings.setVpnEnabled(false)
                                    BastionVpnService.stop(context)
                                }
                            }
                        },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "Or set Private DNS back to Automatic and keep Bastion's.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextMuted,
                    )
                }
            }

        // --- Guard strength: what is actually armed -------------------
            //
            // Pinned above everything else because it is the question the
            // screen exists to answer. Each unarmed row is its own one-tap
            // route to the system screen that arms it, which is what turns a
            // status display into the setup checklist as well.
            val layers = rememberGuardLayers(settings)
            GuardStrengthCard(
                layers = layers,
                onArm = { layer ->
                    when (layer) {
                        GuardLayer.FEED_GUARD -> BastionAccessibilityService.openSettings(context)
                        GuardLayer.CONTENT_FILTER -> {
                            val consent = BastionVpnService.prepareIntent(context)
                            if (consent != null) {
                                vpnConsent.launch(consent)
                            } else {
                                BastionVpnService.start(context)
                                scope.launch { graph.settings.setVpnEnabled(true) }
                            }
                        }
                        GuardLayer.PRIVATE_DNS -> openPrivateDnsSettings(context)
                        GuardLayer.SCREEN_LOCK -> BastionDeviceAdmin.requestActivation(context)
                        // One tap, and that is the whole feature. This used to
                        // offer an adb command as the "upgrade" once the switch
                        // was already on — for a stronger mode that was never
                        // built.
                        GuardLayer.GRAYSCALE ->
                            scope.launch { graph.settings.setGrayscale(true) }
                        GuardLayer.NOTIFICATIONS -> openNotificationSettings(context)
                    }
                },
            )


            // --- Turning Guard on, shown only while it is off ---
            //
            // The card used to sit here whatever the state, restating what the
            // strength card above had just said and in a different visual
            // language: a compact checklist row, then a full card, for the same
            // two facts. That is most of what made this screen read as bolted
            // together. The strength card owns status now; this is setup help,
            // and setup help has no business on a screen where setup is done.
            if (!serviceRunning) {
                BastionCard(accent = BastionColors.Amber) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(false)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Bastion Guard is off",
                            style = MaterialTheme.typography.titleMedium,
                            color = BastionColors.TextPrimary,
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "Reels, Shorts and For You are not being blocked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextTertiary,
                    )
                    Spacer(Modifier.height(Space.md))
                    Text(
                        // Android's own wording for this permission sounds far
                        // more alarming than what is actually being granted, and
                        // people back out of the screen because of it. Saying so
                        // first is the difference between a granted permission
                        // and an abandoned setup.
                        "Android calls this \"Accessibility\", and its warning sounds " +
                            "scarier than this is. It lets Bastion see which screen " +
                            "you're on — that you opened Reels — so it can close it. " +
                            "It never reads your messages or anything on the screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "You're looking for \"Bastion Guard\" in the list, then the " +
                            "switch at the top.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.SageBright,
                    )
                    Spacer(Modifier.height(Space.lg))
                    PrimaryButton(
                        "Turn on in Settings",
                        { BastionAccessibilityService.openSettings(context) },
                        Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.sm))
                    // Android 13 hides accessibility behind "restricted settings"
                    // for sideloaded apps, and the option to lift it is buried in
                    // App info under the overflow menu. Updates install through a
                    // PackageInstaller session now, which should stop it coming
                    // back — but when a build re-gates anyway, this is the door.
                    QuietButton(
                        "Greyed out? Open App info",
                        { com.bastion.app.core.update.SelfInstaller.openAppInfo(context) },
                        Modifier.fillMaxWidth(),
                    )
                }
            }


            // --- Content filter ---
            BastionCard(accent = if (filterRunning) BastionColors.Sage else null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Block adult websites",
                            style = MaterialTheme.typography.titleMedium,
                            color = BastionColors.TextPrimary,
                        )
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            if (filterRunning) "On · $blockedCount lookups blocked"
                            else "Blocks adult domains across every app and browser",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.TextTertiary,
                        )
                    }
                    Switch(
                        checked = filterRunning,
                        onCheckedChange = { wanted ->
                            if (wanted) {
                                val consent = BastionVpnService.prepareIntent(context)
                                if (consent != null) vpnConsent.launch(consent)
                                else {
                                    BastionVpnService.start(context)
                                    scope.launch { graph.settings.setVpnEnabled(true) }
                                }
                            } else {
                                confirmFilterOff = true
                            }
                        },
                        colors = switchColors(),
                    )
                }
                // The VPN reassurance is pre-grant guidance, so it goes when the
                // grant is done. Left permanently visible it was a paragraph of
                // explanation attached to a switch that was already on — which
                // is what made the top of this screen feel like a manual rather
                // than a status.
                if (!filterRunning) {
                    Spacer(Modifier.height(Space.md))
                    Text(
                        "Android will ask for \"VPN\" permission. Bastion isn't sending " +
                            "your traffic anywhere — it uses that only to check website " +
                            "addresses against its blocklist, on this phone. A few apps " +
                            "use their own private connection and slip past; the setting " +
                            "below closes that gap.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextTertiary,
                    )
                }
                Spacer(Modifier.height(Space.md))
                QuietButton(
                    "Open Bastion browser",
                    {
                        context.startActivity(Intent(context, FilteredBrowserActivity::class.java))
                    },
                    Modifier.fillMaxWidth(),
                )
            }
        }

        // --- The one way into configuration -----------------------------
        //
        // Everything below this line used to be part of the same endless
        // scroll: guarded apps, feed rules, the cooling-off lock, the
        // break-glass plan, Private DNS and the veil. A user opens Guard
        // twenty times to check it for every once they change it, so checking
        // is what the default should serve.
        if (!managing) {
            BastionRow(
                title = "Manage protection",
                subtitle = "Apps, feeds, lock-in and the break-glass plan",
                trailing = { Text("›", color = BastionColors.BronzeBright) },
                onClick = { managing = true },
            )
        }

        if (managing) {
            // Group headings on the configuration screen too.
            //
            // Six blocks in one scroll, each a label and some controls, read as
            // one undifferentiated list — the complaint was that it all felt
            // like the same part. Naming what each group is for is most of the
            // fix; the shared surface behind each one does the rest.
            Section("What is guarded", spacing = Space.lg) {
            GuardedAppsSection(
                apps = guardedApps,
                guardRunning = serviceRunning,
                onAdd = { showAppPicker = true },
                onModeChange = { app, mode ->
                    // Loosening waits and is confirmed; tightening is instant and
                    // needs no ceremony. Relaxing a mode is the same kind of
                    // decision as removing the guard altogether, so it gets the
                    // same dialog.
                    if (mode.isWeakerThan(app.mode)) {
                        confirmRelax = app to mode
                    } else {
                        scope.launch {
                            graph.guard.upsertApp(
                                app.copy(mode = mode, updatedAt = System.currentTimeMillis())
                            )
                        }
                    }
                },
                onRemove = { confirmUnguard = it },
                onTurnGuardOn = { BastionAccessibilityService.openSettings(context) },
            )

            FeedRulesSection(
                rules = feedRules,
                guardedApps = guardedApps,
                guardRunning = serviceRunning,
                onSetGroup = { pkg, enabled ->
                    scope.launch {
                        val group = feedRules.filter { it.packageName == pkg }
                        if (enabled) {
                            group.filterNot { it.enabled }
                                .forEach { graph.guard.upsertRule(it.copy(enabled = true)) }
                        } else if (!settings.tamperLockEnabled) {
                            group.filter { it.enabled }
                                .forEach { graph.guard.upsertRule(it.copy(enabled = false)) }
                        } else {
                            // One request for the group rather than one per rule,
                            // so the waiting list says "Instagram" instead of
                            // listing four matchers a man never chose separately.
                            graph.guard.requestWeakening(
                                "Stop closing feeds in ${appLabel(context, pkg)}",
                                payload = "rulegroup:$pkg",
                            )
                        }
                    }
                },
                onSetRule = { rule, enabled ->
                    scope.launch {
                        if (enabled) graph.guard.upsertRule(rule.copy(enabled = true))
                        else if (!settings.tamperLockEnabled) {
                            graph.guard.upsertRule(rule.copy(enabled = false))
                        } else graph.guard.requestWeakening(
                            "Disable rule ${rule.label}",
                            payload = "rule:${rule.id}:off",
                        )
                    }
                },
                onGuardApp = { pkg ->
                    // Guarding straight from the broken link, in the mode that
                    // makes these rules do something. Sending him to the other
                    // section to work it out is most of the way to him not
                    // bothering.
                    scope.launch {
                        graph.guard.upsertApp(
                            com.bastion.app.data.db.GuardedAppEntity(
                                packageName = pkg,
                                label = appLabel(context, pkg),
                                mode = BlockMode.FEED_ONLY,
                            )
                        )
                    }
                },
                onLearn = { showLearnMode = true },
            )
            }

            Section("How hard it is to undo", spacing = Space.md) {

            // --- Tamper resistance ---
            BastionCard(accent = BastionColors.Bronze) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // "Cooling-off lock" named a mechanism. This names the
                        // thing it does for you, which is the only reason to
                        // turn it on.
                        SectionLabel("Make changes hard to undo")
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "Turning protection OFF waits before it happens, so a " +
                                "weak moment can't undo your setup in seconds. " +
                                "Turning protection ON is always immediate.",
                            style = MaterialTheme.typography.bodySmall,
                            color = BastionColors.TextMuted,
                        )
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            // The two states are named plainly, because the whole
                            // point is knowing which one you are in before you
                            // change something.
                            if (settings.tamperLockEnabled)
                                "On — off-switches wait ${com.bastion.app.data.repo.GuardRepository.Delay.describe(settings.coolingOffMinutes)}"
                            else
                                "Off — changes happen right away",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (settings.tamperLockEnabled) BastionColors.BronzeBright
                            else BastionColors.TextMuted,
                        )
                    }
                    Switch(
                        checked = settings.tamperLockEnabled,
                        onCheckedChange = { wanted ->
                            // Locking in is instant; unlocking is itself a
                            // weakening and waits, or the lock would be a button
                            // that turns itself off.
                            if (wanted) scope.launch {
                                graph.settings.setTamperLock(true)
                                // The flag alone was the whole feature. Locking in
                                // now also closes the doors Android lets an app
                                // close, and starts the short watch that notices
                                // Guard going down within a minute.
                                com.bastion.app.guard.lockdown.DeviceOwner.apply(context, true)
                                com.bastion.app.core.alarm.LockInWatchScheduler.sync(context, true)
                            }
                            else confirmUnlock = true
                        },
                        colors = switchColors(),
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    // Never "cannot be turned off". The accessibility toggle stays
                    // reachable no matter what — Android keeps it that way on
                    // purpose — and a promise the app cannot keep would poison
                    // every promise it can.
                    com.bastion.app.guard.lockdown.DeviceOwner.statusLine(
                        context,
                        settings.tamperLockEnabled,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                // The mechanics, folded away. This used to run inline: four
                // paragraphs about what Android does and does not permit, then
                // a black box containing an `adb shell` command. Almost nobody
                // will ever run that command, and putting it on the face of the
                // screen taught everyone else that Guard is a page to scroll
                // past. It stays one tap away, because a man locking himself in
                // is owed the exact terms.
                com.bastion.app.core.design.Advanced(label = "How this is enforced") {
                    Text(
                        "Guard itself can always be switched off in Android's settings. " +
                            "Locked in, that puts a wall in front of you that needs the partner's " +
                            "code or the wait — and Bastion keeps putting it back. Home still " +
                            "leaves it unless the command below has been run. A wall, not a cage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.TextMuted,
                    )

                    if (!com.bastion.app.guard.lockdown.DeviceOwner.isDeviceOwner(context)) {
                        Spacer(Modifier.height(Space.md))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Close uninstall and factory reset — one adb command, once:",
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.TextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            LinkButton(if (ownerCommandCopied) "Copied" else "Copy") {
                                ownerClipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(
                                        com.bastion.app.guard.lockdown.DeviceOwner.setupCommand(context)
                                    )
                                )
                                ownerCommandCopied = true
                            }
                        }
                        Spacer(Modifier.height(Space.sm))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Space.md))
                                .background(BastionColors.MidnightDeep)
                                .padding(Space.md)
                        ) {
                            Text(
                                com.bastion.app.guard.lockdown.DeviceOwner.setupCommand(context),
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.SageBright,
                            )
                        }
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            "Only works on a phone with no accounts added yet, so it is a " +
                                "fresh-device thing. Without it, everything above still applies " +
                                "except the uninstall and reset blocks.",
                            style = MaterialTheme.typography.labelSmall,
                            color = BastionColors.TextMuted,
                        )
                    }
                }

                Spacer(Modifier.height(Space.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    com.bastion.app.data.repo.GuardRepository.Delay.CHOICES.forEach { minutes ->
                        DelayChip(minutes, settings.coolingOffMinutes == minutes) {
                            scope.launch {
                                // Lengthening the delay is a tightening; shortening waits its own delay.
                                if (minutes >= settings.coolingOffMinutes || !settings.tamperLockEnabled) {
                                    graph.settings.setCoolingOffMinutes(minutes)
                                } else graph.guard.requestWeakening(
                                    "Shorten the cooling-off delay to " +
                                        com.bastion.app.data.repo.GuardRepository.Delay.describe(minutes),
                                    payload = "cooloffm:$minutes",
                                )
                            }
                        }
                    }
                }
                if (settings.coolingOffMinutes < com.bastion.app.data.repo.GuardRepository.Delay.TEST_ONLY_BELOW_MINUTES) {
                    Spacer(Modifier.height(Space.sm))
                    // Said plainly rather than left for him to work out. A delay
                    // he can sit through in one go is a rehearsal of the
                    // mechanism, not the mechanism — and an app that offered it
                    // as an equal choice would be overstating itself.
                    Text(
                        "That is a test setting. A delay you can wait out in one sitting " +
                            "proves the wall works; it will not stop you at 1am.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.Amber,
                    )
                }

                if (pendingChanges.isNotEmpty()) {
                    Spacer(Modifier.height(Space.lg))
                    SectionLabel("Waiting", color = BastionColors.Amber)
                    Spacer(Modifier.height(Space.sm))
                    pendingChanges.forEach { change ->
                        val remaining = ((change.effectiveAt - now) / 60_000L).coerceAtLeast(0)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = Space.sm),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(change.description, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextPrimary)
                                Text(
                                    if (remaining > 60) "in ${remaining / 60}h ${remaining % 60}m" else "in ${remaining}m",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BastionColors.Amber,
                                )
                            }
                            LinkButton("Cancel", BastionColors.SageBright) {
                                scope.launch { graph.guard.cancelChange(change.id) }
                            }
                        }
                    }
                }
            }

            // --- Extras ---
            //
            // Everything that is configured rather than acted on. The panic
            // button itself lives on the home screen, where it can be reached
            // without going looking; only its plan belongs here.
            }

            Section("Extra protection", spacing = Space.md) {
                LockdownPlanCard(settings = settings, graph = graph)

                // Directly beneath the plan it obeys. Putting the schedule on
                // the Settings screen would have split one feature across two
                // places and left the question "what will it actually do at ten
                // o'clock?" answered a tab away from where it is asked.
                ScheduledLockdownCard(settings = settings, graph = graph)

                PrivateDnsCard()

                GrayscaleCard(settings = settings, graph = graph)
            }
        }
    }

    if (showAppPicker) {
        BastionBottomSheet(onDismiss = { showAppPicker = false }) {
            AppPickerSheet(
                alreadyGuarded = guardedApps.map { it.packageName }.toSet(),
                onPick = { pkg, label, mode ->
                    scope.launch {
                        graph.guard.upsertApp(
                            GuardedAppEntity(packageName = pkg, label = label, mode = mode)
                        )
                        showAppPicker = false
                    }
                },
            )
        }
    }

    confirmUnguard?.let { app ->
        ConfirmDialog(
            title = "Stop guarding ${app.label}?",
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${com.bastion.app.data.repo.GuardRepository.Delay.describe(settings.coolingOffMinutes)}. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue(
                    "Stop guarding ${app.label}",
                    "remove:${app.packageName}",
                ) { graph.guard.removeApp(app.packageName) }
            },
            onDismiss = { confirmUnguard = null },
        )
    }

    pendingWeakening?.let { action ->
        PasscodeDialog(
            onAttempt = { code -> graph.passcodeGate.attempt(code) },
            initialWait = { graph.passcodeGate.waitMillis() },
            onUnlocked = {
                scope.launch { action() }
                pendingWeakening = null
            },
            onDismiss = { pendingWeakening = null },
        )
    }

    confirmRelax?.let { (app, mode) ->
        ConfirmDialog(
            title = "Relax ${app.label} to ${mode.label()}?",
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${com.bastion.app.data.repo.GuardRepository.Delay.describe(settings.coolingOffMinutes)}. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue(
                    "Relax ${app.label} to ${mode.label()}",
                    "app:${app.packageName}:${mode.name}",
                ) { graph.guard.upsertApp(app.copy(mode = mode, updatedAt = System.currentTimeMillis())) }
            },
            onDismiss = { confirmRelax = null },
        )
    }

    if (confirmStandDown) {
        ConfirmDialog(
            title = "Stop using Guard?",
            body = "Bastion will stop expecting it and stop warning you. Your streak, " +
                "history and guarded-app list all stay. Turn Guard back on any time and " +
                "this picks up where it left off.",
            confirmLabel = "Stop expecting it",
            onConfirm = {
                // Refused outright during a lockdown, and gated by the partner's
                // code otherwise. This is the widest weakening on the screen —
                // it switches off the only thing that notices the others are
                // gone — so it must not be the cheapest one to reach.
                if (com.bastion.app.guard.lockdown.Lockdown.isActive(settings)) {
                    blockedByLockdown = true
                } else {
                    weaken {
                        com.bastion.app.guard.GuardWatchdog.standDown(context)
                        guardBreached = false
                    }
                }
            },
            onDismiss = { confirmStandDown = false },
        )
    }

    if (blockedByLockdown) {
        ConfirmDialog(
            title = "Lockdown is running",
            body = "Guards can't be weakened until it ends. That is the point of it.",
            confirmLabel = "All right",
            onConfirm = {},
            onDismiss = { blockedByLockdown = false },
        )
    }

    if (confirmUnlock) {
        ConfirmDialog(
            title = "Unlock the guards?",
            body = "Unlocking waits ${com.bastion.app.data.repo.GuardRepository.Delay.describe(settings.coolingOffMinutes)}, like any other weakening. " +
                "Until then everything stays as it is.",
            confirmLabel = "Request it",
            onConfirm = {
                weaken { graph.guard.requestWeakening("Unlock the guards", "unlock") }
            },
            onDismiss = { confirmUnlock = false },
        )
    }

    if (confirmFilterOff) {
        ConfirmDialog(
            title = "Turn the content filter off?",
            body = if (settings.tamperLockEnabled) {
                "This won't take effect for ${com.bastion.app.data.repo.GuardRepository.Delay.describe(settings.coolingOffMinutes)}. " +
                    "You can cancel it any time before then."
            } else {
                "You haven't locked in, so this happens straight away."
            },
            confirmLabel = "Request it",
            onConfirm = {
                weakenOrQueue("Turn off the content filter", "vpn:off") {
                    graph.settings.setVpnEnabled(false)
                    BastionVpnService.stop(context)
                }
            },
            onDismiss = { confirmFilterOff = false },
        )
    }

    if (showLearnMode) {
        BastionBottomSheet(
            onDismiss = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            },
        ) {
            LearnModeSheet(onClose = {
                showLearnMode = false
                BastionAccessibilityService.learnMode.value = false
            })
        }
    }
}

/**
 * Closes the biggest hole in the content filter.
 *
 * Bastion's own filter intercepts the system resolver, which an app shipping its
 * own DNS-over-HTTPS simply walks around — that limitation is stated honestly
 * elsewhere in this screen, but stating it is not the same as narrowing it.
 *
 * Android's Private DNS setting is enforced by the platform below the app layer,
 * so pointing it at a filtering resolver covers ground the local VPN cannot.
 * It is one field, typed once, and it survives Bastion being uninstalled — which
 * is a feature rather than a flaw.
 */
@Composable
private fun PrivateDnsCard() {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Re-read on resume, because the change happens in Android's settings and
    // the user comes straight back here expecting the screen to have noticed.
    var live by remember { mutableStateOf<String?>(null) }
    LifecycleResumeEffect(Unit) {
        live = com.bastion.app.guard.vpn.DnsFilters.privateDnsHostname(context)
        scope.launch { com.bastion.app.guard.GuardWatchdog.reconcile(context) }
        onPauseOrDispose {}
    }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }

    var queuedDnsStandDown by remember { mutableStateOf(false) }
    val watching = settings.dnsIntendedOn
    val isOff = watching && live == null

    Column(Modifier.fillMaxWidth()) {
        // "Close the DNS-over-HTTPS gap" is accurate and means nothing to
        // anyone who has not implemented a resolver.
        SectionLabel("Extra website blocking (recommended)")
        Spacer(Modifier.height(Space.sm))
        Text(
            when {
                isOff -> "Off. You had this set to ${settings.dnsHostname} — " +
                    "while you're locked in, turning it off puts a wall in front of you."
                live != null -> "On: $live. Bastion is watching it. If it goes off while " +
                    "you're locked in, you'll hit the same wall as when Guard goes off."
                else -> "Set this as Private DNS. It filters below the app layer, where " +
                    "Bastion can't reach — and once it's set, Bastion holds you to it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isOff) BastionColors.Amber else BastionColors.TextMuted,
        )
        if (watching) {
            // The intent must not be a one-way latch. Someone who genuinely
            // stops using Private DNS has to be able to say so, or the nag
            // becomes something to ignore — and an ignored warning costs the
            // warnings that matter.
            com.bastion.app.core.design.LinkButton(
                "I'm done with Private DNS",
                BastionColors.TextMuted,
            ) {
                scope.launch {
                    queuedDnsStandDown =
                        !com.bastion.app.guard.GuardWatchdog.standDownDns(context)
                }
            }
            if (queuedDnsStandDown) {
                Text(
                    "Queued. It stops watching once the cooling-off wait is served — " +
                        "the same delay every other off-switch takes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.Amber,
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        // Setting it up is two taps: copy the hostname, open the settings
        // screen. That much stays on the surface because it is what a man
        // actually does. What the hostname *is* — a resolver address he will
        // never type twice — sits behind the disclosure with the rest of the
        // machinery.
        com.bastion.app.core.design.Advanced(label = "The address") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Space.md))
                    .background(BastionColors.MidnightDeep)
                    .padding(Space.md)
            ) {
                Text(
                    PRIVATE_DNS_HOST,
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.SageBright,
                )
            }
            Spacer(Modifier.height(Space.sm))
            Text(
                "Cloudflare's family resolver. It answers DNS lookups for adult " +
                    "domains with nothing, below the layer any app can reach.",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(Space.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            QuietButton(
                text = if (copied) "Copied" else "Copy the address",
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(PRIVATE_DNS_HOST))
                    copied = true
                },
                modifier = Modifier.weight(1f),
                accent = BastionColors.SageBright,
            )
            QuietButton(
                text = "Open settings",
                onClick = {
                    // Falls back to the top-level settings screen: the Private DNS
                    // action is not present on every OEM build, and a dead button
                    // would be worse than one extra tap.
                    val opened = runCatching {
                        context.startActivity(
                            Intent("android.settings.WIRELESS_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Cloudflare's family resolver: blocks adult content at the resolver itself. */
private const val PRIVATE_DNS_HOST = "family.cloudflare-dns.com"

@Composable
private fun GrayscaleCard(settings: Settings, graph: BastionGraph) {
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Dim the tempting apps",
                    style = MaterialTheme.typography.titleMedium,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "A dimming veil over guarded apps",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
            Switch(
                checked = settings.grayscaleEnabled,
                onCheckedChange = { scope.launch { graph.settings.setGrayscale(it) } },
                colors = switchColors(),
            )
        }
        Spacer(Modifier.height(Space.md))
        Text(
            // What it is, with no overclaim. The card used to offer an adb
            // command to grant WRITE_SECURE_SETTINGS and promise "true
            // grayscale" in exchange; nothing in the app ever wrote a system
            // setting, so the grant bought a change of wording and nothing
            // else. The veil is the real mechanism, and it is a nudge rather
            // than a wall.
            "Takes the shine off a feed without hiding it. A nudge, not a wall.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
    }
}

/**
 * Text-styled actions that are still real buttons. Guard is used one-handed in a
 * bad moment, so every action carries button semantics and a 48dp target rather
 * than being a clickable label.
 */

/** Weakening is the direction that costs something, so a mis-tap must not start the clock. */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BastionColors.Surface,
        titleContentColor = BastionColors.TextPrimary,
        textContentColor = BastionColors.TextSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            LinkButton(confirmLabel) {
                onConfirm()
                onDismiss()
            }
        },
        dismissButton = { LinkButton("Not now", BastionColors.TextMuted, onDismiss) },
    )
}

/**
 * Asks for the code the accountability partner holds.
 *
 * Deliberately offers no way past itself. There is no "forgot the code" escape,
 * because an escape hatch is exactly what the weak-moment self would reach for —
 * the way through is to phone the partner, which is the entire point of having
 * handed him the code in the first place.
 */
@Composable
private fun PasscodeDialog(
    onAttempt: suspend (String) -> com.bastion.app.core.security.PasscodeGate.Result,
    initialWait: suspend () -> Long,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var waitMillis by remember { mutableStateOf(0L) }

    // Counts down live rather than reading once on open: a dialog frozen at
    // "wait 4 min" gives no sign it is still running, and the natural response
    // is to close and reopen it — which is exactly the hammering being slowed.
    LaunchedEffect(Unit) { waitMillis = initialWait() }
    LaunchedEffect(waitMillis) {
        if (waitMillis > 0) {
            kotlinx.coroutines.delay(1_000)
            waitMillis = (waitMillis - 1_000).coerceAtLeast(0L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BastionColors.Surface,
        title = {
            Text(
                "Your partner's code",
                style = MaterialTheme.typography.titleMedium,
                color = BastionColors.TextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    "You asked him to hold this so tonight's version of you couldn't undo it alone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Spacer(Modifier.height(Space.lg))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; wrong = false },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword,
                    ),
                    enabled = waitMillis == 0L,
                    isError = wrong || waitMillis > 0,
                    supportingText = when {
                        waitMillis > 0 -> {
                            {
                                Text(
                                    "Too many tries. " +
                                        com.bastion.app.core.security.formatWait(waitMillis) +
                                        " to go.",
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
                    shape = RoundedCornerShape(Space.md),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BastionColors.Bronze,
                        unfocusedBorderColor = BastionColors.Outline,
                        focusedTextColor = BastionColors.TextPrimary,
                        unfocusedTextColor = BastionColors.TextPrimary,
                        cursorColor = BastionColors.Bronze,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = code.isNotBlank() && !checking && waitMillis == 0L,
                onClick = {
                    scope.launch {
                        checking = true
                        when (val result = onAttempt(code)) {
                            is com.bastion.app.core.security.PasscodeGate.Result.Unlocked ->
                                onUnlocked()
                            is com.bastion.app.core.security.PasscodeGate.Result.Wrong -> {
                                wrong = true
                                waitMillis = result.waitMillis
                            }
                            is com.bastion.app.core.security.PasscodeGate.Result.Wait ->
                                waitMillis = result.millis
                        }
                        checking = false
                    }
                },
            ) {
                Text("Unlock", color = BastionColors.BronzeBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Leave it locked", color = BastionColors.TextMuted)
            }
        },
    )
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) BastionColors.Sage else BastionColors.Amber)
    )
}

@Composable
private fun DelayChip(minutes: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) BastionColors.BronzeDeep else BastionColors.SurfaceRaised)
            .border(
                1.dp,
                if (selected) BastionColors.Bronze else BastionColors.Outline,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            com.bastion.app.data.repo.GuardRepository.Delay.describeShort(minutes),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) BastionColors.BronzeBright else BastionColors.TextMuted,
        )
    }
}

/**
 * Learn Mode. Open the screen you want blocked, and Bastion lists the view
 * identifiers on it so a rule can be rebuilt when an app changes its layout.
 * Identifiers only — no text is ever captured.
 */
@Composable
private fun LearnModeSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val capture by BastionAccessibilityService.learnedIds.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { BastionAccessibilityService.learnMode.value = true }

    var showAll by remember { mutableStateOf(false) }

    Column(
        Modifier
            // heightIn, not a fixed height: at a large system font scale a
            // fixed 520dp clipped this sheet mid-sentence.
            .heightIn(min = 320.dp, max = 560.dp)
    ) {
        Text(
            // "Learn mode" describes what the app does. This describes the
            // problem the user actually has when they reach for it.
            "Fix a feed slipping through",
            style = MaterialTheme.typography.headlineSmall,
            color = BastionColors.TextPrimary,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            "If an app updates and its feed starts getting past Bastion: open " +
                "that feed, come back here, and pick it from the list.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            "Identifiers only — no text is read.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
        Spacer(Modifier.height(Space.lg))

        val current = capture
        if (current == null) {
            Text("Nothing captured yet.", style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextMuted)
        } else {
            SectionLabel(current.packageName)
            Spacer(Modifier.height(Space.sm))

            // The live verdict. A rule could be saved, look correct, and never
            // fire; this says plainly whether the screen just captured is
            // covered, so a broken rule is a twenty-second fix instead of
            // something discovered by not being stopped.
            Text(
                if (current.blockedNow) "This screen would be blocked."
                else "This screen would NOT be blocked.",
                style = MaterialTheme.typography.titleSmall,
                color = if (current.blockedNow) BastionColors.SageBright else BastionColors.Amber,
            )
            Spacer(Modifier.height(Space.md))

            // Why a browser rule is or is not firing, in the place a man is
            // already standing when he notices it did not.
            //
            // Shown only where an address rule could apply, so it never appears
            // on an ordinary app. Four attempts were made at the in-app-browser
            // path without ever seeing what the service actually had in front of
            // it, and every one of them was a guess that could not be checked.
            // This is the screen that ends that.
            if (current.urlRuleCount > 0 || current.webViewFound) {
                Text(
                    "Web address rules",
                    style = MaterialTheme.typography.titleSmall,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    buildString {
                        append(
                            when (current.guardedAs) {
                                null -> "This app is not guarded, so nothing here can fire. "
                                "FEED_ONLY" -> "Guarded as feeds only. "
                                else -> "Guarded as ${current.guardedAs?.lowercase()}, " +
                                    "which blocks the whole app rather than using these rules. "
                            }
                        )
                        append("${current.urlRuleCount} address ")
                        append(if (current.urlRuleCount == 1) "rule is" else "rules are")
                        append(" switched on. ")
                        append(
                            if (current.webViewFound) "A web page was found on this screen."
                            else "No web page found on this screen."
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextTertiary,
                )
                Spacer(Modifier.height(Space.sm))

                if (current.addresses.isEmpty()) {
                    Text(
                        // The outcome no further release can fix, and the one I
                        // could not see from a laptop. Said plainly so the next
                        // step is a switch rather than another guess.
                        "No web address is visible on this screen. Nothing can be matched " +
                            "against, however the rule is written — this browser shows only " +
                            "the page title. Turn on the whole-site rule for this app instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = BastionColors.Amber,
                    )
                } else {
                    current.addresses.forEach { seen ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                seen.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (seen.isAddressBar) BastionColors.SageBright
                                else BastionColors.TextTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                seen.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = BastionColors.TextMuted,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.lg))
            }

            val qualifying = current.viewIds.filter { it.wouldBlock }
            val shown = if (showAll) current.viewIds else qualifying
            if (qualifying.isEmpty() && !showAll) {
                Text(
                    "Nothing on this screen looks like a full-screen vertical player. " +
                        "Open the feed itself, not a preview of it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
                Spacer(Modifier.height(Space.sm))
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                shown.forEach { learned ->
                    val id = learned.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    graph.guard.upsertRule(
                                        com.bastion.app.data.db.FeedRuleEntity(
                                            id = "learned_${current.packageName}_$id".take(120),
                                            packageName = current.packageName,
                                            label = "Learned · $id",
                                            matchType = com.bastion.app.data.db.MatchType.VIEW_ID,
                                            matchValue = id,
                                            builtIn = false,
                                        )
                                    )
                                    onClose()
                                }
                            }
                            .padding(vertical = Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            id,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (learned.wouldBlock) BastionColors.SageBright
                            else BastionColors.TextMuted,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (learned.wouldBlock) {
                            Text(
                                "would block",
                                style = MaterialTheme.typography.labelSmall,
                                color = BastionColors.SageBright,
                            )
                        }
                    }
                }
                if (!showAll && current.viewIds.size > qualifying.size) {
                    LinkButton(
                        "Show all ${current.viewIds.size} identifiers",
                        BastionColors.TextMuted,
                    ) { showAll = true }
                }
            }
        }
        Spacer(Modifier.height(Space.md))
        QuietButton("Done", onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = BastionColors.MidnightDeep,
    checkedTrackColor = BastionColors.Bronze,
    uncheckedThumbColor = BastionColors.TextMuted,
    uncheckedTrackColor = BastionColors.SurfaceHigh,
    uncheckedBorderColor = BastionColors.Outline,
)

/**
 * Private DNS lives in a different place on almost every OEM skin, and the
 * dedicated action is not present on all of them, so this walks down from most
 * to least specific rather than risking a dead button.
 */
private fun openPrivateDnsSettings(context: Context) {
    val candidates = listOf(
        Intent("android.settings.PRIVATE_DNS_SETTINGS"),
        Intent("android.settings.WIRELESS_SETTINGS"),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/**
 * The app's own notification settings rather than a permission request.
 *
 * Android stops showing the runtime prompt after it has been dismissed twice,
 * and a button that silently does nothing is worse than one extra tap. This
 * screen always works and also covers the case where the permission was
 * granted and the channel was muted afterwards.
 */
private fun openNotificationSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/**
 * Named by what happens, not by what the mode is called internally.
 *
 * "Feed only" is a developer's shorthand — only the feed *what*? Blocked?
 * Allowed? It read either way, and a switch that can be read two opposite ways
 * is worse than no label. These say the outcome instead.
 */
private fun BlockMode.label(): String = when (this) {
    BlockMode.FULL -> "Block the whole app"
    BlockMode.SCHEDULE -> "Block on a schedule"
    BlockMode.TIME_LIMIT -> "Daily time limit"
    BlockMode.FEED_ONLY -> "Block the endless feed"
}

/** The one-liner under a mode, finishing the sentence "…so you ______." */
private fun BlockMode.explain(): String = when (this) {
    BlockMode.FULL -> "The app won't open at all."
    BlockMode.SCHEDULE -> "The app won't open during the hours you set."
    BlockMode.TIME_LIMIT -> "The app closes once you've used your daily minutes."
    BlockMode.FEED_ONLY ->
        "The app opens normally — messages, search, posting. Reels, Shorts and " +
            "For You close as soon as they appear."
}

/** Two words, for the segmented control. */
private fun BlockMode.shortLabel(): String = when (this) {
    BlockMode.FULL -> "Whole app"
    BlockMode.SCHEDULE -> "Schedule"
    BlockMode.TIME_LIMIT -> "Time limit"
    BlockMode.FEED_ONLY -> "Feed only"
}

/** Ordering by strictness, so the cooling-off lock knows which way a change goes. */
private fun BlockMode.strictness(): Int = when (this) {
    BlockMode.FULL -> 4
    BlockMode.SCHEDULE -> 3
    BlockMode.TIME_LIMIT -> 2
    BlockMode.FEED_ONLY -> 1
}

private fun BlockMode.isWeakerThan(other: BlockMode): Boolean = strictness() < other.strictness()
