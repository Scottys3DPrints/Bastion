package com.bastion.app.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.domain.Rank
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rank-up ceremony.
 *
 * This is the emotional payoff of the entire "rank over streak" model, and it
 * used to be a number that silently incremented. Crossing a threshold is the one
 * moment the app should stop and make something of itself.
 *
 * Still calm, though — forged metal catching light, not confetti. The whole tone
 * of Bastion is dignity rather than hype, and a celebration that shouts would
 * belong to a different app.
 */
@Composable
fun RankUpCeremony(
    rank: Rank,
    faithMode: Boolean,
    onDismiss: () -> Unit,
) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(rank) {
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BastionColors.MidnightDeep.copy(alpha = 0.97f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(220.dp)
                        .scale(0.85f + 0.15f * reveal.value)
                        .alpha(reveal.value),
                    contentAlignment = Alignment.Center,
                ) {
                    ForgedMedallion()
                    Text(
                        "◇",
                        style = MaterialTheme.typography.displayLarge,
                        color = BastionColors.BronzeBright,
                    )
                }

                Spacer(Modifier.height(36.dp))
                Box(Modifier.alpha(reveal.value)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionLabel("Rank ${rank.tier}", color = BastionColors.TextMuted)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            rank.displayName(faithMode),
                            style = MaterialTheme.typography.displayMedium,
                            color = BastionColors.BronzeBright,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            // Names what earned it. Rank comes from everything he
                            // has built, not from an unbroken run, and this is the
                            // moment that distinction is most worth restating.
                            "Earned by everything you've built — not by a streak you have to protect.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BastionColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(40.dp))
                PrimaryButton(
                    text = "Keep going",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(reveal.value),
                )
            }
        }
    }
}

/** Slowly rotating rays: metal catching light, not a firework. */
@Composable
private fun ForgedMedallion() {
    val transition = rememberInfiniteTransition(label = "forge")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Canvas(Modifier.fillMaxSize()) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    BastionColors.Bronze.copy(alpha = 0.30f),
                    BastionColors.Bronze.copy(alpha = 0f),
                ),
                center = centre,
                radius = radius * pulse,
            ),
            radius = radius * pulse,
        )

        repeat(12) { i ->
            val angle = Math.toRadians((sweep + i * 30f).toDouble())
            val inner = radius * 0.62f
            val outer = radius * 0.92f
            drawLine(
                color = BastionColors.Bronze.copy(alpha = 0.22f),
                start = Offset(
                    centre.x + (cos(angle) * inner).toFloat(),
                    centre.y + (sin(angle) * inner).toFloat(),
                ),
                end = Offset(
                    centre.x + (cos(angle) * outer).toFloat(),
                    centre.y + (sin(angle) * outer).toFloat(),
                ),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        drawCircle(
            color = BastionColors.Bronze,
            radius = radius * 0.58f,
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}
