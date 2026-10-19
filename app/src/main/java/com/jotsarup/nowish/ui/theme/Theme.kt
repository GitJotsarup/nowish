package com.jotsarup.nowish.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Used on Android 11 and below, where there's no wallpaper-based dynamic color.
private val LightFallback = lightColorScheme(
    primary = Color(0xFF6C5CE7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFB4588A),
    tertiaryContainer = Color(0xFFFFD8E8),
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFCBBEFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = Color(0xFFFFB0D2),
    tertiaryContainer = Color(0xFF7D2A5A),
    background = Color(0xFF141218),
    surface = Color(0xFF141218),
)

private val BaseType = Typography()

private val NowishTypography = BaseType.copy(
    displaySmall = BaseType.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseType.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
    bodyLarge = BaseType.bodyLarge.copy(fontSize = 17.sp, lineHeight = 23.sp),
)

private val NowishShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun NowishTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = NowishTypography,
        shapes = NowishShapes,
        content = content,
    )
}
