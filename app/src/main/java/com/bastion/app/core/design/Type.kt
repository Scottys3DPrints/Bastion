package com.bastion.app.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Serif carries the weight — scripture, oaths and rank titles get gravitas.
 * Everything functional stays in the system sans so it disappears and reads fast.
 *
 * Deliberately uses platform families rather than bundled or downloadable fonts:
 * a man reaching for the panic button at 1am should never wait on a font.
 */
private val Display = FontFamily.Serif
private val Body = FontFamily.SansSerif

val BastionTypography = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 46.sp, lineHeight = 54.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.25).sp),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 29.sp, lineHeight = 37.sp),

    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),

    titleLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),

    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.3.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.8.sp),
)

/** Scripture and oaths: generous, weighty, unhurried. */
val ScriptureStyle = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Normal,
    fontSize = 21.sp,
    lineHeight = 33.sp,
    letterSpacing = 0.1.sp,
)

/**
 * Scripture inside a card on a dense screen. Same gravitas, less floor space —
 * at full size a three-line verse swallowed the whole home screen.
 */
val ScriptureCompactStyle = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 26.sp,
)

/** Small all-caps section markers. */
val OvertureStyle = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = 1.6.sp,
)
