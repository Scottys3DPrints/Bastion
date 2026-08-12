package com.bastion.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.BastionColors
import com.bastion.app.domain.Rank
import com.bastion.app.domain.RankPoints

/**
 * What the medallion means, in the one place a man will look for it.
 *
 * The medallion showed a name, a tier number and an arc filling towards
 * something it never named. Both numbers behind it — the points earned and the
 * points still to go — were computed on every emission and rendered nowhere,
 * so the arc was an animation rather than a measure, and the only way to learn
 * what moved it was to guess.
 *
 * That is worse than a missing feature. A progress bar with no units invites a
 * man to invent a rule for it, and the rule he invents will be "days", because
 * days are the number the rest of the screen shows him. Then he slips, his
 * streak goes to zero, his rank does not move, and the app looks broken at the
 * exact moment it was doing the one thing it was designed to do.
 *
 * So this says the quiet part: rank is cumulative, it never falls, and here is
 * the whole ladder with the rung you are standing on marked.
 */
@Composable
fun RankSheet(
    points: Int,
    rank: Rank,
    faithMode: Boolean,
) {
    val next = Rank.next(rank)
    val toNext = Rank.pointsToNext(points)

    Column(Modifier.fillMaxWidth()) {
        SectionLabel("How rank works")
        Spacer(Modifier.height(Space.md))
        Text(
            // The promise, stated before the mechanism. This is the sentence
            // the whole model exists to make true, and a man who has just
            // slipped needs it in the first line rather than the fourth.
            "Rank counts everything you have built, and it never falls. A slip " +
                "restarts the streak above — it takes nothing off your rank.",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )
        Spacer(Modifier.height(Space.lg))

        Text(
            "$points points",
            style = MaterialTheme.typography.headlineSmall,
            color = BastionColors.BronzeBright,
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            when {
                next == null || toNext == null ->
                    "You are at the highest rank there is."
                else ->
                    "$toNext more to ${next.displayName(faithMode)}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.TextSecondary,
        )

        Spacer(Modifier.height(Space.lg))
        SectionLabel("The ladder")
        Spacer(Modifier.height(Space.sm))
        Rank.entries.forEach { RankRow(it, rank, points, faithMode) }

        Spacer(Modifier.height(Space.lg))
        SectionLabel("What earns points")
        Spacer(Modifier.height(Space.sm))
        Text(
            // Named in full, because a scoring system a man cannot see is one
            // he cannot aim at — and the shape of this list is the argument:
            // most of the ways to earn are things you do, not days you avoid
            // something.
            "Every one of these adds up and none of them can be taken away.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextTertiary,
        )
        Spacer(Modifier.height(Space.sm))
        EARNINGS.forEach { (label, value) -> EarnRow(label, value) }

        Spacer(Modifier.height(Space.md))
        Text(
            "Logging a slip honestly earns points too. Not a reward for " +
                "slipping — a reward for the honesty that comes after it, which " +
                "is the part that predicts whether you get back up.",
            style = MaterialTheme.typography.bodySmall,
            color = BastionColors.TextTertiary,
        )
        Spacer(Modifier.height(Space.lg))
    }
}

@Composable
private fun RankRow(row: Rank, current: Rank, points: Int, faithMode: Boolean) {
    val reached = points >= row.threshold
    val here = row == current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .background(if (here) BastionColors.SurfaceRaised else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Reached rungs stay lit. The ladder is a record of where a man has
        // been, not a checklist that empties when he has a bad week.
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (reached) BastionColors.BronzeBright else BastionColors.TrackEmpty),
        )
        Spacer(Modifier.width(Space.md))
        Text(
            row.displayName(faithMode),
            style = MaterialTheme.typography.bodyMedium,
            color = if (reached) BastionColors.TextPrimary else BastionColors.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (here) "you are here" else "${row.threshold}",
            style = MaterialTheme.typography.labelSmall,
            color = if (here) BastionColors.BronzeBright else BastionColors.TextTertiary,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EarnRow(label: String, value: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextSecondary)
        Text(
            "+$value",
            style = MaterialTheme.typography.bodyMedium,
            color = BastionColors.SageBright,
        )
    }
}

/**
 * Read straight off [RankPoints] rather than written out again.
 *
 * A hand-typed table drifts the first time a value is tuned, and a scoring
 * screen that lies about the scoring is worse than no screen at all — it is the
 * one place a man goes specifically to find out whether the app is being
 * straight with him.
 */
private val EARNINGS = listOf(
    "A clean day" to RankPoints.CLEAN_DAY,
    "A check-in" to RankPoints.CHECK_IN,
    "Riding out an urge" to RankPoints.URGE_RESISTED,
    "Finishing a panic session" to RankPoints.PANIC_SESSION_COMPLETED,
    "A day of a challenge" to RankPoints.CHALLENGE_DAY,
    "Reading a lesson" to RankPoints.LESSON_READ,
    "Keeping a habit" to RankPoints.HABIT_COMPLETED,
    "Logging a slip honestly" to RankPoints.SLIP_LOGGED_HONESTLY,
)
