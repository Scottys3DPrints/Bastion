package com.bastion.app.feature.guardui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.LockedInNote
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.db.BlockMode
import com.bastion.app.data.db.FeedRuleEntity
import com.bastion.app.data.db.GuardedAppEntity
import com.bastion.app.data.db.MatchType

/**
 * One card per app, and one question per card.
 *
 * ## What this replaces, and why
 *
 * Blocking Instagram's reels used to require four things to be true at once,
 * each set in a different place: Guard running, Instagram added to "Guarded
 * apps", its mode set to "Block only the endless feed", and a rule switched on
 * in a second list called "Which feeds get closed". Get three of the four right
 * and nothing happens — no error, no hint, just an app that opens normally
 * while the screen shows a page of switches that all look on.
 *
 * Every part of that was individually defensible. Guarded apps answered "what
 * may Bastion touch", the mode answered "how much", the rules answered "which
 * screens count". Together they asked a man to assemble a block out of parts
 * and gave him no way to see whether he had.
 *
 * So the parts are gone as *choices*. A card asks one thing — what happens in
 * this app — and setting the answer sets everything the answer needs. Choosing
 * "just the feed" guards the app, sets the mode, and switches its rules on, in
 * one tap, because those were never three decisions. They were one decision
 * wearing three switches.
 *
 * What survives, behind a disclosure, is the part that is genuinely a detail:
 * *which* screens count as the feed. That is a repair tool for when detection
 * is wrong, not a step in setting anything up, and it now reads like one.
 */

// --- the one question --------------------------------------------------------

/**
 * What a man is choosing, in his words rather than the database's.
 *
 * [BlockMode] is the storage; this is the question. They are deliberately not
 * the same type: the modes are a technical vocabulary that grew a value at a
 * time, and "FEED_ONLY plus four enabled rules" is a state the storage can
 * express and a person cannot choose.
 */
enum class ProtectionLevel {
    NOTHING,
    FEED,
    HOURS,
    LIMIT,
    EVERYTHING,
}

/** The heading on the option, and on the card once it is chosen. */
internal fun ProtectionLevel.title(): String = when (this) {
    ProtectionLevel.NOTHING -> "Not blocked"
    ProtectionLevel.FEED -> "Just the endless feed"
    ProtectionLevel.HOURS -> "During certain hours"
    ProtectionLevel.LIMIT -> "After a daily limit"
    ProtectionLevel.EVERYTHING -> "The whole app"
}

/** What actually happens, which is the only thing worth reading twice. */
internal fun ProtectionLevel.consequence(): String = when (this) {
    ProtectionLevel.NOTHING -> "Opens normally. Bastion leaves it alone."
    ProtectionLevel.FEED ->
        "Reels, Shorts and For You close as they open — in the app and in any " +
            "browser. Messages, search and posting still work."
    ProtectionLevel.HOURS -> "Won't open during the hours you set."
    ProtectionLevel.LIMIT -> "Closes once the day's minutes are used."
    ProtectionLevel.EVERYTHING -> "Won't open at all, and its site is closed in browsers."
}

/** Storage, from the question. */
internal fun ProtectionLevel.toMode(): BlockMode? = when (this) {
    ProtectionLevel.NOTHING -> null
    ProtectionLevel.FEED -> BlockMode.FEED_ONLY
    ProtectionLevel.HOURS -> BlockMode.SCHEDULE
    ProtectionLevel.LIMIT -> BlockMode.TIME_LIMIT
    ProtectionLevel.EVERYTHING -> BlockMode.FULL
}

/** The question, from storage. */
internal fun levelOf(app: GuardedAppEntity?): ProtectionLevel = when (app?.mode) {
    null -> ProtectionLevel.NOTHING
    BlockMode.FEED_ONLY -> ProtectionLevel.FEED
    BlockMode.SCHEDULE -> ProtectionLevel.HOURS
    BlockMode.TIME_LIMIT -> ProtectionLevel.LIMIT
    BlockMode.FULL -> ProtectionLevel.EVERYTHING
}

/**
 * Which levels are offered, in the order a man weighs them.
 *
 * NOTHING is absent on purpose: it is not a level, it is leaving, and it lives
 * at the bottom of the sheet as its own quiet action so it cannot be picked by
 * a thumb aiming at the row above.
 */
internal val OFFERED_LEVELS = listOf(
    ProtectionLevel.FEED,
    ProtectionLevel.HOURS,
    ProtectionLevel.LIMIT,
    ProtectionLevel.EVERYTHING,
)

/** One app, with everything known about it gathered in one place. */
data class ProtectedApp(
    val packageName: String,
    val label: String,
    val guarded: GuardedAppEntity?,
    val rules: List<FeedRuleEntity>,
) {
    val level: ProtectionLevel get() = levelOf(guarded)
}

// --- the list ----------------------------------------------------------------

@Composable
fun ProtectionSection(
    apps: List<ProtectedApp>,
    guardRunning: Boolean,
    lockedInDelay: String?,
    onAdd: () -> Unit,
    onOpen: (ProtectedApp) -> Unit,
    onTurnGuardOn: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("What's blocked")
        LinkButton("Add an app") { onAdd() }
    }
    Spacer(Modifier.height(Space.sm))
    Text(
        "One card per app. Tap it to choose what happens there.",
        style = MaterialTheme.typography.bodySmall,
        color = BastionColors.TextTertiary,
    )
    Spacer(Modifier.height(Space.md))

    // The one broken link that belongs above the list rather than inside every
    // row: with the service down, nothing here is enforced regardless of what
    // any card says.
    if (!guardRunning && apps.isNotEmpty()) {
        Notice(
            "Guard is switched off, so none of this is being enforced.",
            action = "Turn Guard on",
            onAction = onTurnGuardOn,
        )
        Spacer(Modifier.height(Space.md))
    }

    if (apps.isEmpty()) {
        Text(
            "Nothing blocked yet, so nothing is being stopped. Instagram and " +
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
            AppCard(app, guardRunning, lockedInDelay) { onOpen(app) }
        }
    }
}

@Composable
private fun AppCard(
    app: ProtectedApp,
    guardRunning: Boolean,
    lockedInDelay: String?,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, size = 36.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                color = BastionColors.TextPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                app.level.title(),
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.SageBright,
            )
            // Said on the row only when something is wrong with this app in
            // particular. A row that is simply working says what it does and
            // stops talking.
            trouble(app, guardRunning)?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.Amber,
                )
            }
            lockedInDelay?.let {
                Spacer(Modifier.height(2.dp))
                LockedInNote(it, what = "loosening this")
            }
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = BastionColors.TextTertiary)
    }
}

/**
 * The one line that says a card is not doing what it claims.
 *
 * Reuses [ruleState] rather than re-deriving it, so the card and the detail
 * sheet can never disagree about whether something is working.
 */
internal fun trouble(app: ProtectedApp, guardRunning: Boolean): String? {
    // An app Bastion has no feed rules for, asked to block only its feed.
    //
    // Says the real reason rather than the nearest one. Chrome and Messenger
    // were guarded back when browsers had rule groups of their own; those moved
    // to the sites they belong to, and what is left is an entry that cannot do
    // anything in this mode and never will. "Nothing is switched on inside
    // this" sent a man looking for a switch that does not exist.
    if (app.level == ProtectionLevel.FEED && app.rules.isEmpty()) {
        return "Bastion has no feed to recognise in this app. Its feeds in a " +
            "browser are covered by the site's own card."
    }
    val state = ruleState(
        guardRunning = guardRunning,
        guardedMode = app.guarded?.mode,
        anyRuleEnabled = app.rules.any { it.enabled },
        hasBrowserRules = app.rules.any { it.enabled && it.matchType != MatchType.VIEW_ID },
    )
    return when {
        state is RuleState.AllOff ->
            "Nothing is switched on inside this, so it opens normally."
        else -> null
    }
}

// --- the sheet ---------------------------------------------------------------

@Composable
fun AppProtectionSheet(
    app: ProtectedApp,
    lockedInDelay: String?,
    onSetLevel: (ProtectionLevel) -> Unit,
    onSetRule: (FeedRuleEntity, Boolean) -> Unit,
    onRemove: () -> Unit,
    onLearn: () -> Unit,
) {
    var showRules by remember(app.packageName) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, size = 40.dp)
            Spacer(Modifier.width(Space.md))
            Text(
                app.label,
                style = MaterialTheme.typography.headlineSmall,
                color = BastionColors.TextPrimary,
            )
        }
        Spacer(Modifier.height(Space.lg))
        SectionLabel("What happens here")
        Spacer(Modifier.height(Space.sm))

        OFFERED_LEVELS.forEach { level ->
            LevelRow(level, chosen = app.level == level) { onSetLevel(level) }
            Spacer(Modifier.height(Space.sm))
        }

        lockedInDelay?.let {
            Spacer(Modifier.height(Space.xs))
            LockedInNote(it, what = "loosening this, or stopping it,")
        }

        // The repair tool, and it reads like one.
        //
        // These were a whole second section of the screen, presented as
        // something to set up. They are not: they are how the feed is
        // recognised, and the only reason to open them is that recognition has
        // gone wrong — Instagram moved an id, or something is being closed that
        // should not be.
        if (app.rules.isNotEmpty()) {
            Spacer(Modifier.height(Space.lg))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showRules = !showRules }
                    .padding(vertical = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "What counts as the feed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                Text(
                    if (showRules) "Hide" else "Show",
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.BronzeBright,
                )
            }
            androidx.compose.animation.AnimatedVisibility(showRules) {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "Only worth touching if something is being closed that " +
                            "shouldn't be, or the opposite.",
                        style = MaterialTheme.typography.labelSmall,
                        color = BastionColors.TextTertiary,
                    )
                    Spacer(Modifier.height(Space.sm))
                    app.rules.forEach { rule ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = Space.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                rule.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = BastionColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { onSetRule(rule, it) },
                                colors = switchColorsFor(),
                            )
                        }
                    }
                    Spacer(Modifier.height(Space.sm))
                    LinkButton("A feed is getting through", BastionColors.BronzeBright, onLearn)
                }
            }
        }

        Spacer(Modifier.height(Space.lg))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LinkButton("Stop blocking ${app.label}", BastionColors.TextTertiary, onRemove)
        }
        Spacer(Modifier.height(Space.md))
    }
}

@Composable
private fun LevelRow(level: ProtectionLevel, chosen: Boolean, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.md))
            .background(if (chosen) BastionColors.SurfaceRaised else BastionColors.Surface)
            .clickable(onClick = onPick)
            .padding(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                level.title(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (chosen) BastionColors.BronzeBright else BastionColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (chosen) {
                Text(
                    "Chosen",
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.BronzeBright,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            level.consequence(),
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
        )
    }
}
