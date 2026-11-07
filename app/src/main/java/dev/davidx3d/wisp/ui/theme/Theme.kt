package dev.davidx3d.wisp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.davidx3d.wisp.R

// Brand palette, taken from the logo gradient.
val BrandViolet = Color(0xFF6A4CFF)
val BrandMagenta = Color(0xFFC04DE8)
val BrandCoral = Color(0xFFFF7E8B)
val BrandCream = Color(0xFFFFF7F2)

private val LightFallback = lightColorScheme(
    primary = Color(0xFF5B3EEA), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1B0063),
    secondary = Color(0xFF8A3DAA), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8D8FF), onSecondaryContainer = Color(0xFF34003F),
    tertiary = Color(0xFFB4485A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9DD), onTertiaryContainer = Color(0xFF400015),
    background = Color(0xFFFDF8FF), onBackground = Color(0xFF1C1A22),
    surface = Color(0xFFFDF8FF), onSurface = Color(0xFF1C1A22),
    surfaceVariant = Color(0xFFE6E0EE), onSurfaceVariant = Color(0xFF48454F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F1FC),
    surfaceContainer = Color(0xFFF1EBF7), surfaceContainerHigh = Color(0xFFEBE5F1),
    surfaceContainerHighest = Color(0xFFE6E0EC), outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4D0), error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6),
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFFCABEFF), onPrimary = Color(0xFF30009C),
    primaryContainer = Color(0xFF4520D2), onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFEDB1FF), onSecondary = Color(0xFF530A6F),
    secondaryContainer = Color(0xFF6E2490), onSecondaryContainer = Color(0xFFF8D8FF),
    tertiary = Color(0xFFFFB2BB), onTertiary = Color(0xFF67002A),
    tertiaryContainer = Color(0xFF913043), onTertiaryContainer = Color(0xFFFFD9DD),
    background = Color(0xFF131218), onBackground = Color(0xFFE6E0EC),
    surface = Color(0xFF131218), onSurface = Color(0xFFE6E0EC),
    surfaceVariant = Color(0xFF48454F), onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0E0D13), surfaceContainerLow = Color(0xFF1C1A22),
    surfaceContainer = Color(0xFF201E26), surfaceContainerHigh = Color(0xFF2A2831),
    surfaceContainerHighest = Color(0xFF35333C), outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F), error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))

private fun Typography.branded(): Typography {
    fun TextStyle.o(w: Int, spacing: Float = -0.01f) = copy(fontFamily = Outfit, fontWeight = FontWeight(w), letterSpacing = spacing.em)
    return copy(
        displayLarge = displayLarge.o(600, -0.03f),
        displayMedium = displayMedium.o(600, -0.03f),
        displaySmall = displaySmall.o(600, -0.025f),
        headlineLarge = headlineLarge.o(600, -0.02f),
        headlineMedium = headlineMedium.o(600, -0.02f),
        headlineSmall = headlineSmall.o(600, -0.015f),
        titleLarge = titleLarge.o(600),
        titleMedium = titleMedium.o(600, 0f),
        titleSmall = titleSmall.o(600, 0f),
        labelLarge = labelLarge.o(600, 0.005f),
        labelMedium = labelMedium.o(600, 0.01f),
        labelSmall = labelSmall.o(600, 0.02f),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
    )
}

private val WispShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Extra brand colours derived from the active scheme (so they follow Material You too). */
@Immutable
data class WispColors(
    val gradient: List<Color>,
    val glowA: Color,
    val glowB: Color,
    val glowC: Color,
    val userBubble: Brush,
    val onUserBubble: Color,
    val isDark: Boolean,
)

val LocalWispColors = staticCompositionLocalOf {
    WispColors(listOf(BrandViolet, BrandMagenta, BrandCoral), BrandViolet, BrandMagenta, BrandCoral,
        Brush.linearGradient(listOf(BrandViolet, BrandMagenta)), Color.White, false)
}

object Wisp {
    val colors: WispColors @Composable get() = LocalWispColors.current
}

@Composable
fun WispTheme(dynamic: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    val colors = remember(scheme, dark) {
        // Blend the brand hues into the wallpaper colours so Wisp keeps its character.
        val a = lerp(scheme.primary, BrandViolet, 0.35f)
        val b = lerp(scheme.tertiary, BrandMagenta, 0.35f)
        val c = lerp(scheme.secondary, BrandCoral, 0.30f)
        WispColors(
            gradient = listOf(a, b, c),
            glowA = a, glowB = b, glowC = c,
            userBubble = Brush.linearGradient(listOf(scheme.primary, lerp(scheme.primary, scheme.tertiary, 0.55f))),
            onUserBubble = scheme.onPrimary,
            isDark = dark,
        )
    }
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = WispShapes,
        typography = Typography().branded(),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalWispColors provides colors) {
            content()
        }
    }
}

val MaterialTheme.wisp: WispColors @Composable get() = LocalWispColors.current
