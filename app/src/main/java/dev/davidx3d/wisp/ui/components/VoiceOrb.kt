package dev.davidx3d.wisp.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.davidx3d.wisp.session.Phase
import dev.davidx3d.wisp.ui.theme.BrandCream
import dev.davidx3d.wisp.ui.theme.Wisp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

private fun shapeFor(phase: Phase): RoundedPolygon = when (phase) {
    Phase.IDLE -> MaterialShapes.Cookie9Sided
    Phase.CONNECTING, Phase.RECONNECTING -> MaterialShapes.SoftBurst
    Phase.LISTENING -> MaterialShapes.Cookie9Sided
    Phase.USER_SPEAKING -> MaterialShapes.Cookie12Sided
    Phase.THINKING -> MaterialShapes.Clover8Leaf
    Phase.SPEAKING -> MaterialShapes.Sunny
    Phase.ERROR -> MaterialShapes.Puffy
}

/**
 * The big state indicator. A Material 3 Expressive shape that morphs between states,
 * rotates, breathes with the audio level and carries the logo's waveform inside.
 *
 * [level] is read only while drawing, so audio updates redraw without recomposing.
 */
@Composable
fun VoiceOrb(
    phase: Phase,
    level: () -> Float,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
) {
    val colors = Wisp.colors
    val scheme = MaterialTheme.colorScheme

    // Morph from the previous shape to the new one.
    var from by remember { mutableStateOf(shapeFor(phase)) }
    var to by remember { mutableStateOf(shapeFor(phase)) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(phase) {
        val target = shapeFor(phase)
        if (target !== to) {
            from = to
            to = target
            progress.snapTo(0f)
            progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
    }
    val morph = remember(from, to) { Morph(from.normalized(), to.normalized()) }

    val t = rememberInfiniteTransition(label = "orb")
    val spinSpeed = when (phase) {
        Phase.THINKING, Phase.CONNECTING, Phase.RECONNECTING -> 2400
        Phase.SPEAKING -> 9000
        else -> 18000
    }
    val rotation by t.animateFloat(0f, 360f, infiniteRepeatable(tween(spinSpeed, easing = LinearEasing)), label = "rot")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "wave")

    val active = phase != Phase.IDLE && phase != Phase.ERROR
    val path = remember { Path() }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val lv = if (active) level().coerceIn(0f, 1f) else 0f
            val base = this.size.minDimension * 0.62f
            val pulse = 1f + lv * 0.22f + breath * 0.025f
            val shapeSize = base * pulse
            val center = Offset(this.size.width / 2, this.size.height / 2)

            // Soft glow behind everything.
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.glowB.copy(alpha = 0.42f + lv * 0.3f), colors.glowA.copy(alpha = 0.12f), Color.Transparent),
                    center = center, radius = this.size.minDimension / 2,
                ),
                radius = this.size.minDimension / 2, center = center,
            )

            morph.toPath(progress.value, path)

            // Echo rings: same shape, larger, counter-rotating, fading with the level.
            if (active) {
                listOf(1.18f + lv * 0.25f to 0.22f, 1.36f + lv * 0.4f to 0.11f).forEachIndexed { i, (scale, alpha) ->
                    drawUnitPath(path, center, shapeSize * scale, -rotation * (0.6f + i * 0.3f)) {
                        drawPath(path, colors.glowA.copy(alpha = alpha + lv * 0.1f), style = Stroke(width = 0.018f))
                    }
                }
            }

            // The body: a rotating sweep of the brand colours.
            drawUnitPath(path, center, shapeSize, rotation) {
                drawPath(
                    path,
                    Brush.sweepGradient(colors.gradient + colors.gradient.first(), center = Offset(0.5f, 0.5f)),
                )
                drawPath(
                    path,
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.32f), Color.Transparent),
                        center = Offset(0.32f, 0.28f), radius = 0.7f,
                    ),
                )
            }

            // The logo's waveform, alive.
            drawWaveform(center, base * 0.6f, phase, lv, wave, if (scheme.surface.luminance() < 0.5f) BrandCream else Color.White)
        }
    }
}

private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** Draws a 0..1 unit path scaled to [size] around [center], rotated by [degrees]. */
private inline fun DrawScope.drawUnitPath(path: Path, center: Offset, size: Float, degrees: Float, block: DrawScope.() -> Unit) {
    rotate(degrees, center) {
        withTransform({
            translate(center.x - size / 2, center.y - size / 2)
            scale(size, size, pivot = Offset.Zero)
        }) { block() }
    }
}

private val idleHeights = floatArrayOf(0.115f, 0.30f, 0.52f, 0.30f, 0.115f)

private fun DrawScope.drawWaveform(center: Offset, width: Float, phase: Phase, level: Float, wave: Float, color: Color) {
    val barW = width * 0.13f
    val gap = width * 0.19f
    for (i in 0 until 5) {
        val idle = idleHeights[i]
        val h = when (phase) {
            Phase.SPEAKING, Phase.USER_SPEAKING, Phase.LISTENING -> {
                val wobble = (sin(wave + i * 1.3f) + 1f) / 2f
                idle + level * (0.25f + wobble * 0.35f) * (if (i == 2) 1.1f else 0.9f)
            }
            Phase.THINKING, Phase.CONNECTING, Phase.RECONNECTING -> {
                val s = (sin(wave - i * 0.9f) + 1f) / 2f
                0.12f + s * 0.32f
            }
            else -> idle
        }
        val height = max(barW, width * h.coerceAtMost(0.9f))
        val x = center.x + (i - 2) * gap - barW / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(x, center.y - height / 2),
            size = Size(barW, height),
            cornerRadius = CornerRadius(barW / 2, barW / 2),
        )
    }
}
