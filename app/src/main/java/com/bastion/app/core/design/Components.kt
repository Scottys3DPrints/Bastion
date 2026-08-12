package com.bastion.app.core.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding

/**
 * The dawn gradient that runs through the whole app. Every new day is a sunrise;
 * the horizon sits low so the screen stays dark where the eye rests.
 */
/**
 * How much dawn to show, from the actual hour.
 *
 * The metaphor was decorative while the intensity never changed. Tied to the
 * clock it becomes felt: a real sunrise in the morning, and near-black at 1am —
 * which is both when urges peak and when a bright screen is its own enemy.
 */
fun dawnIntensityForHour(hour: Int): Float = when (hour) {
    in 5..8 -> 1f          // sunrise: the fullest the horizon ever gets
    in 9..16 -> 0.55f      // daylight: present but quiet
    in 17..20 -> 0.75f     // dusk: warm again
    in 21..23 -> 0.3f      // late: banking down
    else -> 0.12f          // the small hours: almost nothing
}

@Composable
fun DawnBackground(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to BastionColors.MidnightDeep,
                    0.35f to BastionColors.DawnTop,
                    0.72f to lerpColor(BastionColors.DawnTop, BastionColors.DawnMid, intensity),
                    0.92f to lerpColor(BastionColors.DawnTop, BastionColors.DawnGlow, intensity * 0.75f),
                    1.0f to lerpColor(BastionColors.DawnTop, BastionColors.DawnHorizon, intensity * 0.35f),
                )
            )
    ) { content() }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * c,
        green = a.green + (b.green - a.green) * c,
        blue = a.blue + (b.blue - a.blue) * c,
        alpha = 1f,
    )
}

@Composable
fun BastionCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = BastionColors.Surface.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: BastionColors.OutlineSoft),
    ) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = BastionColors.TextMuted) {
    Text(text.uppercase(), style = OvertureStyle, color = color, modifier = modifier)
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // heightIn: 54dp is the minimum touch target, not a cap. At a 2x font
        // scale a two-word label needs more room than that, and a fixed height
        // clips it rather than growing to fit.
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BastionColors.Bronze,
            contentColor = BastionColors.MidnightDeep,
            disabledContainerColor = BastionColors.SurfaceHigh,
            disabledContentColor = BastionColors.TextMuted,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = BastionColors.TextSecondary,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BastionColors.Outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * The rank medallion. Forged metal, and it does not shatter — a slip dents
 * momentum, it never erases who you have become.
 */
@Composable
fun RankMedallion(
    rankName: String,
    rankTier: Int,
    progressToNext: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 132.dp,
    /**
     * Optional, because the widget draws this too and has nowhere to send a
     * tap. Where it is supplied, the medallion is the most natural thing on the
     * screen to press when a man wants to know what it means.
     */
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        val transition = rememberInfiniteTransition(label = "medallion")
        val shimmer by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
            label = "shimmer",
        )
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)

            drawArc(
                color = BastionColors.SurfaceHigh,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        BastionColors.BronzeDeep,
                        BastionColors.Bronze,
                        lerpColor(BastionColors.Bronze, BastionColors.BronzeBright, shimmer),
                        BastionColors.Bronze,
                        BastionColors.BronzeDeep,
                    )
                ),
                startAngle = -90f,
                sweepAngle = 360f * progressToNext.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SectionLabel("Rank $rankTier", color = BastionColors.TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(
                rankName,
                style = MaterialTheme.typography.headlineMedium,
                color = BastionColors.BronzeBright,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A single habit ring for the day's Regimen. */
@Composable
fun HabitRing(
    label: String,
    emoji: String,
    done: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // No fixed width: the caller gives each ring an equal share of the row, so
    // three habits get roomy labels and five still fit. A fixed 78dp was
    // breaking "Twenty-minute walk" mid-word.
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(if (done) BastionColors.SageDeep.copy(alpha = 0.45f) else BastionColors.SurfaceRaised)
                .border(
                    width = 2.dp,
                    color = if (done) BastionColors.Sage else BastionColors.Outline,
                    shape = CircleShape,
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (done) BastionColors.SageBright else BastionColors.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 2,
            // Without this a long habit name is chopped mid-word ("Twenty-minu"),
            // which reads as a rendering fault rather than a shortened label.
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Screen scaffold: dawn background plus system-bar insets, used by every screen. */
@Composable
fun BastionScreen(
    modifier: Modifier = Modifier,
    dawnIntensity: Float = 1f,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    DawnBackground(intensity = dawnIntensity) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Top,
            content = content,
        )
    }
}

@Composable
fun StatPill(value: String, label: String, accent: Color = BastionColors.TextPrimary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = BastionColors.TextMuted)
    }
}

@Composable
fun RowDivider() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BastionColors.OutlineSoft)
    )
}

@Suppress("unused")
@Composable
fun HorizontalSpace(width: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(width))

/**
 * A chip that is a chip, not a bordered box.
 *
 * ChoiceRow owns a whole row and takes an enum; this is the one for several
 * small independent groups — weekday letters, delay lengths, schedule types.
 *
 * It lived in the habits package until a second feature needed it, which is
 * the moment a shared control stops being a local detail. A cross-feature
 * import of a UI atom is how a design system quietly stops being one.
 */
@Composable
fun BastionChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Space.sm)
    Box(
        modifier
            // 40dp, up from 36. These sit four and seven to a row, so each one
            // is already narrow; short as well made them fiddly to hit.
            .height(40.dp)
            .clip(shape)
            // Selected is a filled sage chip, not a slightly lighter grey one.
            // The old pair — SurfaceHigh against Surface — differ by 1.15:1,
            // which is to say they do not differ. On a row of seven weekday
            // chips there was no way to see which days were actually chosen.
            .background(if (selected) BastionColors.Sage else BastionColors.Surface)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else BastionColors.OutlineStrong,
                shape = shape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            // Dark on sage is 6.7:1; the light text that looks right in a mock
            // is 2.5:1 and unreadable in daylight.
            color = if (selected) BastionColors.MidnightDeep else BastionColors.TextSecondary,
        )
    }
}
