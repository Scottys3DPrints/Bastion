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
internal fun Divider() {
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
internal fun Notice(text: String, action: String, onAction: () -> Unit) {
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
internal fun switchColorsFor() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = BastionColors.MidnightDeep,
    checkedTrackColor = BastionColors.Sage,
    uncheckedThumbColor = BastionColors.TextTertiary,
    uncheckedTrackColor = BastionColors.Surface,
    uncheckedBorderColor = BastionColors.OutlineStrong,
)
