package com.bastion.app.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val BastionScheme = darkColorScheme(
    primary = BastionColors.Bronze,
    onPrimary = BastionColors.MidnightDeep,
    primaryContainer = BastionColors.BronzeDeep,
    onPrimaryContainer = BastionColors.BronzeBright,

    secondary = BastionColors.Sage,
    onSecondary = BastionColors.MidnightDeep,
    secondaryContainer = BastionColors.SageDeep,
    onSecondaryContainer = BastionColors.SageBright,

    tertiary = BastionColors.Steel,
    onTertiary = BastionColors.MidnightDeep,
    tertiaryContainer = BastionColors.SurfaceHigh,
    onTertiaryContainer = BastionColors.SteelBright,

    background = BastionColors.Midnight,
    onBackground = BastionColors.TextPrimary,
    surface = BastionColors.Surface,
    onSurface = BastionColors.TextPrimary,
    surfaceVariant = BastionColors.SurfaceRaised,
    onSurfaceVariant = BastionColors.TextSecondary,
    surfaceContainer = BastionColors.SurfaceRaised,
    surfaceContainerHigh = BastionColors.SurfaceHigh,
    surfaceContainerLow = BastionColors.Surface,

    // Even "error" is warm here. Nothing in Bastion shouts at you.
    error = BastionColors.Amber,
    onError = BastionColors.MidnightDeep,
    errorContainer = BastionColors.AmberSoft,
    onErrorContainer = BastionColors.BronzeBright,

    outline = BastionColors.Outline,
    outlineVariant = BastionColors.OutlineSoft,
)

/** True when the user is in Faith Mode, so copy and iconography can adapt. */
val LocalFaithMode = staticCompositionLocalOf { true }

@Composable
fun BastionTheme(
    faithMode: Boolean = true,
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Bastion is dark-first by design, not by preference: urges peak at night and
    // a bright screen at 1am is its own kind of enemy. There is no light scheme.
    CompositionLocalProvider(LocalFaithMode provides faithMode) {
        MaterialTheme(
            colorScheme = BastionScheme,
            typography = BastionTypography,
            content = content,
        )
    }
}
