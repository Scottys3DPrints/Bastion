package com.bastion.app.feature.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.DawnBackground
import com.bastion.app.core.design.EmptyState
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.ScriptureCompactStyle
import com.bastion.app.core.design.ScriptureStyle
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.content.MotivationItem
import com.bastion.app.data.prefs.Settings
import com.bastion.app.data.repo.FeedRepository
import kotlinx.coroutines.launch

/**
 * The Well — one line at a time, filling the screen.
 *
 * Built as a full-screen pager rather than a scrolling list, and the difference
 * is the whole point. A list invites the eye to run ahead: you skim, you see
 * four cards at once, none of them lands, and the gesture is the same
 * thumb-flick that a bad feed trained. One line filling the screen cannot be
 * skimmed. You either read it or you swipe past it, and swiping past is a
 * choice rather than a habit.
 *
 * The anti-feed rules are unchanged and are what this is for: the portion is
 * finite, the last page is a real ending that points outward, nothing moves on
 * its own, there are no counts and nobody else, and reading it cannot be
 * failed. The one thing a pager adds is a sense of how far in you are, carried
 * by a hairline rather than a number — not a score, just a promise that this
 * stops.
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
    // Distinct from "empty", which they were not before: an unloaded Well and an
    // exhausted one both rendered as a black screen, so the first thing a man
    // saw on opening the tab was something that looked broken.
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(settings.faithMode, extra) {
        cards = graph.feed.dailyFeed(settings, extra)
        dry = graph.feed.hasRunDry(settings)
        graph.feed.markServed(cards)
        loading = false
    }

    val hour = remember { java.time.LocalTime.now().hour }
    val pager = rememberPagerState { cards.size }

    DawnBackground(intensity = com.bastion.app.core.design.dawnIntensityForHour(hour)) {
        Box(Modifier.fillMaxSize()) {

            if (loading || cards.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = Space.section),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        // The same mark the app draws while it is starting up.
                        // One thing to look at beats a rectangle of nothing.
                        Text(
                            "◇",
                            style = MaterialTheme.typography.displayMedium,
                            color = BastionColors.BronzeDeep,
                        )
                    } else {
                        EmptyState(
                            "The Well is dry for today.",
                            actionLabel = "Draw from it again",
                            onAction = {
                                scope.launch {
                                    graph.feed.drawAgain()
                                    extra = 0
                                    loading = true
                                    cards = graph.feed.dailyFeed(settings)
                                    dry = false
                                    graph.feed.markServed(cards)
                                    loading = false
                                }
                            },
                        )
                    }
                }
            }

            VerticalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                // One page per gesture. A pager that flings through five at a
                // time would be the scrolling feed again with extra steps.
                pageSize = androidx.compose.foundation.pager.PageSize.Fill,
            ) { index ->
                val card = cards.getOrNull(index) ?: return@VerticalPager
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(horizontal = Space.section),
                    contentAlignment = Alignment.Center,
                ) {
                    when (card) {
                        is FeedRepository.Card.Words -> WordsPage(
                            item = card.item,
                            saved = card.item.id in settings.savedMotivation,
                            onSave = {
                                scope.launch { graph.settings.toggleSavedMotivation(card.item.id) }
                            },
                        )

                        is FeedRepository.Card.Progress -> ProgressPage(card)

                        is FeedRepository.Card.Caught -> CaughtUpPage(
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

            // Chrome, kept to the minimum a full-bleed page can carry: how far
            // in you are, and the way out to settings. Everything else would be
            // furniture in front of the words.
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The one tab with no name on it — a first-timer met a quote on
                // a black screen with no idea what he had opened or why. So the
                // Well introduces itself on the page where he arrives, and then
                // stops: a permanent title would be exactly the furniture this
                // screen was stripped of.
                androidx.compose.animation.AnimatedVisibility(
                    visible = pager.currentPage == 0,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    SectionLabel("The Well", color = BastionColors.TextMuted)
                }
                IconButton(onClick = onOpenProfile) {
                    Icon(
                        Icons.Filled.AccountCircle,
                        contentDescription = "You, partner and settings",
                        tint = BastionColors.TextMuted,
                    )
                }
            }

            if (cards.size > 1) {
                ProgressHairline(
                    fraction = (pager.currentPage + 1).toFloat() / cards.size,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.systemBars),
                )
            }

            if (cards.size > 1) {
                SwipeHint(
                    visible = pager.currentPage == 0,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(bottom = Space.lg),
                )
            }
        }
    }
}

/**
 * One line, the whole screen.
 *
 * Nothing here but the words, who said them, and the one gesture worth having.
 * The type is the display face at its most generous — this is the surface the
 * font was chosen for.
 */
@Composable
private fun WordsPage(item: MotivationItem, saved: Boolean, onSave: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item.title?.let {
            SectionLabel(it, color = BastionColors.BronzeBright)
            Spacer(Modifier.height(Space.lg))
        }
        Text(
            item.text,
            // A story is long enough that full display size would push it off
            // the page, so it steps down the ramp — but stays on Fraunces.
            // Dropping to the body face made the page look like it belonged to
            // a different app every time a story came up, which is a worse
            // problem than a long page.
            style = if (item.length == "long") ScriptureCompactStyle else ScriptureStyle,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        item.credit()?.let {
            Spacer(Modifier.height(Space.section))
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.BronzeBright,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(Space.xl))
        // A bookmark rather than a heart, and with no number beside it. What is
        // being built is his own collection, not a tally anyone can be pleased
        // or disappointed by.
        IconButton(onClick = onSave) {
            Icon(
                if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                contentDescription = if (saved) "Kept" else "Keep this",
                tint = if (saved) BastionColors.BronzeBright else BastionColors.TextMuted,
            )
        }
    }
}

/** His own days. The only number in the whole thing, and it is his. */
@Composable
private fun ProgressPage(card: FeedRepository.Card.Progress) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            card.headline,
            style = MaterialTheme.typography.displayMedium,
            color = BastionColors.BronzeBright,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            card.detail,
            style = MaterialTheme.typography.bodyLarge,
            color = BastionColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The last page, and the most important one.
 *
 * A normal feed never lets you arrive anywhere. This one ends on a page with
 * nowhere further to swipe, pointing outward — and continuing is a button you
 * have to reach for rather than a scroll that never bottoms out.
 */
@Composable
private fun CaughtUpPage(
    message: String,
    ranDry: Boolean,
    onMore: () -> Unit,
    onDrawAgain: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("◇", style = MaterialTheme.typography.displayMedium, color = BastionColors.BronzeDeep)
        Spacer(Modifier.height(Space.section))
        Text(
            message,
            style = MaterialTheme.typography.headlineMedium,
            color = BastionColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.md))
        Text(
            "That's a good amount for today. The rest will keep.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.xl))
        if (ranDry) {
            QuietButton("Draw from it again", onDrawAgain, Modifier.fillMaxWidth())
        } else {
            QuietButton("A few more", onMore, Modifier.fillMaxWidth(), BastionColors.TextMuted)
        }
    }
}

/**
 * How far in you are, without a number.
 *
 * A count — "4 / 16" — invites arithmetic. Twelve left, I can do twelve, and
 * reading turns into a task with a score attached. It also reads as a target,
 * and a man who stops at nine has failed at something he was never meant to be
 * graded on.
 *
 * A line that fills says the one true thing the count was there for — this ends
 * — and says it to the corner of the eye rather than to the part of him that
 * counts things.
 */
@Composable
private fun ProgressHairline(fraction: Float, modifier: Modifier = Modifier) {
    val eased by androidx.compose.animation.core.animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(450),
        label = "wellProgress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(BastionColors.Outline.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(eased)
                .background(BastionColors.BronzeDeep)
        )
    }
}

/**
 * A hint on the first page only, then gone.
 *
 * A full-bleed page with no visible list gives no clue that there is anything
 * below it, and a man who does not know to swipe just sees one quote and
 * leaves. Shown once, at the start, and never again — a permanent nudge would
 * be the app pushing rather than offering.
 */
@Composable
private fun SwipeHint(visible: Boolean, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Swipe up",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.xs))
            Box(
                Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(BastionColors.Outline)
            )
        }
    }
}

/** Small enough that "a few more" stays a top-up rather than a second helping. */
private const val MORE_BATCH = 6
