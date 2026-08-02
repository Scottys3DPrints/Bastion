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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bastion.app.core.design.BastionCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.PrimaryButton
import com.bastion.app.core.design.QuietButton
import com.bastion.app.core.design.SectionLabel
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.lockdown.BastionDeviceAdmin
import com.bastion.app.guard.lockdown.Lockdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Break glass.
 *
 * A plan chosen calmly, in advance, so that pressing one button in a bad moment
 * carries it out without asking a single question. Deciding anything at the
 * moment of pressing is precisely what the man reaching for this cannot do.
 *
 * Amber rather than red, like everything else here — this is a decision he made
 * for himself, not an emergency being declared over him.
 */
@Composable
fun LockdownCard(settings: Settings, graph: BastionGraph, showPlanLink: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var configuring by remember { mutableStateOf(false) }

    // Asks Lockdown, rather than comparing the wall clock itself. Once the
    // countdown gained a monotonic anchor, a lockdown could be running by the
    // elapsed clock while `lockdownUntil` had already passed — and this button
    // would have offered to start a new one on top of it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = remember(settings, now) { Lockdown.isActive(settings) }
    LaunchedEffect(settings.lockdownUntil, settings.lockdownEndElapsed) {
        while (Lockdown.isActive(settings)) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    BastionCard(accent = if (active) BastionColors.Amber else BastionColors.Bronze) {
        if (active) {
            SectionLabel("Lockdown running", color = BastionColors.Amber)
            Spacer(Modifier.height(10.dp))
            val minutes = Lockdown.remainingMinutes(settings)
            Text(
                if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m left" else "${minutes}m left",
                style = MaterialTheme.typography.headlineMedium,
                color = BastionColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                // Says plainly that it cannot be called off, because discovering
                // that by trying would feel like a trap rather than a decision.
                "Guarded apps stay closed until it ends. Clearing Bastion's data or turning "
                    + "Guard off would end it — Android lets no app be truly uncloseable.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
        } else {
            SectionLabel("Break glass")
            Spacer(Modifier.height(8.dp))
            Text(
                "One button, ${settings.lockdownHours}h. Not cancellable from inside the app.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Lock everything down", { confirming = true }, Modifier.fillMaxWidth())
            if (showPlanLink) {
                Spacer(Modifier.height(8.dp))
                QuietButton("Edit the plan", { configuring = true }, Modifier.fillMaxWidth())
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = BastionColors.Surface,
            title = { Text("Lock down for ${settings.lockdownHours} hours?", color = BastionColors.TextPrimary) },
            text = {
                Text(
                    buildString {
                        append("Every guarded app closes. ")
                        if (settings.lockdownFilter) append("The filter comes on. ")
                        if (settings.lockdownGrayscale) append("Colour goes. ")
                        if (settings.lockdownLockScreen) append("Your screen locks once — not for the whole time. ")
                        append("\n\nThis cannot be undone before it ends.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        Lockdown.trigger(context)?.let { runCatching { context.startActivity(it) } }
                    }
                }) { Text("Do it", color = BastionColors.Amber) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Not now", color = BastionColors.TextMuted)
                }
            },
        )
    }

    if (configuring) LockdownPlanDialog(settings, graph) { configuring = false }
}

/**
 * The trigger alone, sized to sit in the pinned row beside Hold the Line.
 *
 * It began life inside a card in the scrolling body, which meant scrolling past
 * the brief, the habits and the benefit card to reach it — useless for a button
 * whose entire value is being there the instant it is wanted. Pinned, it is
 * always on screen, exactly like the panic button it sits next to.
 */
@Composable
fun LockdownCompactButton(
    settings: Settings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val active = settings.lockdownUntil > now
    LaunchedEffect(settings.lockdownUntil) {
        while (settings.lockdownUntil > System.currentTimeMillis()) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
        now = System.currentTimeMillis()
    }

    // A small FAB, not a full-width outlined button. In the pinned action slot
    // it sat as a wide rectangle across the content behind it and read as a
    // stray card rather than a control; the mini-FAB is the standard shape for
    // a secondary action attached to a primary one, and it takes a corner
    // rather than a whole row.
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = { if (!active) confirming = true },
        modifier = modifier,
        containerColor = BastionColors.Surface,
        contentColor = BastionColors.Amber,
    ) {
        if (active) {
            val m = Lockdown.remainingMinutes(settings)
            Text(
                if (m >= 60) "${m / 60}h" else "${m}m",
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            androidx.compose.material3.Icon(
                Icons.Filled.Lock,
                contentDescription = "Break glass — lock down now",
            )
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = BastionColors.Surface,
            title = { Text("Lock down for ${settings.lockdownHours} hours?", color = BastionColors.TextPrimary) },
            text = {
                Text(
                    buildString {
                        append("Every guarded app closes. ")
                        if (settings.lockdownFilter) append("The filter comes on. ")
                        if (settings.lockdownGrayscale) append("Colour goes. ")
                        if (settings.lockdownLockScreen) append("Your screen locks once — not for the whole time. ")
                        append("\n\nThis cannot be undone before it ends.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        Lockdown.trigger(context)?.let { runCatching { context.startActivity(it) } }
                    }
                }) { Text("Do it", color = BastionColors.Amber) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text("Not now", color = BastionColors.TextMuted)
                }
            },
        )
    }
}

/**
 * The plan on its own, for the settings section.
 *
 * Split from the button deliberately. The trigger belongs where a man can reach
 * it without thinking; the plan belongs where he configures things calmly, and
 * the two have no business sharing a screen position just because they share a
 * feature name.
 */
@Composable
fun LockdownPlanCard(settings: Settings, graph: BastionGraph) {
    var configuring by remember { mutableStateOf(false) }

    BastionCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SectionLabel("Break-glass plan")
                Spacer(Modifier.height(6.dp))
                Text(
                    "${settings.lockdownHours}h · ${planSummary(settings)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
            QuietButton("Edit", { configuring = true })
        }
    }

    if (configuring) LockdownPlanDialog(settings, graph) { configuring = false }
}

private fun planSummary(settings: Settings): String = buildList {
    add("apps closed")
    if (settings.lockdownFilter) add("filter")
    if (settings.lockdownGrayscale) add("greyscale")
    if (settings.lockdownLockScreen) add("screen lock")
    if (settings.lockdownTellPartner) add("partner told")
}.joinToString(", ")

@Composable
private fun LockdownPlanDialog(
    settings: Settings,
    graph: BastionGraph,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BastionColors.Surface,
        title = { Text("Your plan", color = BastionColors.TextPrimary) },
        text = {
            Column {
                SectionLabel("How long")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 6, 24, 72).forEach { hours ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (settings.lockdownHours == hours) BastionColors.BronzeDeep
                                    else BastionColors.SurfaceRaised
                                )
                                .clickable { scope.launch { graph.settings.setLockdownHours(hours) } }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                "${hours}h",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (settings.lockdownHours == hours) BastionColors.BronzeBright
                                else BastionColors.TextMuted,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionLabel("What happens")
                PlanToggle("Close every guarded app", true, enabled = false) {}
                PlanToggle("Turn the filter on", settings.lockdownFilter) {
                    scope.launch { graph.settings.setLockdownFilter(it) }
                }
                PlanToggle("Drain the colour", settings.lockdownGrayscale) {
                    scope.launch { graph.settings.setLockdownGrayscale(it) }
                }
                PlanToggle("Lock the screen", settings.lockdownLockScreen) { wanted ->
                    scope.launch { graph.settings.setLockdownLockScreen(wanted) }
                    // Needs a system consent screen; asking at the moment the
                    // option is chosen beats failing silently later.
                    if (wanted && !BastionDeviceAdmin.isActive(context)) {
                        BastionDeviceAdmin.requestActivation(context)
                    }
                }
                PlanToggle("Tell my partner", settings.lockdownTellPartner) {
                    scope.launch { graph.settings.setLockdownTellPartner(it) }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    // The obvious wish, answered before it is asked.
                    "Android won't let any app switch the phone off — that's reserved for the system. " +
                        "Locking the screen is the closest there is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = BastionColors.BronzeBright)
            }
        },
    )
}

@Composable
private fun PlanToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) BastionColors.TextSecondary else BastionColors.TextMuted,
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BastionColors.MidnightDeep,
                checkedTrackColor = BastionColors.Bronze,
                uncheckedThumbColor = BastionColors.TextMuted,
                uncheckedTrackColor = BastionColors.SurfaceHigh,
                uncheckedBorderColor = BastionColors.Outline,
                disabledCheckedTrackColor = BastionColors.BronzeDeep,
            ),
        )
    }
}

