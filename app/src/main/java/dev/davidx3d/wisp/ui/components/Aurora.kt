package dev.davidx3d.wisp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.davidx3d.wisp.ui.theme.Wisp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Slowly drifting colour fields behind the conversation. [energy] (0..1) brightens and
 * lifts them, [level] lets them breathe with the voice.
 */
@Composable
fun AuroraBackground(
    energy: Float,
    level: () -> Float,
    modifier: Modifier = Modifier,
    anchorY: Float = 0.92f,
) {
    val colors = Wisp.colors
    val bg = MaterialTheme.colorScheme.surface
    val e by animateFloatAsState(energy, tween(900), label = "energy")
    val t = rememberInfiniteTransition(label = "aurora")
    val a by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(21000, easing = LinearEasing)), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "b")
    val darkBoost = if (colors.isDark) 1.25f else 0.9f

    Canvas(modifier.fillMaxSize()) {
        drawRect(bg)
        val w = size.width
        val h = size.height
        val lv = level().coerceIn(0f, 1f)
        val lift = (0.15f + e * 0.12f + lv * 0.06f) * h
        val blobs = listOf(
            Triple(colors.glowA, Offset(w * (0.25f + 0.12f * sin(a)), h * anchorY - lift * (0.8f + 0.2f * b)), 0.75f),
            Triple(colors.glowB, Offset(w * (0.78f + 0.1f * cos(a * 1.3f)), h * anchorY - lift * 0.6f), 0.65f),
            Triple(colors.glowC, Offset(w * (0.5f + 0.18f * sin(a * 0.7f + 1f)), h * (anchorY + 0.05f) - lift * 0.3f), 0.85f),
        )
        blobs.forEach { (c, center, r) ->
            val radius = w * r * (0.85f + e * 0.25f + lv * 0.1f)
            val alpha = ((0.16f + e * 0.30f) * darkBoost).coerceAtMost(0.6f)
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = alpha), c.copy(alpha = alpha * 0.35f), Color.Transparent), center, radius),
                radius = radius, center = center,
            )
        }
    }
}
