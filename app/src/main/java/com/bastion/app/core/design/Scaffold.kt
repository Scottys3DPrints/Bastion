package com.bastion.app.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one spacing scale.
 *
 * Every screen used to pick its own numbers — 44dp of top padding here, 52dp
 * there, gaps of 6, 8, 10, 12, 14, 18, 20 and 22 between blocks on a single
 * screen. No one notices any individual value, and everyone feels the result:
 * an app where nothing lines up with anything, which reads as unfinished even
 * when each screen is carefully made.
 */
object Space {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp

    /** The gap between two sections. The only vertical rhythm on a screen. */
    val section: Dp = 24.dp
    val xl: Dp = 32.dp

    /** Horizontal screen margin, identical everywhere. */
    val gutter: Dp = 20.dp
}

/**
 * The single screen anatomy, used by every tab.
 *
 * Before this, each screen invented its own chrome: the home screen opened with
 * a greeting and a floating gear and no title, Track opened straight into a
 * ring, Guard opened with a large heading. Nothing repeated, so the app never
 * taught anyone its own grammar — which is most of why it felt like several
 * apps sharing a colour palette.
 *
 * What it enforces, deliberately:
 *
 *  - **One title**, left-aligned in a real top bar, with at most one trailing
 *    action. The thematic name lives here ("Watchtower"), the plain word on the
 *    tab, so the poetry survives without a navigation label having to carry it.
 *  - **One rhythm.** The content column spaces its children by [Space.section]
 *    itself, so screens cannot drift by hand-placing Spacers between blocks.
 *    Add a child, get the right gap.
 *  - **One place for the primary action** — [floatingAction], bottom-right, or
 *    nothing at all. A screen that is status and configuration (Guard) is
 *    allowed to have none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BastionScaffold(
    title: String,
    modifier: Modifier = Modifier,
    dawnIntensity: Float = 1f,
    /** Present on pushed routes; absent on the four tabs, which have nowhere back to. */
    onBack: (() -> Unit)? = null,
    /** The single trailing top-bar action. */
    action: @Composable (() -> Unit)? = null,
    floatingAction: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DawnBackground(intensity = dawnIntensity) {
        Box(Modifier.fillMaxSize()) {
            Column(modifier.fillMaxSize()) {
                BastionTopBar(title = title, onBack = onBack, action = action)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Space.gutter)
                        // Bottom room for the floating action, which overlays
                        // this column rather than displacing it.
                        .padding(bottom = if (floatingAction != null) 140.dp else Space.xl),
                    verticalArrangement = Arrangement.spacedBy(Space.section),
                    content = content,
                )
            }

            floatingAction?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Space.gutter),
                ) { it() }
            }
        }
    }
}

/**
 * The header, on its own, for screens whose body cannot be a scrolling column.
 *
 * [BastionScaffold] is the right shape for almost everything, but it owns its
 * body — one scrolling column with a fixed rhythm — and a chat is the one thing
 * that cannot live inside that. The Mentor needs a list that fills the space
 * left over and a composer pinned beneath it, which a `verticalScroll` parent
 * cannot give a child of unbounded height.
 *
 * So the header comes out rather than the Mentor keeping a hand-rolled copy of
 * it. What makes a screen feel like part of the app is the chrome at the top;
 * the body underneath is free to be whatever the content actually is.
 *
 * [subtitle] is for the one screen that has something true to say under its
 * name, and is not an invitation to start writing paragraphs into headers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BastionTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = BastionColors.TextMuted,
    onBack: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    color = BastionColors.TextPrimary,
                )
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = subtitleColor)
                }
            }
        },
        navigationIcon = {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = BastionColors.TextSecondary,
                    )
                }
            }
        },
        actions = { action?.invoke() },
        colors = TopAppBarDefaults.topAppBarColors(
            // Transparent so the dawn gradient runs unbroken behind it. A
            // surface-coloured bar cut the sky off at the top of every screen.
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
    )
}

/**
 * A borderless group: a label, then its content.
 *
 * The default container, and the fix for "everything is a card". Track stacked
 * ten bordered surfaces — a hero, a metric row, a calendar, an action row, two
 * charts, two insights and six benefits — and when every block has equal weight
 * and its own box, the eye has no hierarchy to rest on. A section separates by
 * whitespace instead, which costs nothing and reads as calm.
 *
 * [BastionCard] survives for the things that genuinely need to feel like an
 * object: a hero, or one highlighted call to action.
 */
@Composable
fun Section(
    label: String? = null,
    modifier: Modifier = Modifier,
    /**
     * One line under the heading saying what the group is *for*.
     *
     * A heading names a group; it does not explain why a man would open it.
     * "How hard it is to undo" tells him what the controls below adjust and
     * nothing about whether he wants them adjusted — and a screen full of
     * headings like that reads as settings to skip past rather than decisions
     * to make. The purpose belongs at the top, once, not repeated inside every
     * card underneath.
     */
    description: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    /**
     * The gap between things *inside* the section, which is not the gap between
     * sections and was the whole problem.
     *
     * The scaffold spaces its own children by [Space.section], and a screen that
     * emits a heading and then four cards as five separate children gets that
     * 24dp everywhere: between the heading and the thing it labels, and between
     * every card in the group. Nothing then reads as belonging to anything, and
     * the screen looks like a list of unrelated slabs — which is exactly the
     * complaint. One section is one child, with its own tighter rhythm inside.
     */
    spacing: Dp = Space.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (label != null || trailing != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                label?.let { SectionLabel(it) }
                trailing?.invoke()
            }
            // Closer to its content than to the section above it. A heading
            // equidistant from both belongs to neither.
            Spacer(Modifier.height(Space.sm))
        }
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.md))
        }
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/**
 * What switching this particular thing off will cost, next to the switch.
 *
 * The cooling-off card already says the rule in general — "removing any
 * protection has to wait" — and general is exactly the wrong place for it. A
 * man reads that once during setup, and meets the consequence weeks later at
 * the one control he happens to be reaching for, with no warning attached to
 * the thing under his thumb. He flips it, nothing appears to happen, and the
 * app looks broken at the moment it is working hardest.
 *
 * So the price is printed where the decision is made. Only where it is true:
 * anything that still switches off immediately says nothing, because a note
 * that is right four times out of five is worse than none at all.
 *
 * @param delay already worded — "24 hours", "12 hours" — because the formatter
 *   lives beside the rules that use it and this file must not reach into the
 *   data layer to find it.
 */
@Composable
fun LockedInNote(
    delay: String,
    modifier: Modifier = Modifier,
    what: String = "Switching this off",
) {
    Text(
        "Locked in — $what takes $delay.",
        style = MaterialTheme.typography.labelSmall,
        color = BastionColors.BronzeBright,
        modifier = modifier,
    )
}

/**
 * One list row, used for every repeated thing: guarded apps, feed rules,
 * habits, benefits, check-ins, settings entries.
 *
 * There were five or six bespoke row layouts doing this, each with its own
 * padding and its own idea of where the trailing control sits. One spec means a
 * user learns the pattern once.
 */
@Composable
fun BastionRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    titleColor: Color = BastionColors.TextPrimary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick == null) Modifier
                // Role.Button rather than a bare clickable: a tappable row that
                // announces itself as plain text is invisible to a screen
                // reader and gets no ripple.
                else Modifier.clickable(role = Role.Button, onClick = onClick)
            )
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            it()
            Spacer(Modifier.size(Space.md))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                    // A subtitle is the supporting line, not the content. Some
                    // of them are a full sentence about why a habit is worth
                    // keeping, and at a 2x font scale that ran to seven lines —
                    // three habits filled the screen and the list stopped being
                    // a list. Bounded here rather than at each call site, so no
                    // future row can reintroduce it.
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.size(Space.md))
            it()
        }
    }
}

/**
 * One bottom sheet.
 *
 * The urge sheet, the recovery flow, the app picker and learn mode each laid
 * themselves out slightly differently — different horizontal padding, different
 * bottom inset, different title treatment — so the same gesture produced four
 * subtly different surfaces. One wrapper means a sheet is a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BastionBottomSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
        ),
        containerColor = BastionColors.Surface,
    ) {
        Column(
            Modifier
                // Scrollable, because a sheet taller than the screen is not a
                // rare case here — it is the logging flow, which is five steps
                // with a day picker and twelve hour chips on the first one.
                //
                // Without this the overflow was simply unreachable: the sheet
                // grew to the screen and stopped, and everything past the fold
                // was gone. What sits at the bottom of these sheets is always
                // the buttons, so the failure was never cosmetic — the flow
                // could be opened and not finished, and the way out was to
                // dismiss and lose the answers.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter)
                // Clears the gesture bar; sheets that stop at their own content
                // put the last button under the system navigation.
                .padding(bottom = Space.xl),
        ) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(Space.lg))
            }
            content()
        }
    }
}

/**
 * One empty state everywhere: a line saying what would be here, and the one
 * action that fills it.
 *
 * Ad-hoc "Nothing yet" strings were scattered across Progress, Grow, Partner
 * and the guarded-app list, each phrased differently and none of them offering
 * a way forward. An empty screen is the moment a user most needs telling what
 * to do next.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = Space.section),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = BastionColors.Outline)
            Spacer(Modifier.height(Space.md))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextMuted,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Space.sm))
            TextButton(onClick = onAction, contentPadding = PaddingValues(Space.sm)) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = BastionColors.BronzeBright,
                )
            }
        }
    }
}

/**
 * A titled group of related controls, on one shared surface.
 *
 * [Section] separates by whitespace alone, which is right when the things
 * either side of the gap look different anyway — a chart, then a calendar. It
 * is not enough on a settings screen, where every row is a label and a switch:
 * the eye finds no edges, twenty rows read as one undifferentiated list, and
 * the user cannot tell where "notifications" stopped and "backups" began.
 *
 * So a group gets a real container. One surface for the whole group rather than
 * one per row — the boundary is what needs drawing, not each item inside it —
 * which keeps the bordered-surface count low while making the grouping
 * unmissable.
 *
 * [subtitle] is for the sentence that says what the group is *for*, in the
 * words the user would use. A heading alone names a thing; the line under it is
 * what stops a stranger having to guess.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionLabel(title)
        subtitle?.let {
            Spacer(Modifier.height(Space.xs))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
        }
        Spacer(Modifier.height(Space.md))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(BastionColors.Surface.copy(alpha = 0.55f))
                .padding(horizontal = Space.lg, vertical = Space.xs),
            content = content,
        )
    }
}

/**
 * The line between two controls inside a [SettingsGroup].
 *
 * Inset from the left so it reads as separating siblings rather than cutting
 * the group in half.
 */
@Composable
fun GroupDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BastionColors.OutlineSoft)
    )
}

/**
 * One tap for the explanation, for the things that cannot be said in a line.
 *
 * The alternative is either a wall of text nobody reads or a technical term
 * nobody understands, and this app has done both. Collapsed by default so the
 * screen stays short; there for the person who wants to know what "Private DNS"
 * actually means before they touch it.
 */
/**
 * A placeholder the size of the thing that is coming.
 *
 * Today's word and the daily brief are read from assets after the first frame,
 * so the screen drew itself, then grew two blocks and shoved everything below
 * them down the page. That reflow is the single thing that most made a
 * carefully-built app read as unfinished — and on a slow morning it moved the
 * habit rings out from under a thumb that was already on its way down.
 *
 * Deliberately still. A shimmer would be motion for its own sake on a screen
 * whose whole argument is that nothing here moves unless it earned it.
 */
@Composable
fun Skeleton(lines: Int = 2, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        repeat(lines) { index ->
            Spacer(
                Modifier
                    // The last line short, the way a paragraph ends.
                    .fillMaxWidth(if (index == lines - 1) 0.55f else 1f)
                    .height(Space.lg)
                    .clip(RoundedCornerShape(Space.xs))
                    .background(BastionColors.Surface.copy(alpha = 0.5f))
            )
            if (index != lines - 1) Spacer(Modifier.height(Space.sm))
        }
    }
}

/**
 * The things most people should never see, folded away by default.
 *
 * [WhatsThis] is for a sentence a curious person might want. This is for the
 * genuinely technical: an `adb shell` string, a DNS hostname, the mechanics of
 * how a lock is enforced. That material has to stay reachable — a man who has
 * locked himself in deserves to be able to read exactly what he agreed to — but
 * putting it inline made the most important screen in the app read like a
 * terminal, and taught everyone else to scroll past the part that matters.
 *
 * Collapsed, it costs one line. Open, nothing is hidden.
 */
@Composable
fun Advanced(
    modifier: Modifier = Modifier,
    label: String = "Advanced",
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(vertical = Space.sm),
        ) {
            Text(
                if (open) "$label ▴" else "$label ▾",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.TextMuted,
            )
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Column(content = content)
        }
    }
}

@Composable
fun WhatsThis(text: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(vertical = Space.sm),
        ) {
            Text(
                if (open) "Hide" else "What's this?",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.SageBright,
            )
        }
        androidx.compose.animation.AnimatedVisibility(open) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextSecondary,
                modifier = Modifier.padding(bottom = Space.sm),
            )
        }
    }
}
