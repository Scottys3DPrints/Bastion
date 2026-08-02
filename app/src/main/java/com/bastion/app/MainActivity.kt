package com.bastion.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bastion.app.core.design.BastionColors
import com.bastion.app.core.design.BastionTheme
import com.bastion.app.data.BastionGraph
import com.bastion.app.data.prefs.Settings
import com.bastion.app.feature.brotherhood.BrotherhoodScreen
import com.bastion.app.feature.grow.GrowScreen
import com.bastion.app.feature.guardui.GuardScreen
import com.bastion.app.feature.home.WatchtowerScreen
import com.bastion.app.feature.mentor.MentorScreen
import com.bastion.app.feature.onboarding.OnboardingFlow
import com.bastion.app.feature.settings.SettingsScreen
import com.bastion.app.feature.track.TrackScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BastionRoot(openTarget = intent?.getStringExtra(EXTRA_OPEN)) }
    }

    /** Re-delivered when the Panic screen routes here while this is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setContent { BastionRoot(openTarget = intent.getStringExtra(EXTRA_OPEN)) }
    }

    companion object {
        const val EXTRA_OPEN = "com.bastion.app.OPEN"
        const val OPEN_MENTOR = "mentor"
    }
}

/**
 * Four tabs, each genuinely distinct.
 *
 * There were five, and two of them — Home and Track — overlapped so heavily
 * that streak, clean days, rank and the benefit cards all rendered twice. When
 * every screen tries to be a dashboard, nothing is clearly *the* place for
 * anything. The line drawn instead: **Today is what about right now, Progress
 * is how it is going over time.**
 *
 * Partner and Mentor left the bar entirely. Neither is a daily-visit
 * destination, both were pushing the labels to clip — which is why the tabs had
 * already been renamed away from their thematic names — and accountability
 * belongs next to the settings that configure it. They live in the profile hub,
 * one tap from the top bar of every screen.
 */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    TODAY("watchtower", "Today", Icons.Filled.Shield),
    PROGRESS("track", "Progress", Icons.Filled.Insights),
    GUARD("guard", "Guard", Icons.Filled.Security),
    GROW("grow", "Grow", Icons.AutoMirrored.Filled.TrendingUp),
}

@Composable
private fun BastionRoot(openTarget: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = androidx.compose.runtime.remember { BastionGraph.from(context) }

    // Null until DataStore has actually answered.
    //
    // Seeding this with a default Settings() meant `onboarded` read false for
    // the first frames of every cold start, so an already-covenanted user saw
    // the sign-up ceremony flash past on each launch — and on a slow morning
    // could have tapped into it.
    val settings by graph.settings.settings
        .collectAsStateWithLifecycle<Settings?>(initialValue = null)

    // Reconciled on every app resume, not on any one screen's.
    //
    // It lived on the Guard screen first, which turned out to be too narrow:
    // enabling Guard in system settings and coming back to a different tab — or
    // being on Guard already, where re-selecting the tab fires no new resume —
    // left the intent unrecorded, and an unrecorded intent means no breach is
    // ever detected. Whether Guard is running is app-level state.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        kotlinx.coroutines.CoroutineScope(graph.applicationScope.coroutineContext).launch {
            com.bastion.app.guard.GuardWatchdog.reconcile(context)
            // Cooling-off requests also mature here, not only on the daily
            // alarm — otherwise a change whose delay expired at noon could sit
            // unapplied until the next morning's brief.
            if (graph.guard.applyMaturedChanges()) {
                com.bastion.app.guard.vpn.BastionVpnService.stop(context)
            }
            // Whatever the lock says now, the enforcement follows it. This is
            // the path that lifts the restrictions when a cooling-off unlock
            // finally matures — without it, deciding to stop being locked in
            // would leave uninstall blocked and the minute watch running.
            val lockedIn = graph.settings.current().tamperLockEnabled
            com.bastion.app.guard.lockdown.DeviceOwner.apply(context, lockedIn)
            com.bastion.app.core.alarm.LockInWatchScheduler.sync(context, lockedIn)
            com.bastion.app.guard.GuardWatchdog.enforceIfLockedIn(context)
        }
        onPauseOrDispose { }
    }

    val loaded = settings
    BastionTheme(faithMode = loaded?.faithMode ?: true) {
        when {
            // Brief on a real phone, but a pure black rectangle reads as a
            // broken app rather than a loading one, so it carries the mark.
            loaded == null -> Box(
                Modifier
                    .fillMaxSize()
                    .background(BastionColors.Midnight),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Text(
                    "◇",
                    style = MaterialTheme.typography.displayMedium,
                    color = BastionColors.BronzeDeep,
                )
            }
            !loaded.onboarded -> {
                // Onboarding owns the whole window: the Covenant is a ceremony,
                // and a navigation bar underneath it would cheapen the moment.
                OnboardingFlow(onComplete = { })
            }
            else -> MainScaffold(faithMode = loaded.faithMode, openTarget = openTarget)
        }
    }
}

/**
 * Switches to a tab from anywhere, including from the profile hub or the Mentor.
 *
 * Deliberately without saveState/restoreState. The usual pattern assumes each
 * tab owns a nested graph; this graph is flat, and on a flat graph the pair
 * misbehaves in two ways that were both reachable by hand:
 *
 *   - the state saved against the start destination included whatever overlay
 *     was stacked on it, so leaving Settings by tapping a tab and then tapping
 *     Home put Settings straight back;
 *   - navigating to the start destination itself restored the stack that had
 *     just been saved against it, so Home landed on Track.
 *
 * Dropping both costs per-tab scroll position and buys navigation that always
 * goes where it says. For four shallow tabs that is unambiguously the better
 * trade — the whole complaint was that the bar could not be trusted.
 */
private fun androidx.navigation.NavHostController.toTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { inclusive = false }
        launchSingleTop = true
    }
}

@Composable
private fun MainScaffold(faithMode: Boolean, openTarget: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Arriving from the Panic screen's "Talk it through", which used to be a
    // dead button on the one screen where a dead button is least forgivable.
    androidx.compose.runtime.LaunchedEffect(openTarget) {
        if (openTarget == MainActivity.OPEN_MENTOR) navController.navigate(ROUTE_MENTOR)
    }

    val onTabs = Destination.entries.any { d ->
        currentDestination?.hierarchy?.any { it.route == d.route } == true
    }

    Scaffold(
        containerColor = BastionColors.Midnight,
        contentColor = BastionColors.TextPrimary,
        bottomBar = {
            // Hidden on pushed routes. The bar is for switching between the
            // four homes; showing it under a detail screen invites a tap that
            // silently discards whatever is being edited there.
            if (onTabs) {
                NavigationBar(containerColor = BastionColors.MidnightDeep, tonalElevation = 0.dp) {
                    Destination.entries.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.toTab(destination.route) },
                            icon = {
                                Icon(destination.icon, contentDescription = destination.label)
                            },
                            label = {
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = BastionColors.MidnightDeep,
                                selectedTextColor = BastionColors.BronzeBright,
                                indicatorColor = BastionColors.Bronze,
                                unselectedIconColor = BastionColors.TextMuted,
                                unselectedTextColor = BastionColors.TextMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.TODAY.route,
                // One transition system, chosen once and applied globally:
                // tabs cross-fade because they are siblings with no spatial
                // relationship, and pushed routes slide because they sit on
                // top of what you came from. Mixing the two — or leaving the
                // defaults — is most of why navigation felt arbitrary.
                enterTransition = { fadeIn(tween(180)) },
                exitTransition = { fadeOut(tween(180)) },
            ) {
                composable(Destination.TODAY.route) {
                    WatchtowerScreen(
                        faithMode = faithMode,
                        onOpenProgress = { navController.toTab(Destination.PROGRESS.route) },
                        onOpenProfile = { navController.navigate(ROUTE_PROFILE) },
                    )
                }
                composable(Destination.PROGRESS.route) {
                    TrackScreen(
                        faithMode = faithMode,
                        onOpenProfile = { navController.navigate(ROUTE_PROFILE) },
                    )
                }
                composable(Destination.GUARD.route) {
                    GuardScreen(onOpenProfile = { navController.navigate(ROUTE_PROFILE) })
                }
                composable(Destination.GROW.route) {
                    GrowScreen(
                        faithMode = faithMode,
                        onOpenProfile = { navController.navigate(ROUTE_PROFILE) },
                    )
                }

                pushed(ROUTE_MENTOR) {
                    MentorScreen(faithMode = faithMode, onBack = { navController.popBackStack() })
                }
                pushed(ROUTE_PARTNER) {
                    BrotherhoodScreen(
                        onOpenMentor = { navController.navigate(ROUTE_MENTOR) },
                        onBack = { navController.popBackStack() },
                    )
                }
                pushed(ROUTE_PROFILE) {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPartner = { navController.navigate(ROUTE_PARTNER) },
                        onOpenMentor = { navController.navigate(ROUTE_MENTOR) },
                    )
                }
            }
        }
    }
}

const val ROUTE_MENTOR = "mentor"
const val ROUTE_PARTNER = "partner"
const val ROUTE_PROFILE = "profile"

/** A route that sits on top of a tab: slides in from the right, back out to it. */
private fun androidx.navigation.NavGraphBuilder.pushed(
    route: String,
    content: @Composable () -> Unit,
) {
    composable(
        route = route,
        enterTransition = { slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220)) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(200)) },
    ) { content() }
}
