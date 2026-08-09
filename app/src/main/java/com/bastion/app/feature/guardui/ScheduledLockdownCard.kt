package com.bastion.app.feature.guardui

import android.app.AlarmManager
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bastion.app.core.alarm.ScheduledLockdown
import com.bastion.app.core.alarm.ScheduledLockdownScheduler
import com.bastion.app.core.design.BastionChip
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.ChoiceRow
import com.bastion.app.core.design.GroupDivider
import com.bastion.app.core.design.SettingsGroup
import com.bastion.app.core.design.Space
import com.bastion.app.core.design.rememberNotificationPermission
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import com.bastion.app.guard.lockdown.BastionDeviceAdmin
import com.bastion.app.guard.lockdown.Lockdown
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/**
 * The same plan, on a clock.
 *
 * A man who has used the break-glass button a few times can usually name the
 * hour he needs it. Making him press it at that hour every night is asking him
 * to be his most disciplined at his least — the point of deciding in advance is
 * that the decision does not have to be made again in the moment.
 *
 * So this configures *when*, and nothing else. What happens is the plan he
 * already set for the button, deliberately not a second copy of it: two lockdown
 * plans that could drift apart is two things to keep true, and the one that gets
 * forgotten is always the one that fires at eleven o'clock unattended.
 */
@Composable
fun ScheduledLockdownCard(settings: Settings, graph: BastionGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifications = rememberNotificationPermission()

    // While one is running, nothing here can be touched. The rule that a
    // lockdown refuses every weakening until its clock runs out is not softened
    // for the schedule that started it — see weakenOrQueue on the Guard screen.
    val running = remember(settings) { Lockdown.isActive(settings) }

    // Whether the alarm can be exact. Inexact delivery is the difference
    // between a lockout at ten and a lockout at some point after ten, which for
    // a window this size is most of the value, so it is said out loud rather
    // than left to be noticed.
    val exact = remember {
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false
    }

    /** Every write goes through here, so the alarm can never disagree with the settings. */
    fun applyThen(block: suspend () -> Unit) = scope.launch {
        block()
        val updated = graph.settings.current()
        // Claims whatever window is open right now as settled before syncing.
        // Without it, switching this on at 10:30 with a ten o'clock start would
        // read as a window missed by half an hour and lock the phone in the act
        // of being configured — for half an hour that could not be called off.
        ScheduledLockdownScheduler.markWindowServed(context, updated)
        ScheduledLockdownScheduler.sync(context, updated)
    }

    SettingsGroup(
        title = "Nightly lockdown",
        subtitle = "For the hours you already know are the hard ones. The same " +
            "lockdown as the panic button, at a time you pick, without having to " +
            "decide anything when it arrives. It cannot be called off once it starts.",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Curfew",
                    style = MaterialTheme.typography.bodyLarge,
                    color = BastionColors.TextPrimary,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (settings.scheduledLockdownEnabled) {
                        "${curfewDaysLine(settings)} at ${clockTime(settings)} for " +
                            Lockdown.describe(settings.scheduledLockdownSeconds)
                    } else {
                        "Off — nothing happens on its own"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = BastionColors.TextMuted,
                )
            }
            Switch(
                checked = settings.scheduledLockdownEnabled,
                enabled = !running,
                onCheckedChange = { wanted ->
                    // The wall is raised by a notification when the phone is
                    // idle, so a denied permission is a lockout that half
                    // happens. Asked at the moment it is switched on, which is
                    // the only moment the answer means anything.
                    if (wanted) notifications.requestIfNeeded()
                    applyThen { graph.settings.setScheduledLockdownEnabled(wanted) }
                    // Locking the screen needs device admin, exactly as it does
                    // for the button. Asking here beats a scheduled lockdown
                    // that quietly does everything except take the phone away.
                    if (wanted && settings.lockdownLockScreen &&
                        !BastionDeviceAdmin.isActive(context)
                    ) {
                        BastionDeviceAdmin.requestActivation(context)
                    }
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BastionColors.MidnightDeep,
                    checkedTrackColor = BastionColors.Bronze,
                    uncheckedThumbColor = BastionColors.TextMuted,
                    uncheckedTrackColor = BastionColors.SurfaceHigh,
                    uncheckedBorderColor = BastionColors.Outline,
                ),
            )
        }

        if (settings.scheduledLockdownEnabled) {
            GroupDivider()
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Starts at",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BastionColors.TextSecondary,
                )
                TextButton(
                    enabled = !running,
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                applyThen { graph.settings.setScheduledLockdownTime(hour, minute) }
                            },
                            settings.scheduledLockdownHour,
                            settings.scheduledLockdownMinute,
                            true,
                        ).show()
                    },
                ) {
                    Text(
                        clockTime(settings),
                        style = MaterialTheme.typography.headlineSmall,
                        color = BastionColors.BronzeBright,
                    )
                }
            }

            GroupDivider()
            Spacer(Modifier.height(Space.md))
            Text(
                "On these days",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(Space.sm))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { index, letter ->
                    val iso = index + 1
                    // No days chosen means every day, which is what this always
                    // was — so an untouched curfew keeps behaving exactly as it
                    // did before the choice existed.
                    val on = settings.curfewDays.isEmpty() || iso in settings.curfewDays
                    BastionChip(letter, on, Modifier.weight(1f)) {
                        if (!running) {
                            val current = settings.curfewDays.ifEmpty { (1..7).toList() }
                            val next = if (iso in current) current - iso else current + iso
                            // Every day off would be a curfew that never runs
                            // while reading as switched on. Falling back to
                            // "every day" is the honest reading of taking the
                            // last one away.
                            applyThen { graph.settings.setCurfewDays(next) }
                        }
                    }
                }
            }

            GroupDivider()
            Spacer(Modifier.height(Space.md))
            Text(
                "Holds for",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(Space.sm))
            ChoiceRow(
                options = SCHEDULED_LENGTHS,
                selected = settings.scheduledLockdownSeconds,
                label = { Lockdown.describeShort(it) },
                onSelect = { seconds ->
                    if (!running) applyThen { graph.settings.setScheduledLockdownSeconds(seconds) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.md))
            Text(
                nextRunLine(settings),
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.SageBright,
            )

            GroupDivider()
            Spacer(Modifier.height(Space.md))
            Text(
                "What it does",
                style = MaterialTheme.typography.bodyMedium,
                color = BastionColors.TextSecondary,
            )
            Spacer(Modifier.height(Space.xs))
            Text(
                // Its own switches now rather than the button's. A curfew is a
                // routine and a panic press is a crisis; they do not want the
                // same response, and sharing one plan meant changing either
                // changed both.
                "Separate from the panic button's plan, so a routine and a crisis " +
                    "can do different things.",
                style = MaterialTheme.typography.labelSmall,
                color = BastionColors.TextMuted,
            )
            Spacer(Modifier.height(Space.sm))
            CurfewToggle(
                "Take the phone away",
                "Raises the countdown wall.",
                settings.curfewLockScreen,
                enabled = !running,
            ) { on ->
                applyThen { graph.settings.setCurfewLockScreen(on) }
                if (on && !BastionDeviceAdmin.isActive(context)) {
                    BastionDeviceAdmin.requestActivation(context)
                }
            }
            CurfewToggle(
                "Turn the website filter on",
                "Only if you have already given VPN permission.",
                settings.curfewFilter,
                enabled = !running,
            ) { on -> applyThen { graph.settings.setCurfewFilter(on) } }
            CurfewToggle(
                "Drain the colour",
                "Dims every guarded app while it runs.",
                settings.curfewGrayscale,
                enabled = !running,
            ) { on -> applyThen { graph.settings.setCurfewGrayscale(on) } }
            CurfewToggle(
                "Tell your partner",
                // Off by default, and this says why: a man switching it on
                // should know what he is signing his partner up for.
                "A message every time it starts, which on a daily curfew is a " +
                    "message every day.",
                settings.curfewTellPartner,
                enabled = !running,
            ) { on -> applyThen { graph.settings.setCurfewTellPartner(on) } }

            if (!exact) {
                Spacer(Modifier.height(Space.md))
                Text(
                    "Android is not letting Bastion set exact alarms on this phone, so the " +
                        "lockout may start some minutes late. Allow \"Alarms & reminders\" for " +
                        "Bastion in system settings to fix it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.Amber,
                )
            }

            if (notifications.needed && !notifications.granted) {
                Spacer(Modifier.height(Space.md))
                Text(
                    "Notifications are off. Guarded apps and the filter will still lock down " +
                        "on time, but the countdown screen cannot take over a phone that is " +
                        "idle without one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BastionColors.Amber,
                )
            }
        }

        if (running) {
            Spacer(Modifier.height(Space.md))
            Text(
                "A lockdown is running. The schedule can be changed once it ends.",
                style = MaterialTheme.typography.bodySmall,
                color = BastionColors.Amber,
            )
        }
        Spacer(Modifier.height(Space.md))
    }
}

private fun clockTime(settings: Settings): String =
    "%02d:%02d".format(settings.scheduledLockdownHour, settings.scheduledLockdownMinute)

/** "Next: tonight at 22:00" — the one line that proves the thing is armed. */
private fun nextRunLine(settings: Settings): String {
    val now = LocalDateTime.now()
    val next = ScheduledLockdown.nextRunAfter(
        now,
        settings.scheduledLockdownHour,
        settings.scheduledLockdownMinute,
    )
    val hours = Duration.between(now, next).toHours()
    val whenPart = when {
        next.toLocalDate() == now.toLocalDate() -> "today"
        hours < 24 -> "tomorrow"
        else -> next.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    return "Next lockout $whenPart at ${"%02d:%02d".format(next.hour, next.minute)}."
}

private fun planSummaryLine(settings: Settings): String = buildList {
    add("every guarded app closes")
    if (settings.lockdownFilter) add("the filter comes on")
    if (settings.lockdownGrayscale) add("colour goes")
    if (settings.lockdownLockScreen) add("the phone locks to a countdown")
    if (settings.lockdownTellPartner) add("your partner is offered the message")
}.joinToString(", ")

/**
 * The lengths a nightly window is worth having, in seconds.
 *
 * Shorter at the top end than the button's, on purpose. The button answers a
 * crisis and may reasonably take a day; a lockout that runs every single night
 * has to end before the morning, or the first thing it costs is the alarm clock
 * he wakes up to and the second is the whole feature.
 */
internal val SCHEDULED_LENGTHS = listOf(30 * 60, 60 * 60, 2 * 60 * 60, 8 * 60 * 60)

/** One switch in the curfew's own plan. */
@Composable
private fun CurfewToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = BastionColors.TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BastionColors.TextTertiary)
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BastionColors.MidnightDeep,
                checkedTrackColor = BastionColors.Bronze,
                uncheckedThumbColor = BastionColors.TextTertiary,
                uncheckedTrackColor = BastionColors.SurfaceHigh,
                uncheckedBorderColor = BastionColors.OutlineStrong,
            ),
        )
    }
}

/** "Every day", "Weekends", "Mon, Wed, Fri" — the schedule in words. */
private fun curfewDaysLine(settings: Settings): String {
    val days = settings.curfewDays
    if (days.isEmpty() || days.size == 7) return "Every day"
    val sorted = days.sorted()
    if (sorted == listOf(6, 7)) return "Weekends"
    if (sorted == (1..5).toList()) return "Weekdays"
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return sorted.joinToString(", ") { names[it - 1] }
}
