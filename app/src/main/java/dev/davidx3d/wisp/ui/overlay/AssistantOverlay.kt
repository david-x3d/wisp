package dev.davidx3d.wisp.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.davidx3d.wisp.R
import dev.davidx3d.wisp.session.Phase
import dev.davidx3d.wisp.session.VoiceSessionManager
import dev.davidx3d.wisp.ui.components.AuroraBackground
import dev.davidx3d.wisp.ui.components.ConfirmCard
import dev.davidx3d.wisp.ui.components.ControlDock
import dev.davidx3d.wisp.ui.components.NoticeCard
import dev.davidx3d.wisp.ui.components.Transcript
import dev.davidx3d.wisp.ui.components.VoiceOrb
import dev.davidx3d.wisp.ui.components.phaseLabel
import kotlinx.coroutines.launch
import dev.davidx3d.wisp.i18n.tr
import kotlin.math.roundToInt

/**
 * Gemini-style assistant sheet over the current app. Half height by default; drag up for
 * full screen, drag down (or tap outside) to close.
 */
@Composable
fun AssistantOverlay(manager: VoiceSessionManager, onDismiss: () -> Unit, onOpenApp: () -> Unit) {
    val state by manager.state.collectAsState()
    val level = { if (state.phase == Phase.SPEAKING) manager.outputLevel.value else manager.inputLevel.value }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fullPx = with(density) { maxHeight.toPx() }
        val collapsed = fullPx * 0.48f
        val expanded = fullPx - with(density) { 36.dp.toPx() }
        val height = remember { Animatable(collapsed) }

        fun settle(velocity: Float) = scope.launch {
            val h = height.value
            val target = when {
                velocity > 2500f || h < collapsed * 0.7f -> 0f
                velocity < -1500f || h > (collapsed + expanded) / 2 -> expanded
                else -> collapsed
            }
            height.animateTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), -velocity)
            if (target == 0f) onDismiss()
        }

        // Scrim: tap outside closes.
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))))
                .clickable(interactionSource = null, indication = null) { scope.launch { height.animateTo(0f); onDismiss() } },
        )

        AnimatedVisibility(shown, enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            val dragState = rememberDraggableState { delta ->
                scope.launch { height.snapTo((height.value - delta).coerceIn(0f, expanded)) }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { height.value.toDp() })
                    .padding(horizontal = 8.dp)
                    .clip(RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp, bottomStart = 28.dp, bottomEnd = 28.dp))
                    .clickable(interactionSource = null, indication = null) {},
            ) {
                AuroraBackground(energy = if (state.active) 0.9f else 0.3f, level = level, anchorY = 1.0f)
                Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
                    // Drag handle + header
                    Column(
                        Modifier.fillMaxWidth().draggable(dragState, Orientation.Vertical, onDragStopped = { v -> settle(v) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.padding(top = 10.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.ic_wisp_mark), null, Modifier.size(28.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Wisp", style = MaterialTheme.typography.titleLarge)
                                AnimatedContent(phaseLabel(state), label = "ph") {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            FilledTonalIconButton(
                                onClick = { scope.launch { height.animateTo(if (height.value < expanded - 1) expanded else collapsed) } },
                                shapes = IconButtonDefaults.shapes(),
                            ) { Icon(Icons.Rounded.OpenInFull, tr("Vergrößern", "Expand")) }
                            FilledTonalIconButton(onClick = onOpenApp, shapes = IconButtonDefaults.shapes()) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, tr("In App öffnen", "Open in app"))
                            }
                            FilledTonalIconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Close, tr("Schließen", "Close")) }
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (state.items.isEmpty()) {
                            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                VoiceOrb(state.phase, level, size = 170.dp)
                            }
                        } else {
                            Transcript(state.items, Modifier.fillMaxSize(), PaddingValues(horizontal = 14.dp, vertical = 8.dp), compact = true)
                        }
                    }

                    Column(Modifier.padding(horizontal = 12.dp)) {
                        ConfirmCard(state.confirmation, manager::resolveConfirmation, Modifier.padding(bottom = 10.dp))
                        state.error?.let { err ->
                            Box(Modifier.padding(bottom = 10.dp).clickable { manager.dismissError() }) { NoticeCard(err) }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.items.isNotEmpty()) {
                            VoiceOrb(state.phase, level, size = 76.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        ControlDock(
                            state = state,
                            onMain = { if (state.active) manager.stop() else manager.start() },
                            onMute = manager::toggleMute,
                            onPttDown = manager::pttDown,
                            onPttUp = manager::pttUp,
                            onSendText = manager::sendText,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
