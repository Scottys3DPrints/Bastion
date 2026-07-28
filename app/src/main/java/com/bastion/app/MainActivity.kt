package com.bastion.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BastionRoot() }
    }
}

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    WATCHTOWER("watchtower", "Watchtower", Icons.Filled.Shield),
    TRACK("track", "Track", Icons.Filled.Insights),
    GUARD("guard", "Guard", Icons.Filled.Security),
    GROW("grow", "Grow", Icons.Filled.TrendingUp),
    BROTHERHOOD("brotherhood", "Brotherhood", Icons.Filled.Forum),
}

@Composable
private fun BastionRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = androidx.compose.runtime.remember { BastionGraph.from(context) }
    val settings by graph.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())

    BastionTheme(faithMode = settings.faithMode) {
        if (!settings.onboarded) {
            // Onboarding owns the whole window: the Covenant is a ceremony, and
            // a navigation bar underneath it would cheapen the moment.
            OnboardingFlow(onComplete = { })
        } else {
            MainScaffold(faithMode = settings.faithMode)
        }
    }
}

@Composable
private fun MainScaffold(faithMode: Boolean) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

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
                        label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) },
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
