package com.bastion.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TrendingUp
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
 * Tab labels are short, plain words rather than the app's thematic names.
 *
 * "Watchtower" and "Brotherhood" overflowed the bar and clipped off-screen at
 * five tabs, and a navigation label's only job is to be understood instantly.
 * The thematic names still head their screens, where there is room for them.
 */
private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    WATCHTOWER("watchtower", "Home", Icons.Filled.Shield),
    TRACK("track", "Track", Icons.Filled.Insights),
    GUARD("guard", "Guard", Icons.Filled.Security),
    GROW("grow", "Grow", Icons.Filled.TrendingUp),
    BROTHERHOOD("brotherhood", "Partner", Icons.Filled.Forum),
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

@Composable
private fun MainScaffold(faithMode: Boolean, openTarget: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Arriving from the Panic screen's "Talk it through", which used to be a
    // dead button on the one screen where a dead button is least forgivable.
    androidx.compose.runtime.LaunchedEffect(openTarget) {
        if (openTarget == MainActivity.OPEN_MENTOR) navController.navigate("mentor")
    }

    Scaffold(
        containerColor = BastionColors.Midnight,
        contentColor = BastionColors.TextPrimary,
        bottomBar = {
            NavigationBar(containerColor = BastionColors.MidnightDeep, tonalElevation = 0.dp) {
                Destination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
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
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.WATCHTOWER.route,
            ) {
                composable(Destination.WATCHTOWER.route) {
                    WatchtowerScreen(
                        faithMode = faithMode,
                        onOpenMentor = { navController.navigate("mentor") },
                        onOpenTrack = { navController.navigate(Destination.TRACK.route) },
                        onOpenSettings = { navController.navigate("settings") },
                    )
                }
                composable(Destination.TRACK.route) { TrackScreen(faithMode = faithMode) }
                composable(Destination.GUARD.route) { GuardScreen() }
                composable(Destination.GROW.route) { GrowScreen(faithMode = faithMode) }
                composable(Destination.BROTHERHOOD.route) {
                    BrotherhoodScreen(onOpenMentor = { navController.navigate("mentor") })
                }
                composable("mentor") {
                    MentorScreen(faithMode = faithMode, onBack = { navController.popBackStack() })
                }
                composable("settings") {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
