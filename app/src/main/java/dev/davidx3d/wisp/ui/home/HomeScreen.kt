package dev.davidx3d.wisp.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.davidx3d.wisp.R
import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.session.Phase
import dev.davidx3d.wisp.session.VoiceSessionManager
import dev.davidx3d.wisp.ui.components.AuroraBackground
import dev.davidx3d.wisp.ui.components.ConfirmCard
import dev.davidx3d.wisp.ui.components.ControlDock
import dev.davidx3d.wisp.ui.components.NoticeCard
import dev.davidx3d.wisp.ui.components.Transcript
import dev.davidx3d.wisp.ui.components.VoiceOrb
import dev.davidx3d.wisp.ui.components.phaseLabel
import java.time.LocalTime

private val suggestions = listOf(
    "⏱ Timer 10 Minuten",
    "🌤 Wie wird das Wetter?",
    "💬 Lies meine Nachrichten",
    "📅 Was steht heute an?",
    "🔦 Taschenlampe an",
    "🎵 Was läuft gerade?",
)

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "Guten Morgen"
    in 11..16 -> "Hallo"
    in 17..21 -> "Guten Abend"
    else -> "Noch wach?"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    manager: VoiceSessionManager,
    settings: AppSettings,
    onSettings: () -> Unit,
    onHistory: () -> Unit,
    onNeedsKey: () -> Unit,
) {
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val level = { if (state.phase == Phase.SPEAKING) manager.outputLevel.value else manager.inputLevel.value }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) manager.start()
    }
    fun start(text: String? = null) {
        if (!settings.hasActiveKey) return onNeedsKey()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        } else {
            manager.start(text)
        }
    }

    val inConversation = state.active || state.items.isNotEmpty()

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(energy = if (state.active) 1f else 0.35f, level = level)

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(settings, onSettings, onHistory)

            AnimatedContent(
                inConversation,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "home",
            ) { conversation ->
                if (!conversation) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.clickable(interactionSource = null, indication = null) { start() }) {
                            VoiceOrb(state.phase, level, size = 240.dp)
                        }
                        Spacer(Modifier.height(20.dp))
                        Text("${greeting()}, David", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Frag mich etwas oder sag mir, was ich tun soll.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(28.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            suggestions.forEach { s ->
                                Surface(
                                    onClick = { start(s.substringAfter(' ')) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                                ) {
                                    Text(s, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Transcript(
                            state.items,
                            Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        )
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            VoiceOrb(state.phase, level, size = 150.dp)
                            AnimatedContent(phaseLabel(state), label = "phase") {
                                Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!state.active) {
                                TextButton(onClick = { manager.dismissError(); manager.start() }) { Text("Neues Gespräch") }
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp)) {
                ConfirmCard(state.confirmation, manager::resolveConfirmation, Modifier.padding(bottom = 12.dp))
                AnimatedVisibility(state.error != null) {
                    Box(Modifier.padding(bottom = 12.dp).clickable { manager.dismissError() }) {
                        NoticeCard(state.error ?: "")
                    }
                }
            }
            ControlDock(
                state = state,
                onMain = { if (state.active) manager.stop() else start() },
                onMute = manager::toggleMute,
                onPttDown = manager::pttDown,
                onPttUp = manager::pttUp,
                onSendText = manager::sendText,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun TopBar(settings: AppSettings, onSettings: () -> Unit, onHistory: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_wisp_mark), null, Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Text("Wisp", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(10.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                settings.provider.shortLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalIconButton(onClick = onHistory, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.History, "Verlauf") }
        Spacer(Modifier.width(4.dp))
        FilledTonalIconButton(onClick = onSettings, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Rounded.Settings, "Einstellungen") }
    }
}
