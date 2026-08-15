package com.bastion.app.feature.guardui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.LockedInNote
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.MatchType
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.repo.GuardRepository

/**
 * The two sections a man actually comes to this screen to change, and the one
 * relationship between them that was invisible.
 *
 * A feed rule does nothing on its own. The accessibility service looks up the
 * foreground package in the guarded-apps table, returns immediately if it is not
 * there, and only consults feed rules when that app's mode is Feeds only. So a
 * rule switched on for TikTok while TikTok is not guarded is a switch wired to
 * nothing — and the old screen showed it identically to one that was working.
 * A man could toggle it, watch nothing change, and reasonably conclude the app
 * was broken.
 *
 * Everything here is arranged around making that chain visible: Guard running →
 * app guarded → mode is Feeds only → rule enabled. Break any link and the UI
 * says which one, in the place where you would look.
 */

// --- app identity ------------------------------------------------------------

/** Friendly names for the apps the built-in rules cover. */
private val KNOWN_NAMES = mapOf(
    "com.instagram.android" to "Instagram",
    "com.google.android.youtube" to "YouTube",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.ss.android.ugc.trill" to "TikTok",
    "com.facebook.katana" to "Facebook",
    "com.snapchat.android" to "Snapchat",
    "com.twitter.android" to "X",
    "com.reddit.frontpage" to "Reddit",
)

/**
 * The installed app's own label where possible, the known name otherwise.
 *
 * Asking the package manager first means a rule for an app the user does not
 * have still reads as "TikTok" rather than as a package id, and an app that
 * renames itself is followed automatically.
 */
internal fun appLabel(context: Context, pkg: String): String =
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
        ?: KNOWN_NAMES[pkg]
        ?: pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase)

internal fun isInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

/**
 * The launcher icon, as a Compose image.
 *
 * A list of forty text labels is read one line at a time; the same list with
 * icons is scanned. This is the single biggest difference to how quickly the
 * picker can be used, and it costs one bitmap per row.
 */
@Composable
internal fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 36.dp) {
    val context = LocalContext.current
    val image: ImageBitmap? = remember(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg)
                .toBitmap(width = 96, height = 96)
                .asImageBitmap()
        }.getOrNull()
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(Space.sm))
            .background(BastionColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                painter = BitmapPainter(image),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            // An app that is not installed still gets a row, so it needs a mark
            // rather than a hole.
            Text(
                appLabel(LocalContext.current, pkg).take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = BastionColors.TextTertiary,
            )
        }
    }
}

// --- guarded apps ------------------------------------------------------------

/** What a mode does, said as an outcome rather than as a setting name. */
internal fun BlockMode.outcome(): String = when (this) {
    BlockMode.FULL -> "Won't open at all"
    BlockMode.FEED_ONLY -> "Feeds close, the rest stays open"
    BlockMode.SCHEDULE -> "Blocked during the hours you set"
    BlockMode.TIME_LIMIT -> "Closes after your daily minutes"
}

@Composable
fun GuardedAppsSection(
    apps: List<GuardedAppEntity>,
    guardRunning: Boolean,
    onAdd: () -> Unit,
    onModeChange: (GuardedAppEntity, BlockMode) -> Unit,
    onRemove: (GuardedAppEntity) -> Unit,
    onTurnGuardOn: () -> Unit,
    /** Worded delay while the lock is on; null when it is off. */
    lockedInDelay: String? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Guarded apps")
        LinkButton("Add an app") { onAdd() }
    }
    Spacer(Modifier.height(Space.sm))
    // Said once, at the top, because not saying it is what made the screen
    // confusing: a man saw a list of Instagram rules and reasonably assumed
    // they were in force. This is the list that decides. Nothing below it
    // applies to an app that is not on it, in its own app or in a browser.
    Text(
        "The apps Bastion is allowed to touch. Nothing further down applies to " +
            "an app that isn't on this list.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextTertiary,
    )
    Spacer(Modifier.height(Space.md))

    // The first broken link, named where it breaks. Every row below is inert
    // without the service, and the old screen showed them exactly as it showed
    // working ones.
    if (!guardRunning && apps.isNotEmpty()) {
        Notice(
            "Guard is switched off, so none of these are being enforced right now.",
            action = "Turn Guard on",
            onAction = onTurnGuardOn,
        )
        Spacer(Modifier.height(Space.md))
    }

    if (apps.isEmpty()) {
        Text(
            "Nothing guarded yet, so nothing is being blocked. Instagram and " +
                "YouTube are the usual first two.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextTertiary,
        )
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .background(BastionColors.Surface),
    ) {
        apps.forEachIndexed { index, app ->
            if (index > 0) Divider()
            GuardedAppRow(
                app = app,
                onModeChange = { onModeChange(app, it) },
                onRemove = { onRemove(app) },
                lockedInDelay = lockedInDelay,
            )
        }
    }
}

@Composable
private fun GuardedAppRow(
    app: GuardedAppEntity,
    onModeChange: (BlockMode) -> Unit,
    onRemove: () -> Unit,
    lockedInDelay: String?,
) {
    var open by remember(app.packageName) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                )
                // The outcome, not the mode's name. "Feed only" is a developer's
                // shorthand that reads either way — only the feed blocked, or
                // only the feed allowed — and a label that can be read two
                // opposite ways is worse than none.
                Text(
                    app.mode.outcome(),
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextTertiary,
                )
            }
            Text(
                if (open) "▴" else "▾",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.TextTertiary,
            )
        }

        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.padding(start = Space.md, end = Space.md, bottom = Space.md)) {
                // SCHEDULE and TIME_LIMIT are deliberately absent: both need a
                // parameter with nowhere to set it, so choosing them silently
                // applied a default of 00:00-00:00 or a fixed cap. A mode an
                // older build already set stays listed, so the row keeps telling
                // the truth about what is actually running.
                val modes = buildList {
                    add(BlockMode.FEED_ONLY)
                    add(BlockMode.FULL)
                    if (app.mode !in this) add(app.mode)
                }
                modes.forEach { mode ->
                    ModeOption(
                        mode = mode,
                        selected = app.mode == mode,
                        onSelect = { if (app.mode != mode) onModeChange(mode) },
                    )
                    Spacer(Modifier.height(Space.sm))
                }
                lockedInDelay?.let {
                    LockedInNote(
                        it,
                        what = "relaxing this, or stopping it,",
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
                }
                LinkButton("Stop guarding ${app.label}", BastionColors.TextTertiary, onRemove)
            }
        }
    }
}

/**
 * A mode as a card with its consequence written out, rather than a segment.
 *
 * The segmented control had room for two words and put the explanation
 * underneath, which meant the sentence describing the *selected* mode sat below
 * a control offering a different one. Giving each option its own line lets the
 * consequence sit with the choice it belongs to.
 */
@Composable
private fun ModeOption(mode: BlockMode, selected: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(Space.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) BastionColors.SurfaceHigh else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) BastionColors.Sage else BastionColors.OutlineStrong,
                shape = shape,
            )
            .clickable { onSelect() }
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) BastionColors.Sage else Color.Transparent)
                .border(2.dp, if (selected) BastionColors.Sage else BastionColors.OutlineStrong, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text("✓", style = MaterialTheme.typography.labelSmall, color = BastionColors.MidnightDeep)
            }
        }
        Spacer(Modifier.width(Space.md))
        Column {
            Text(
                when (mode) {
                    BlockMode.FULL -> "Block the whole app"
                    BlockMode.FEED_ONLY -> "Block only the endless feed"
                    BlockMode.SCHEDULE -> "Block on a schedule"
                    BlockMode.TIME_LIMIT -> "Daily time limit"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextPrimary,
            )
            Text(
                when (mode) {
                    BlockMode.FULL -> "It won't open at all."
                    BlockMode.FEED_ONLY ->
                        "Messages, search and posting still work. Reels, Shorts and " +
                            "For You close as soon as they appear."
                    BlockMode.SCHEDULE -> "Blocked during the hours you set."
                    BlockMode.TIME_LIMIT -> "Closes once the day's minutes are used."
                },
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextTertiary,
            )
        }
    }
}

// --- feed rules --------------------------------------------------------------

/** Why a group of rules is or is not currently doing anything. */
internal sealed interface RuleState {
    val line: String

    data object Working : RuleState {
        override val line = "Working — closes wherever it appears."
    }
    data object AllOff : RuleState {
        override val line = "Every switch here is off, so this opens normally."
    }
    data object NotGuarded : RuleState {
        override val line = "Does nothing — this isn't in your guarded apps."
    }
    data object WholeAppBlocked : RuleState {
        override val line = "Not needed — the whole app is already blocked."
    }

    /**
     * Blocked outright in its own app, still reachable in a browser.
     *
     * The old line for this said "not needed, the whole app is already
     * blocked", which was true when every rule named a screen inside an app and
     * became false the moment a group also carried addresses. A man who blocked
     * Instagram outright and was told these rules were unnecessary would have
     * gone on opening instagram.com in Chrome, believing he had closed it.
     */
    data object BrowserOnly : RuleState {
        override val line = "The app is fully blocked. These close it in a browser too."
    }
    data object GuardOff : RuleState {
        override val line = "Paused — Guard is switched off."
    }

}

/**
 * Works out whether a group of rules is actually in force.
 *
 * This is the chain the service walks, stated once: Guard running, the app
 * guarded, its mode Feeds only, and at least one rule enabled. Pure so the
 * answer can be checked without a phone, because "the switch is on and nothing
 * happens" is the single worst failure this screen can have.
 */
internal fun ruleState(
    guardRunning: Boolean,
    guardedMode: BlockMode?,
    anyRuleEnabled: Boolean,
    /** Whether the group has rules that fire in a browser, not only in the app. */
    hasBrowserRules: Boolean = false,
): RuleState = when {
    // Not guarded is the whole answer, and it is now the only one.
    //
    // A rule belongs to a service. If the service is not in the guarded list,
    // nothing here fires anywhere — not in its app, not in a browser. That was
    // true of the app rules and quietly untrue of the address ones, which is
    // how Instagram reels came to be closed on a phone where Instagram had
    // never been guarded.
    guardedMode == null -> RuleState.NotGuarded
    // Above the mode, because Guard being down stops every one of these and
    // saying "the whole app is blocked" while nothing is running is a claim a
    // man would only find out was wrong by testing it.
    !guardRunning -> RuleState.GuardOff
    guardedMode != BlockMode.FEED_ONLY ->
        if (hasBrowserRules) RuleState.BrowserOnly else RuleState.WholeAppBlocked
    !anyRuleEnabled -> RuleState.AllOff
    else -> RuleState.Working
}

@Composable
fun FeedRulesSection(
    rules: List<FeedRuleEntity>,
    guardedApps: List<GuardedAppEntity>,
    guardRunning: Boolean,
    onSetGroup: (pkg: String, enabled: Boolean) -> Unit,
    onSetRule: (FeedRuleEntity, Boolean) -> Unit,
    onGuardApp: (pkg: String) -> Unit,
    onLearn: () -> Unit,
    /** Worded delay while the lock is on; null when it is off. */
    lockedInDelay: String? = null,
) {
    val context = LocalContext.current
    val byMode = remember(guardedApps) { guardedApps.associate { it.packageName to it.mode } }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("Which feeds get closed")
        LinkButton("Fix a rule") { onLearn() }
    }
    Spacer(Modifier.height(Space.sm))
    Text(
        "One group per site, and only for sites in your guarded apps above — " +
            "nothing here touches an app you haven't added.\n\n" +
            "\"In the app\" needs that app set to \"Block only the endless " +
            "feed\". \"In a browser\" covers every browser at once, including " +
            "links opened inside other apps, so there is nothing to set up per " +
            "browser.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextTertiary,
    )
    Spacer(Modifier.height(Space.md))

    // Only apps that are actually on the phone.
    //
    // The built-in set covers every app these rules might ever be needed for,
    // which on any given phone is mostly apps a man does not have. Listing
    // Snapchat and Opera to someone who has neither buries the two rows that
    // matter under a catalogue of things he cannot act on, and each one invited
    // the question of whether it was supposed to be doing something.
    //
    // The rules themselves stay in the database untouched, so installing one of
    // them later brings its row back already configured.
    val groups = remember(rules, context) {
        rules.groupBy { it.packageName }
            // Shown when the app is installed, or when the group has an address
            // rule — those apply in a browser whether or not the app is here,
            // and hiding the group would hide the only switch that controls
            // them. Instagram uninstalled is exactly when instagram.com matters.
            .filterKeys { pkg ->
                isInstalled(context, pkg) ||
                    rules.any { it.packageName == pkg && it.matchType != MatchType.VIEW_ID }
            }
            .toList()
            .sortedBy { appLabelKey(it.first) }
    }

    if (groups.isEmpty()) {
        Text(
            "None of the apps these cover are installed.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextTertiary,
        )
        return
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .background(BastionColors.Surface),
    ) {
        groups.forEachIndexed { index, (pkg, groupRules) ->
            if (index > 0) Divider()
            FeedRuleGroup(
                pkg = pkg,
                rules = groupRules,
                label = appLabel(context, pkg),
                state = ruleState(
                    guardRunning = guardRunning,
                    guardedMode = byMode[pkg],
                    anyRuleEnabled = groupRules.any { it.enabled },
                    hasBrowserRules = groupRules.any {
                        it.enabled && it.matchType != MatchType.VIEW_ID
                    },
                ),
                onSetGroup = { onSetGroup(pkg, it) },
                onSetRule = onSetRule,
                onGuardApp = { onGuardApp(pkg) },
                lockedInDelay = lockedInDelay,
            )
        }
    }
}

/** Sort key that keeps the known apps in a stable, readable order. */
private fun appLabelKey(pkg: String) =
    (KNOWN_NAMES[pkg] ?: pkg).lowercase()

@Composable
private fun FeedRuleGroup(
    pkg: String,
    rules: List<FeedRuleEntity>,
    label: String,
    state: RuleState,
    onSetGroup: (Boolean) -> Unit,
    onSetRule: (FeedRuleEntity, Boolean) -> Unit,
    onGuardApp: () -> Unit,
    lockedInDelay: String?,
) {
    var open by remember(pkg) { mutableStateOf(false) }
    val enabledCount = rules.count { it.enabled }

    Column(Modifier.fillMaxWidth().padding(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(pkg, size = 32.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                )
                Text(
                    state.line,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (state) {
                        is RuleState.Working, is RuleState.BrowserOnly -> BastionColors.SageBright
                        is RuleState.NotGuarded, is RuleState.GuardOff -> BastionColors.Amber
                        else -> BastionColors.TextTertiary
                    },
                )
            }
            // One switch for the app, not one per matcher. Two rules for the
            // same destination ("Instagram Reels" and "Instagram Reels
            // (viewer)") are an implementation detail of how the screen is
            // recognised; nobody wants one on and the other off, and offering it
            // invites a half-blocked feed that looks like a bug.
            Switch(
                checked = enabledCount > 0,
                onCheckedChange = onSetGroup,
                colors = switchColorsFor(),
            )
        }

        // The broken link, with the repair next to it. Telling a man the app is
        // not guarded and making him find the other section is most of the way
        // to him not bothering.
        if (state is RuleState.NotGuarded) {
            Spacer(Modifier.height(Space.sm))
            LinkButton("Guard $label so these work", BastionColors.BronzeBright, onGuardApp)
        }

        // Only where switching something off would actually wait: a group with
        // nothing on has nothing to take away, and a note there would be a
        // price quoted for something that is not for sale.
        if (lockedInDelay != null && enabledCount > 0) {
            Spacer(Modifier.height(Space.xs))
            LockedInNote(lockedInDelay, what = "switching any of these off")
        }

        Spacer(Modifier.height(Space.xs))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = Space.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "$enabledCount of ${rules.size} " +
                    if (rules.size == 1) "screen" else "screens",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextTertiary,
            )
            Text(
                if (open) "Hide details" else "Details",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.BronzeBright,
            )
        }

        androidx.compose.animation.AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth()) {
                rules.forEach { rule ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                rule.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.TextSecondary,
                            )
                            // The matcher, shown only here. It is what the rule
                            // is made of rather than what it does, and a
                            // non-technical reader took it for breakage.
                            Text(
                                "${rule.matchType.name.lowercase().replace('_', ' ')} · ${rule.matchValue}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BastionColors.TextMuted,
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { onSetRule(rule, it) },
                            colors = switchColorsFor(),
                        )
                    }
                }
            }
        }
    }
}

// --- the picker --------------------------------------------------------------

/**
 * Choosing an app to guard.
 *
 * The old version listed every launchable app alphabetically as bare text, so
 * finding Instagram on a phone with ninety apps meant scrolling or typing, and
 * the mode it would be given was a grey word on the right that looked like a
 * category. Icons make the list scannable, and the apps this is actually for
 * come first under their own heading.
 */
@Composable
fun AppPickerSheet(
    alreadyGuarded: Set<String>,
    onPick: (pkg: String, label: String, mode: BlockMode) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) null else pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    val matching = apps
        .filter { it.second.contains(query, ignoreCase = true) }
        .filterNot { it.first in alreadyGuarded }
    val suggestedPkgs = GuardRepository.SUGGESTED_PACKAGES.map { it.first }.toSet()
    val suggested = matching.filter { it.first in suggestedPkgs }
    val rest = matching.filterNot { it.first in suggestedPkgs }

    Column(
        Modifier
            .padding(horizontal = 22.dp)
            .padding(bottom = 34.dp)
            .heightIn(max = 560.dp)
    ) {
        Text("Guard an app", style = MaterialTheme.typography.headlineSmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.xs))
        Text(
            "Tap one to guard it. You can change how it's blocked straight afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextTertiary,
        )
        Spacer(Modifier.height(Space.md))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Space.md),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BastionColors.Bronze,
                unfocusedBorderColor = BastionColors.OutlineStrong,
                focusedTextColor = BastionColors.TextPrimary,
                unfocusedTextColor = BastionColors.TextPrimary,
            ),
        )
        Spacer(Modifier.height(Space.md))

        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (suggested.isNotEmpty()) {
                PickerHeading("The usual ones")
                suggested.forEach { (pkg, label) -> PickerRow(pkg, label, onPick) }
                Spacer(Modifier.height(Space.md))
            }
            if (rest.isNotEmpty()) {
                if (suggested.isNotEmpty()) PickerHeading("Everything else")
                rest.forEach { (pkg, label) -> PickerRow(pkg, label, onPick) }
            }
            if (matching.isEmpty()) {
                Text(
                    if (query.isBlank()) "Every app you have is already guarded."
                    else "No app matches \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun PickerHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = BastionColors.TextSecondary,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(Space.sm))
}

@Composable
private fun PickerRow(
    pkg: String,
    label: String,
    onPick: (String, String, BlockMode) -> Unit,
) {
    val suggested = GuardRepository.SUGGESTED_PACKAGES.firstOrNull { it.first == pkg }?.second
        ?: BlockMode.FEED_ONLY
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .clickable { onPick(pkg, label, suggested) }
            .padding(vertical = Space.sm, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(pkg)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = BastionColors.TextPrimary)
            Text(
                suggested.outcome(),
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextTertiary,
            )
        }
    }
}

// --- shared bits -------------------------------------------------------------

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 60.dp)
            .height(1.dp)
            .background(BastionColors.OutlineSoft)
    )
}

/** An amber line with the repair attached, for a link in the chain that is broken. */
@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .background(BastionColors.AmberSoft.copy(alpha = 0.35f))
            .border(1.dp, BastionColors.Amber, RoundedCornerShape(Space.sm))
            .padding(Space.md),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = BastionColors.TextPrimary)
        Spacer(Modifier.height(Space.xs))
        LinkButton(action, BastionColors.Amber, onAction)
    }
}

@Composable
private fun switchColorsFor() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = BastionColors.MidnightDeep,
    checkedTrackColor = BastionColors.Sage,
    uncheckedThumbColor = BastionColors.TextTertiary,
    uncheckedTrackColor = BastionColors.Surface,
    uncheckedBorderColor = BastionColors.OutlineStrong,
)
