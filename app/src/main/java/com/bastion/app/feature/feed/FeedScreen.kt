package com.bastion.app.feature.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionScaffold
import com.bastion.app.core.design.LinkButton
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.ScriptureCompactStyle
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.MotivationItem
import com.bastion.app.data.prefs.Settings
import com.bastion.app.data.repo.FeedRepository
import kotlinx.coroutines.launch

/**
 * The Well.
 *
 * A feed to reach for instead of the one Guard took away — and the whole design
 * problem is keeping what makes a feed satisfying while removing what makes one
 * a trap. What is deliberately absent is the specification:
 *
 *  - **it ends.** A finite portion, then a real stopping cue that points
 *    outward. No regeneration, no "more like this".
 *  - **nothing moves on its own.** No autoplay, no video, no motion designed to
 *    catch the eye at the edge of the screen.
 *  - **no counts and nobody else.** Not a like total, not a streak leaderboard,
 *    not one word about what anyone else is doing.
 *  - **reading it cannot be failed.** There is no feed streak. It is a gift,
 *    not another thing to keep up.
 *
 * If a change here would make it stickier at the cost of someone's peace, it
 * does not belong. That restraint is the feature.
 */
@Composable
fun FeedScreen(onOpenProfile: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { BastionGraph.from(context) }
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())

    var cards by remember { mutableStateOf<List<FeedRepository.Card>>(emptyList()) }
    var extra by remember { mutableStateOf(0) }
    var dry by remember { mutableStateOf(false) }

    // Composed once per (mode, extra) rather than on every recomposition: a
    // feed that reshuffles under the thumb is the disorienting part of the
    // real ones.
    LaunchedEffect(settings.faithMode, extra) {
        cards = graph.feed.dailyFeed(settings, extra)
        dry = graph.feed.hasRunDry(settings)
        graph.feed.markServed(cards)
    }

    val hour = remember { java.time.LocalTime.now().hour }

    BastionScaffold(
        title = if (settings.faithMode) "Daily Bread" else "The Well",
        dawnIntensity = com.bastion.app.core.design.dawnIntensityForHour(hour),
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
        cards.forEach { card ->
            when (card) {
                is FeedRepository.Card.Words -> WordsCard(
                    item = card.item,
                    saved = card.item.id in settings.savedMotivation,
                    onSave = {
                        scope.launch { graph.settings.toggleSavedMotivation(card.item.id) }
                    },
                )

                is FeedRepository.Card.Progress -> ProgressCard(card)

                is FeedRepository.Card.Caught -> CaughtUpCard(
                    message = card.message,
                    ranDry = dry,
                    onMore = { extra += MORE_BATCH },
                    onDrawAgain = {
                        scope.launch {
                            graph.feed.drawAgain()
                            extra = 0
                            cards = graph.feed.dailyFeed(settings)
                            dry = false
                            graph.feed.markServed(cards)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One line, given room.
 *
 * Full-bleed and centred, the words in the display face over the dawn. One idea
 * per card and nothing else on it — which makes this the calmest surface in the
 * app, and the exact opposite of the feed it is standing in for.
 */
@Composable
private fun WordsCard(item: MotivationItem, saved: Boolean, onSave: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(accentFor(item.type).copy(alpha = 0.10f))
            .padding(horizontal = Space.lg, vertical = Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item.title?.let {
            SectionLabel(it, color = accentFor(item.type))
            Spacer(Modifier.height(Space.md))
        }
        Text(
            item.text,
            style = if (item.length == "long") MaterialTheme.typography.bodyLarge
            else ScriptureCompactStyle,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        item.credit()?.let {
            Spacer(Modifier.height(Space.md))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = accentFor(item.type),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(Space.md))
        // Saving is the only gesture, and it points inward at his own
        // collection rather than outward at a count. There is no number here
        // and never will be.
        LinkButton(
            if (saved) "Kept" else "Keep this",
            if (saved) BastionColors.BronzeBright else BastionColors.TextMuted,
            onSave,
        )
    }
}

/** His own days, as a card. The only number in the feed, and it is his. */
@Composable
private fun ProgressCard(card: FeedRepository.Card.Progress) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(BastionColors.BronzeDeep.copy(alpha = 0.22f))
            .padding(horizontal = Space.lg, vertical = Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            card.headline,
            style = MaterialTheme.typography.displaySmall,
            color = BastionColors.BronzeBright,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            card.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The end of the portion, and the most important card here.
 *
 * A normal feed never lets you arrive anywhere; this one has to. The message
 * points outward — go and live the day — and the way to carry on is a button
 * you have to choose rather than a scroll that never bottoms out. Offering
 * "more" at all is a compromise, and it is deliberately the quieter option.
 */
@Composable
private fun CaughtUpCard(
    message: String,
    ranDry: Boolean,
    onMore: () -> Unit,
    onDrawAgain: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("◇", style = MaterialTheme.typography.displaySmall, color = BastionColors.BronzeDeep)
        Spacer(Modifier.height(Space.lg))
        Text(
            message,
            style = MaterialTheme.typography.titleMedium,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            "That's a good amount for today. The rest will keep.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.section))
        if (ranDry) {
            QuietButton("Draw from it again", onDrawAgain, Modifier.fillMaxWidth())
        } else {
            QuietButton("A few more", onMore, Modifier.fillMaxWidth(), BastionColors.TextMuted)
        }
    }
}

/** A quiet colour per type, so the stream has variety without shouting. */
private fun accentFor(type: String): Color = when (type) {
    "scripture", "prayer" -> BastionColors.BronzeBright
    "reframe", "urge_line" -> BastionColors.SageBright
    "affirmation" -> BastionColors.Bronze
    "story" -> BastionColors.SageBright
    else -> BastionColors.TextSecondary
}

/** Small enough that "a few more" stays a top-up rather than a second helping. */
private const val MORE_BATCH = 6
