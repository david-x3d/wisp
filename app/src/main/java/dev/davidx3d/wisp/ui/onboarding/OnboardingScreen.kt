package dev.davidx3d.wisp.ui.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.session.Phase
import dev.davidx3d.wisp.ui.SettingsViewModel
import dev.davidx3d.wisp.ui.components.AuroraBackground
import dev.davidx3d.wisp.ui.components.IconBadge
import dev.davidx3d.wisp.ui.components.Tint
import dev.davidx3d.wisp.ui.components.VoiceOrb
import dev.davidx3d.wisp.i18n.tr

private const val STEPS = 4

/** First start: hello → name → provider & key → microphone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: SettingsViewModel, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf(vm.settings.value.userName) }
    var provider by remember { mutableStateOf(vm.settings.value.provider) }
    var key by remember { mutableStateOf("") }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish(vm, name, provider, key, onDone) }

    // Each step gets its own orb shape, so the hero morphs as you go.
    val orbPhase = when (step) {
        0 -> Phase.IDLE
        1 -> Phase.LISTENING
        2 -> Phase.THINKING
        else -> Phase.SPEAKING
    }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(energy = 0.35f + step * 0.2f, level = { 0f })
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(step, Modifier.padding(top = 20.dp))
            val imeOpen = WindowInsets.isImeVisible
            val orbSize by animateDpAsState(
                when {
                    imeOpen -> 96.dp
                    step == 0 -> 220.dp
                    else -> 150.dp
                },
                label = "orb",
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
            Spacer(Modifier.height(if (imeOpen) 12.dp else 48.dp))
            VoiceOrb(orbPhase, { 0.15f }, size = orbSize)
            Spacer(Modifier.height(if (imeOpen) 12.dp else 24.dp))

            AnimatedContent(
                step,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally { it / 3 * dir } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 * dir } + fadeOut())
                },
                label = "step",
            ) { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when (s) {
                        0 -> {
                            Title(tr("Hi, ich bin Wisp", "Hi, I'm Wisp"))
                            Body(tr("Dein Sprachassistent. Du redest, ich höre zu – und erledige Sachen auf deinem Handy.", "Your voice assistant. You talk, I listen – and get things done on your phone."))
                        }
                        1 -> {
                            Title(tr("Wie heißt du?", "What's your name?"))
                            Body(tr("So spreche ich dich an.", "That's what I'll call you."))
                            Spacer(Modifier.height(24.dp))
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            OutlinedTextField(
                                value = name, onValueChange = { name = it.take(40) },
                                placeholder = { Text(tr("Dein Vorname", "Your first name")) },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { if (name.isNotBlank()) step++ }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                        2 -> {
                            Title(if (name.isBlank()) tr("Wer soll antworten?", "Who should answer?") else tr("Schön, ${name.trim()}!", "Nice to meet you, ${name.trim()}!"))
                            Body(tr("Wähl, mit welcher KI ich spreche. Das kannst du später jederzeit ändern.", "Pick which AI I talk with. You can change this any time."))
                            Spacer(Modifier.height(20.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ProviderCard(Provider.XAI, "Grok", tr("0,08 \$/min · Websuche", "\$0.08/min · web search"), Icons.Rounded.Bolt, provider == Provider.XAI, Modifier.fillMaxWidth()) { provider = it }
                                ProviderCard(Provider.GEMINI, "Gemini", tr("Free Tier verfügbar", "Free tier available"), Icons.Rounded.Hub, provider == Provider.GEMINI, Modifier.fillMaxWidth()) { provider = it }
                                ProviderCard(Provider.OPENAI, "OpenAI", tr("Abrechnung nach Tokens", "Billed per token"), Icons.Rounded.Hub, provider == Provider.OPENAI, Modifier.fillMaxWidth()) { provider = it }
                            }
                            Spacer(Modifier.height(16.dp))
                            var visible by remember { mutableStateOf(false) }
                            OutlinedTextField(
                                value = key, onValueChange = { key = it },
                                label = { Text("${provider.shortLabel} API-Key") },
                                singleLine = true,
                                shape = RoundedCornerShape(20.dp),
                                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { visible = !visible }) {
                                        Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                when (provider) { Provider.XAI -> "console.x.ai → API Keys"; Provider.OPENAI -> "platform.openai.com → API keys"; Provider.GEMINI -> "aistudio.google.com → API keys" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        else -> {
                            Title(tr("Darf ich zuhören?", "May I listen?"))
                            Body(tr("Ich brauche das Mikrofon für Gespräche und zeige eine Benachrichtigung, solange ich zuhöre. Weitere Rechte fragst du später pro Werkzeug frei.", "I need the microphone for conversations and show a notification while I'm listening. You can unlock more permissions per tool later."))
                            Spacer(Modifier.height(16.dp))
                            IconBadge(Icons.Rounded.Mic, Tint.PRIMARY, MaterialShapes.Cookie9Sided, size = 64)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { step-- }) { Text(tr("Zurück", "Back")) }
                if (step == 2) TextButton(onClick = { key = ""; step++ }) { Text(tr("Später", "Later")) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        when (step) {
                            STEPS - 1 -> micLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
                            else -> step++
                        }
                    },
                    enabled = when (step) {
                        1 -> name.isNotBlank()
                        2 -> key.isNotBlank()
                        else -> true
                    },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.height(56.dp),
                ) {
                    Text(
                        when (step) {
                            0 -> tr("Los geht's", "Let's go")
                            STEPS - 1 -> tr("Erlauben & starten", "Allow & start")
                            else -> tr("Weiter", "Next")
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
                }
            }
        }
    }
}

private fun finish(vm: SettingsViewModel, name: String, provider: Provider, key: String, onDone: () -> Unit) {
    if (key.isNotBlank()) vm.saveKey(provider, key)
    vm.update { it.copy(userName = name.trim(), provider = provider, onboardingDone = true) }
    onDone()
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(
        text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepDots(step: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(STEPS) { i ->
            val w by animateDpAsState(if (i == step) 28.dp else 8.dp, label = "dot")
            val c by animateColorAsState(
                if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                label = "dotc",
            )
            Box(Modifier.size(width = w, height = 8.dp).clip(CircleShape).background(c))
        }
    }
}

@Composable
private fun ProviderCard(
    p: Provider, title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean, modifier: Modifier, onSelect: (Provider) -> Unit,
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "pc",
    )
    Surface(
        onClick = { onSelect(p) },
        shape = RoundedCornerShape(28.dp),
        color = bg,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(40.dp).clip((if (p == Provider.XAI) MaterialShapes.Burst else MaterialShapes.Clover8Leaf).toShape())
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
