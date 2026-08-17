package com.bastion.app.feature.guardui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bastion.app.core.design.BastionCard
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.accessibility.BastionAccessibilityService
import com.bastion.app.guard.lockdown.BastionDeviceAdmin
import com.bastion.app.guard.vpn.BastionVpnService

/**
 * The five things that actually stand between a man and a feed.
 *
 * Each is a separate system-level grant, given on a different screen, and
 * before this card they were discovered by scrolling — so it was entirely
 * possible to finish onboarding with one layer live and the app's language
 * implying full protection. Naming them in one place, with their real state,
 * is what makes "how protected am I" answerable at a glance.
 *
 * Notifications are here too, and honestly do not protect anything on their
 * own: they are how you find out a layer has fallen. They count because a
 * silent watchdog is not a watchdog.
 */
/**
 * Named for what each layer does for the person, in their words.
 *
 * "Blocks known hosts at the resolver" is true and unreadable. A status meter
 * whose rows have to be decoded is not a status meter.
 */
enum class GuardLayer(val label: String, val blurb: String) {
    FEED_GUARD("Feed blocking", "Closes Reels, Shorts and For You as they open"),
    CONTENT_FILTER("Website filter", "Blocks adult sites across your apps and browsers"),
    PRIVATE_DNS("Extra website blocking", "Catches the apps that route around the filter"),
    SCREEN_LOCK("Screen lock", "Lets a panic lockdown lock your screen"),
    GRAYSCALE("Dimming", "Drains the colour out of guarded apps"),
    NOTIFICATIONS("Alerts", "Tells you when a protection drops"),
    STAY_AWAKE("Keep Bastion awake", "Stops Android putting the guard to sleep"),
}

/** What a layer's state is right now, and what to say when it is not on. */
data class LayerState(
    val layer: GuardLayer,
    val on: Boolean,
    /** Shown instead of "on" — e.g. "veil only", so a partial state never reads as off. */
    val partial: String? = null,
)

/**
 * Reads every layer's real state, and re-reads it whenever the screen resumes.
 *
 * Every one of these is granted on a system screen outside the app, so the
 * only moment their state can be trusted is on return from that screen. This is
 * the pattern the grayscale card already used, applied to all six — the classic
 * Android setup failure is sending someone to Settings and never confirming
 * they actually did it.
 */
@Composable
fun rememberGuardLayers(settings: Settings): List<LayerState> {
    val context = LocalContext.current
    var states by remember { mutableStateOf(readLayers(context, settings)) }

    LifecycleResumeEffect(settings) {
        states = readLayers(context, settings)
        onPauseOrDispose { }
    }

    return states
}

private fun readLayers(context: Context, settings: Settings): List<LayerState> = listOf(
    LayerState(GuardLayer.FEED_GUARD, BastionAccessibilityService.isEnabled(context)),
    LayerState(
        GuardLayer.CONTENT_FILTER,
        // Consent alone is not protection: the switch must also be on, or the
        // service is authorised and idle.
        // Not counted while Private DNS is also set: the two cannot coexist,
        // and scoring them as two independent points is what encouraged
        // turning both on — which takes the phone off the internet.
        on = settings.vpnFilterEnabled &&
            BastionVpnService.prepareIntent(context) == null &&
            !com.bastion.app.guard.vpn.DnsFilters.privateDnsIsSet(context),
        partial = if (settings.vpnFilterEnabled &&
            com.bastion.app.guard.vpn.DnsFilters.privateDnsIsSet(context)
        ) "clashing with Private DNS" else null,
    ),
    LayerState(GuardLayer.PRIVATE_DNS, com.bastion.app.guard.vpn.DnsFilters.privateDnsIsSet(context)),
    LayerState(GuardLayer.SCREEN_LOCK, BastionDeviceAdmin.isActive(context)),
    // Was gated on WRITE_SECURE_SETTINGS and reported "veil only" without it,
    // which implied a stronger mode existed behind the grant. None did — the
    // veil is the whole feature, so the switch alone decides.
    LayerState(GuardLayer.GRAYSCALE, settings.grayscaleEnabled),
    LayerState(GuardLayer.NOTIFICATIONS, notificationsAllowed(context)),
    // Belongs here rather than in a battery setting. Android revokes an
    // accessibility service when its app is force-stopped, and the system
    // force-stops apps it thinks are idle — so without this, the guard can be
    // gone by morning with nothing said. See BatteryExemption.
    LayerState(
        GuardLayer.STAY_AWAKE,
        com.bastion.app.guard.BatteryExemption.isExempt(context),
    ),
)


private fun notificationsAllowed(context: Context): Boolean =
    if (android.os.Build.VERSION.SDK_INT < 33) true
    else context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Guard strength, and the checklist for arming what is missing.
 *
 * One card rather than two: a strength meter that lists what is off, where each
 * "off" line is itself the button to turn it on, *is* the arming checklist. A
 * separate "finish setting up" card would say the same things twice and then
 * disagree with itself the moment one of them went stale.
 */
@Composable
fun GuardStrengthCard(
    layers: List<LayerState>,
    onArm: (GuardLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = layers.count { it.on }
    val total = layers.size

    BastionCard(
        modifier = modifier,
        accent = when {
            on == total -> BastionColors.Sage
            on >= total / 2 -> BastionColors.Bronze
            else -> BastionColors.Amber
        },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("Guard strength")
            Text(
                "$on of $total on",
                style = MaterialTheme.typography.labelMedium,
                color = if (on == total) BastionColors.SageBright else BastionColors.TextMuted,
            )
        }

        Spacer(Modifier.height(Space.md))
        StrengthMeter(on = on, total = total)
        Spacer(Modifier.height(Space.lg))

        layers.forEach { state ->
            LayerRow(state = state, onArm = { onArm(state.layer) })
        }
    }
}

/** Filled pips, not a bar: six discrete grants, counted, not a continuous score. */
@Composable
private fun StrengthMeter(on: Int, total: Int) {
    val lit = when {
        on == total -> BastionColors.SageBright
        on >= total / 2 -> BastionColors.Bronze
        else -> BastionColors.Amber
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        repeat(total) { index ->
            Canvas(Modifier.size(10.dp)) {
                drawCircle(
                    color = if (index < on) lit else BastionColors.Outline,
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
    }
}

@Composable
private fun LayerRow(state: LayerState, onArm: () -> Unit) {
    // Only the unarmed rows are tappable. A row that is already on has nothing
    // to do, and making it look actionable invites a tap that turns something
    // off by accident.
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (state.on) Modifier else Modifier.clickable(onClick = onArm))
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(
                    color = when {
                        state.on -> BastionColors.SageBright
                        state.partial != null -> BastionColors.Bronze
                        else -> BastionColors.Outline
                    },
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
        Spacer(Modifier.size(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                state.layer.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.on) BastionColors.TextPrimary else BastionColors.TextSecondary,
            )
            if (!state.on) {
                Text(
                    state.partial ?: state.layer.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
            }
        }
        if (!state.on) {
            Text(
                if (state.partial != null) "Fix" else "Turn on",
                style = MaterialTheme.typography.labelMedium,
                color = BastionColors.BronzeBright,
            )
        }
    }
}
