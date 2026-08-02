package com.bastion.app.core.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The one text action.
 *
 * There were four private copies of this — one each in Guard, Grow, Brotherhood
 * and the Mentor — plus bare `Modifier.clickable` text in several more places.
 * The copies had drifted apart in padding and colour, and the bare clickables
 * had no button semantics and no 48dp target at all, which matters in an app
 * used one-handed under stress.
 */
@Composable
fun LinkButton(
    label: String,
    color: Color = BastionColors.BronzeBright,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Pick one of a few — the Grow tabs, Faith/Discipline, a guarded app's mode.
 *
 * These were three different custom chip styles, each hand-built from a Box, a
 * border and a clickable, each with its own corner radius and padding. This is
 * the platform's control for the job; using it means the selection behaves and
 * reads the way it does everywhere else on the phone.
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Four options fill the width; five or more scroll instead.
    //
    // A segmented row divides the width evenly and clips whatever does not
    // fit, so a fifth option turned "Challenges" into "Challeng" and "Becoming"
    // into "Becomin" — text that reads as a rendering fault rather than as a
    // label that ran out of room. Scrolling keeps the standard control and the
    // whole word.
    val scrolls = options.size > 4
    SingleChoiceSegmentedButtonRow(
        if (scrolls) modifier.horizontalScroll(rememberScrollState()) else modifier
    ) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = BastionColors.BronzeDeep,
                    activeContentColor = BastionColors.BronzeBright,
                    activeBorderColor = BastionColors.Bronze,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = BastionColors.TextMuted,
                    inactiveBorderColor = BastionColors.Outline,
                ),
                // No checkmark: the labels are short and the fill already says
                // which is chosen, and the tick pushed longer labels to ellipsis.
                icon = {},
            ) {
                // The label's size is capped, exactly as the navigation bar's
                // is, and for the same reason: a segment is a fixed fraction of
                // the screen whatever the font scale. Scrolling past four fixed
                // the count problem but not the scale one — at 2x, three
                // options were enough to render "Challenges" as "Challeng",
                // which reads as a broken app rather than as text that did not
                // fit. Smaller scales are still honoured; only the top is held.
                val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                val capped = 12f * (minOf(scale, MAX_SEGMENT_FONT_SCALE) / scale)
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = androidx.compose.ui.unit.TextUnit(
                            capped,
                            androidx.compose.ui.unit.TextUnitType.Sp,
                        ),
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Beyond this a segment's label stops growing; see [ChoiceRow]. */
private const val MAX_SEGMENT_FONT_SCALE = 1.3f

/** Multi-select — the trigger list. The platform control for "pick any of these". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BastionFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = BastionColors.TextSecondary,
            selectedContainerColor = BastionColors.BronzeDeep,
            selectedLabelColor = BastionColors.BronzeBright,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = BastionColors.Outline,
            selectedBorderColor = BastionColors.Bronze,
        ),
    )
}
