package dev.davidx3d.wisp.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.davidx3d.wisp.data.settings.defaultSystemPrompt
import dev.davidx3d.wisp.i18n.tr
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.ProviderCatalog
import dev.davidx3d.wisp.data.settings.TurnMode
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.ui.SettingsViewModel
import dev.davidx3d.wisp.ui.TestState
import dev.davidx3d.wisp.ui.components.SettingRow
import dev.davidx3d.wisp.ui.components.SettingsScaffold
import dev.davidx3d.wisp.ui.components.StatusPill
import dev.davidx3d.wisp.ui.components.Tint
import dev.davidx3d.wisp.ui.components.WispSwitch
import dev.davidx3d.wisp.ui.components.footnote
import dev.davidx3d.wisp.ui.components.group
import java.util.Locale

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROVIDER = "settings/provider"
    const val VOICE = "settings/voice"
    const val PERSONA = "settings/persona"
    const val CONVERSATION = "settings/conversation"
    const val TOOLS = "settings/tools"
    const val ASSISTANT = "settings/assistant"
    const val USAGE = "settings/usage"
    const val HISTORY = "history"
}

private fun minutes(seconds: Long) = if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
private fun usd(micro: Long) = tr("%.2f $", "$%.2f").format(dev.davidx3d.wisp.i18n.Lang.locale, micro / 1_000_000.0)

@Composable
fun SettingsHome(vm: SettingsViewModel, onBack: () -> Unit, go: (String) -> Unit) {
    val s by vm.settings.collectAsState()
    val isDefault by vm.isDefaultAssistant.collectAsState()
    val tools by vm.tools.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val missing = tools.count { it.enabled && it.missing.isNotEmpty() }

    SettingsScaffold(tr("Einstellungen", "Settings"), onBack) {
        group {
            row { m, sh ->
                SettingRow(
                    tr("Anbieter & API-Keys", "Provider & API keys"), m, sh,
                    subtitle = s.provider.label + if (!s.hasActiveKey) tr(" · Key fehlt", " · key missing") else "",
                    icon = Icons.Rounded.Key, tint = if (s.hasActiveKey) Tint.PRIMARY else Tint.ERROR,
                    badgeShape = MaterialShapes.Cookie9Sided, onClick = { go(Routes.PROVIDER) },
                    trailing = { if (!s.hasActiveKey) StatusPill(tr("fehlt", "missing"), false) },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Stimme & Modell", "Voice & model"), m, sh,
                    subtitle = "${s.voice.replaceFirstChar { it.uppercase() }} · ${s.model}",
                    icon = Icons.Rounded.RecordVoiceOver, tint = Tint.SECONDARY, badgeShape = MaterialShapes.Clover4Leaf,
                    onClick = { go(Routes.VOICE) },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Persönlichkeit", "Personality"), m, sh, subtitle = tr("System-Prompt und Sprache", "System prompt and language"),
                    icon = Icons.Rounded.Face, tint = Tint.TERTIARY, badgeShape = MaterialShapes.Flower,
                    onClick = { go(Routes.PERSONA) },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Gespräch", "Conversation"), m, sh,
                    subtitle = when (s.turnMode) {
                        TurnMode.SERVER_VAD -> tr("Freihändig", "Hands-free")
                        TurnMode.SEMANTIC_VAD -> tr("Freihändig, semantisch", "Hands-free, semantic")
                        TurnMode.PUSH_TO_TALK -> "Push-to-Talk"
                    },
                    icon = Icons.Rounded.GraphicEq, tint = Tint.PRIMARY, badgeShape = MaterialShapes.SoftBurst,
                    onClick = { go(Routes.CONVERSATION) },
                )
            }
        }
        group(tr("Fähigkeiten", "Capabilities")) {
            row { m, sh ->
                SettingRow(
                    tr("Werkzeuge", "Tools"), m, sh,
                    subtitle = tr("${tools.count { it.enabled }} aktiv", "${tools.count { it.enabled }} active") + if (missing > 0) tr(" · $missing brauchen Berechtigungen", " · $missing need permissions") else "",
                    icon = Icons.Rounded.Build, tint = if (missing > 0) Tint.ERROR else Tint.SECONDARY,
                    badgeShape = MaterialShapes.Cookie6Sided, onClick = { go(Routes.TOOLS) },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Als Standard-Assistent", "Default assistant"), m, sh,
                    subtitle = if (isDefault) tr("Wisp startet per Power-Taste oder Wischgeste", "Wisp starts with the power button or swipe gesture") else tr("Noch nicht festgelegt", "Not set yet"),
                    icon = Icons.Rounded.AutoAwesome, tint = if (isDefault) Tint.PRIMARY else Tint.TERTIARY,
                    badgeShape = MaterialShapes.Sunny, onClick = { go(Routes.ASSISTANT) },
                    trailing = { StatusPill(if (isDefault) tr("aktiv", "active") else tr("aus", "off"), isDefault) },
                )
            }
        }
        group(tr("Mehr", "More")) {
            row { m, sh ->
                SettingRow(
                    tr("Nutzung & Kosten", "Usage & cost"), m, sh, subtitle = tr("Gesamt ${minutes(s.totalSeconds)} · ca. ${usd(s.totalCostMicroUsd)}", "Total ${minutes(s.totalSeconds)} · approx. ${usd(s.totalCostMicroUsd)}"),
                    icon = Icons.Rounded.Insights, tint = Tint.NEUTRAL, badgeShape = MaterialShapes.Pentagon,
                    onClick = { go(Routes.USAGE) },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Verlauf", "History"), m, sh, subtitle = tr("Vergangene Gespräche durchsuchen", "Search past conversations"),
                    icon = Icons.Rounded.History, tint = Tint.NEUTRAL, badgeShape = MaterialShapes.Ghostish,
                    onClick = { go(Routes.HISTORY) },
                )
            }
        }
    }
}

@Composable
fun ProviderScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val test by vm.test.collectAsState()
    SettingsScaffold(tr("Anbieter", "Provider"), onBack, subtitle = tr("Wer am anderen Ende zuhört", "Who's listening on the other end")) {
        group(tr("Aktiver Anbieter", "Active provider")) {
            Provider.entries.forEach { p ->
                row { m, sh ->
                    SettingRow(
                        p.label, m, sh,
                        subtitle = if (p == Provider.GEMINI) tr("Live-Audio · Free Tier mit Google API-Key", "Live audio · free tier with Google API key") else if (p == Provider.XAI) tr("grok-voice · 0,08 $ pro Minute · Websuche eingebaut", "grok-voice · $0.08 per minute · built-in web search") else tr("gpt-realtime · Abrechnung nach Tokens", "gpt-realtime · billed per token"),
                        icon = if (p == Provider.XAI) Icons.Rounded.Bolt else Icons.Rounded.Hub,
                        tint = if (s.provider == p) Tint.PRIMARY else Tint.NEUTRAL,
                        badgeShape = if (p == Provider.XAI) MaterialShapes.Burst else MaterialShapes.Clover8Leaf,
                        onClick = { vm.update { it.copy(provider = p) } },
                        trailing = { RadioButton(selected = s.provider == p, onClick = { vm.update { it.copy(provider = p) } }) },
                    )
                }
            }
        }
        Provider.entries.forEach { p ->
            group("${p.shortLabel} API-Key") {
                row { m, sh ->
                    KeyRow(
                        provider = p,
                        hasKey = s.copy(provider = p).hasActiveKey,
                        test = test[p] ?: TestState.Idle,
                        onSave = { vm.saveKey(p, it) },
                        onTest = { vm.testKey(p) },
                        modifier = m, shape = sh,
                    )
                }
            }
        }
        footnote(tr(
            "Keys werden mit einem Schlüssel aus dem Android Keystore verschlüsselt gespeichert und nie geloggt. xAI-Key: console.x.ai → API Keys. OpenAI-Key: platform.openai.com → API keys.",
            "Keys are encrypted with a key from the Android Keystore and never logged. xAI key: console.x.ai → API Keys. OpenAI key: platform.openai.com → API keys.",
        ))
    }
}

@Composable
private fun KeyRow(
    provider: Provider, hasKey: Boolean, test: TestState,
    onSave: (String) -> Unit, onTest: () -> Unit,
    modifier: Modifier, shape: androidx.compose.ui.graphics.Shape,
) {
    var editing by remember { mutableStateOf(!hasKey) }
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    SettingRow(
        if (hasKey) tr("Key gespeichert", "Key saved") else tr("Kein Key", "No key"), modifier, shape,
        subtitle = if (hasKey) tr("••••••••••••  verschlüsselt", "••••••••••••  encrypted") else tr("Füg deinen ${provider.shortLabel}-Key ein", "Paste your ${provider.shortLabel} key"),
        icon = if (hasKey) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
        tint = if (hasKey) Tint.PRIMARY else Tint.ERROR,
        trailing = {
            if (hasKey && !editing) TextButton(onClick = { editing = true }) { Text(tr("Ändern", "Change")) }
        },
        below = {
            AnimatedVisibility(editing) {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text(tr("API-Key", "API key")) },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) tr("Verbergen", "Hide") else tr("Anzeigen", "Show"))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSave(text); text = ""; editing = false; visible = false; onTest() },
                            enabled = text.isNotBlank(), shapes = ButtonDefaults.shapes(),
                        ) { Text(tr("Speichern & testen", "Save & test")) }
                        if (hasKey) {
                            TextButton(onClick = { onSave(""); editing = true }) { Text(tr("Löschen", "Delete")) }
                        }
                    }
                }
            }
            if (hasKey) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = onTest, enabled = test != TestState.Running, shapes = ButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Verbindung testen", "Test connection"))
                    }
                    Spacer(Modifier.width(12.dp))
                    when (test) {
                        TestState.Running -> LoadingIndicator(Modifier.size(32.dp))
                        TestState.Ok -> StatusPill(tr("✓ Funktioniert", "✓ Works"), true)
                        is TestState.Failed -> Unit
                        TestState.Idle -> Unit
                    }
                }
                if (test is TestState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(test.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

@Composable
fun VoiceScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val xaiVoices by vm.xaiVoices.collectAsState()
    val previewing by vm.previewing.collectAsState()
    val voices = if (s.provider == Provider.XAI) xaiVoices else ProviderCatalog.voices(s.provider)
    SettingsScaffold(tr("Stimme & Modell", "Voice & model"), onBack, subtitle = s.provider.label) {
        group(tr("Modell", "Model")) {
            row { m, sh ->
                SettingRow(tr("Realtime-Modell", "Realtime model"), m, sh, icon = Icons.Rounded.Memory, tint = Tint.SECONDARY, below = {
                    Dropdown(
                        options = ProviderCatalog.models(s.provider).map { it.id to "${it.name} – ${it.description}" },
                        selected = s.model,
                        onSelect = { id -> vm.update { when (it.provider) { Provider.XAI -> it.copy(xaiModel = id); Provider.OPENAI -> it.copy(openAiModel = id); Provider.GEMINI -> it.copy(geminiModel = id) } } },
                    )
                })
            }
            if (s.provider == Provider.XAI) {
                row { m, sh ->
                    SettingRow(
                        tr("Nachdenken (Reasoning)", "Reasoning"), m, sh, subtitle = tr("Klügere Antworten, etwas mehr Verzögerung", "Smarter answers, slightly more delay"),
                        icon = Icons.Rounded.Psychology, tint = Tint.TERTIARY,
                        trailing = { WispSwitch(s.xaiReasoning, { on -> vm.update { it.copy(xaiReasoning = on) } }) },
                    )
                }
                row { m, sh ->
                    SettingRow(
                        tr("Websuche", "Web search"), m, sh, subtitle = tr("Grok darf selbst im Web suchen (z. B. Wetter, News)", "Grok may search the web itself (e.g. weather, news)"),
                        icon = Icons.Rounded.TravelExplore, tint = Tint.PRIMARY,
                        trailing = { WispSwitch(s.webSearch, { on -> vm.update { it.copy(webSearch = on) } }) },
                    )
                }
            }
        }
        if (s.provider == Provider.GEMINI) {
            group("Google AI Studio") {
                row { m, sh -> SettingRow("Free Tier", m, sh,
                    subtitle = tr("Nur Kostenschätzung. Dein Google-Projekt bestimmt Abrechnung und Limits. Key: aistudio.google.com", "Cost estimate only. Your Google project determines billing and limits. Key: aistudio.google.com"),
                    trailing = { WispSwitch(s.geminiFreeTier, { on -> vm.update { it.copy(geminiFreeTier = on) } }) }) }
            }
        }
        group(tr("Stimme", "Voice")) {
            voices.forEach { v ->
                row { m, sh ->
                    val selected = s.voice.equals(v.id, ignoreCase = true)
                    SettingRow(
                        v.name, m, sh, subtitle = v.description.ifBlank { null },
                        icon = Icons.Rounded.GraphicEq, tint = if (selected) Tint.PRIMARY else Tint.NEUTRAL,
                        badgeShape = if (selected) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
                        onClick = { vm.update { when (it.provider) { Provider.XAI -> it.copy(xaiVoice = v.id); Provider.OPENAI -> it.copy(openAiVoice = v.id); Provider.GEMINI -> it.copy(geminiVoice = v.id) } } },
                        trailing = {
                            FilledTonalIconButton(onClick = { vm.preview(v) }, enabled = previewing == null && s.provider != Provider.GEMINI, shapes = IconButtonDefaults.shapes()) {
                                if (previewing == v.id) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.PlayArrow, tr("Hörprobe", "Preview"))
                            }
                            RadioButton(selected = selected, onClick = null)
                        },
                    )
                }
            }
        }
        if (s.provider == Provider.GEMINI) footnote(tr("Stimmen hörst du im Live-Gespräch. Free Tier: begrenztes Kontingent, kein automatischer Anbieterwechsel.", "Hear voices in a live conversation. Free tier: limited quota, no automatic provider switch."))
        else footnote(tr("Die Hörprobe nutzt die Text-to-Speech-API des Anbieters und kostet einen Bruchteil eines Cents. Die Stimme gilt ab dem nächsten Gespräch.", "The preview uses the provider's text-to-speech API and costs a fraction of a cent. The voice applies from the next conversation."))
    }
}

@Composable
fun PersonaScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    var prompt by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt.ifBlank { defaultSystemPrompt() }) }
    var name by remember(s.userName) { mutableStateOf(s.userName) }
    SettingsScaffold(tr("Persönlichkeit", "Personality"), onBack) {
        group(tr("Du", "You")) {
            row { m, sh ->
                SettingRow(tr("Dein Name", "Your name"), m, sh, subtitle = tr("So spricht Wisp dich an", "What Wisp calls you"), icon = Icons.Rounded.Person, tint = Tint.PRIMARY,
                    badgeShape = MaterialShapes.Cookie9Sided, below = {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                            shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (name.trim() != s.userName) {
                                    IconButton(onClick = { vm.update { it.copy(userName = name.trim()) } }) { Icon(Icons.Rounded.Check, tr("Speichern", "Save")) }
                                }
                            },
                        )
                    })
            }
        }
        group(tr("Sprache", "Language")) {
            row { m, sh ->
                SettingRow(tr("Sprache", "Language"), m, sh, icon = Icons.Rounded.Translate, tint = Tint.SECONDARY, below = {
                    Dropdown(ProviderCatalog.languages, s.language) { lang -> vm.update { it.copy(language = lang) } }
                })
            }
        }
        group("System-Prompt") {
            row { m, sh ->
                SettingRow(tr("So verhält sich Wisp", "How Wisp behaves"), m, sh, icon = Icons.Rounded.Face, tint = Tint.TERTIARY, badgeShape = MaterialShapes.Flower, below = {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        shape = RoundedCornerShape(18.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.update { it.copy(systemPrompt = prompt) } }, enabled = prompt != s.systemPrompt.ifBlank { defaultSystemPrompt() }, shapes = ButtonDefaults.shapes()) {
                            Text(tr("Speichern", "Save"))
                        }
                        TextButton(onClick = { prompt = defaultSystemPrompt(); vm.update { it.copy(systemPrompt = "") } }) {
                            Text(tr("Standard", "Default"))
                        }
                    }
                })
            }
        }
        footnote(tr("Tipp: Kurz halten. Gesprochene Antworten wirken natürlicher, wenn der Prompt Länge und Ton vorgibt statt Regeln aufzulisten.", "Tip: keep it short. Spoken answers sound more natural when the prompt sets length and tone instead of listing rules."))
    }
}

@Composable
fun ConversationScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    var sens by remember(s.vadSensitivity) { mutableFloatStateOf(s.vadSensitivity) }
    SettingsScaffold(tr("Gespräch", "Conversation"), onBack) {
        group(tr("Wann bin ich fertig mit Reden?", "When am I done talking?")) {
            row { m, sh ->
                SettingRow(tr("Sprecherkennung", "Turn detection"), m, sh, icon = Icons.Rounded.GraphicEq, tint = Tint.PRIMARY, below = {
                    val modes = buildList {
                        add(TurnMode.SERVER_VAD to tr("Automatisch", "Automatic"))
                        if (s.provider == Provider.OPENAI) add(TurnMode.SEMANTIC_VAD to tr("Semantisch", "Semantic"))
                        add(TurnMode.PUSH_TO_TALK to "Push-to-Talk")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween), modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { i, (mode, label) ->
                            ToggleButton(
                                checked = s.turnMode == mode,
                                onCheckedChange = { vm.update { it.copy(turnMode = mode) } },
                                shapes = when (i) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                })
            }
            if (s.turnMode != TurnMode.PUSH_TO_TALK) {
                row { m, sh ->
                    SettingRow(
                        tr("Empfindlichkeit", "Sensitivity"), m, sh,
                        subtitle = when {
                            sens < 0.33f -> tr("Nur deutliche Sprache (laute Umgebung)", "Only clear speech (noisy places)")
                            sens > 0.66f -> tr("Reagiert auch auf leises Sprechen", "Also reacts to quiet speech")
                            else -> tr("Ausgewogen", "Balanced")
                        },
                        icon = Icons.Rounded.Tune, tint = Tint.SECONDARY,
                        below = {
                            Slider(value = sens, onValueChange = { sens = it }, onValueChangeFinished = { vm.update { it.copy(vadSensitivity = sens) } })
                        },
                    )
                }
            }
        }
        group("Audio") {
            row { m, sh ->
                SettingRow(
                    tr("Lautsprecher", "Speaker"), m, sh, subtitle = tr("Ohne Kopfhörer über den Lautsprecher statt die Ohrmuschel", "Without headphones, use the loudspeaker instead of the earpiece"),
                    icon = Icons.Rounded.VolumeUp, tint = Tint.TERTIARY,
                    trailing = { WispSwitch(s.speakerByDefault, { on -> vm.update { it.copy(speakerByDefault = on) } }) },
                )
            }
        }
        footnote(tr(
            "Unterbrechen geht immer: Red einfach rein, dann verstummt Wisp sofort. Echo-Unterdrückung des Handys verhindert, dass Wisp sich selbst hört. Bei xAI wirkt die Empfindlichkeit als Rauschfilter auf dem Handy, bei OpenAI direkt auf die Server-Erkennung.",
            "You can always interrupt: just start talking and Wisp stops right away. The phone's echo cancellation keeps Wisp from hearing itself. With xAI the sensitivity acts as a noise gate on the phone, with OpenAI it tunes the server-side detection.",
        ))
    }
}

@Composable
fun ToolsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val tools by vm.tools.collectAsState()
    val shell by vm.shell.collectAsState()
    val message by vm.message.collectAsState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }

    SettingsScaffold(tr(tr("Werkzeuge", "Tools"), "Tools"), onBack, subtitle = tr("Was Wisp auf dem Handy darf", "What Wisp may do on your phone")) {
        group(tr("Sicherheit", "Safety")) {
            row { m, sh ->
                SettingRow(
                    tr("Anrufe & Nachrichten bestätigen", "Confirm calls & messages"), m, sh, subtitle = tr("Wisp fragt vorher – per „ja“ oder Button", "Wisp asks first – say “yes” or tap the button"),
                    icon = Icons.Rounded.Shield, tint = Tint.TERTIARY, badgeShape = MaterialShapes.Clover4Leaf,
                    trailing = { WispSwitch(s.confirmActions, { on -> vm.update { it.copy(confirmActions = on) } }) },
                )
            }
        }
        if (message != null) {
            item {
                LaunchedEffect(message) { kotlinx.coroutines.delay(4000); vm.consumeMessage() }
                Text(message ?: "", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
        }
        ToolCategory.entries.forEach { cat ->
            val list = tools.filter { it.tool.category == cat }
            if (list.isEmpty()) return@forEach
            group(cat.title) {
                if (cat == ToolCategory.SYSTEM) {
                    row { m, sh ->
                        SettingRow(
                            tr("Systemsteuerung erlauben", "Allow system control"), m, sh,
                            subtitle = when (shell.backend) {
                                dev.davidx3d.wisp.tools.system.Backend.SHIZUKU -> tr("Bereit über Shizuku", "Ready via Shizuku")
                                dev.davidx3d.wisp.tools.system.Backend.ROOT -> tr("Bereit über Root (KernelSU)", "Ready via root (KernelSU)")
                                null -> tr("WLAN, Flugmodus & Co. brauchen Shizuku oder Root", "Wi-Fi, airplane mode & co. need Shizuku or root")
                            },
                            icon = Icons.Rounded.AdminPanelSettings, tint = if (shell.available) Tint.PRIMARY else Tint.ERROR,
                            badgeShape = MaterialShapes.Gem,
                            trailing = { WispSwitch(s.systemControlEnabled, { on -> vm.update { it.copy(systemControlEnabled = on) } }) },
                            below = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = vm::requestShizuku, enabled = shell.shizukuRunning && !shell.shizukuGranted, shapes = ButtonDefaults.shapes()) {
                                        Text(
                                            when {
                                                !shell.shizukuRunning -> tr("Shizuku läuft nicht", "Shizuku not running")
                                                shell.shizukuGranted -> "Shizuku ✓"
                                                else -> tr("Shizuku erlauben", "Allow Shizuku")
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    row { m, sh ->
                        SettingRow(
                            tr("Root als Ausweichweg", "Root as fallback"), m, sh, subtitle = if (shell.rootGranted) tr("KernelSU hat Wisp Root gegeben", "KernelSU granted Wisp root") else tr("Nur nutzen, wenn Shizuku nicht läuft", "Only used when Shizuku isn't running"),
                            icon = Icons.Rounded.Tag, tint = Tint.NEUTRAL,
                            trailing = {
                                WispSwitch(s.rootEnabled, { on ->
                                    vm.update { it.copy(rootEnabled = on) }
                                    if (on) vm.requestRoot()
                                })
                            },
                        )
                    }
                }
                list.forEach { st ->
                    row { m, sh ->
                        val tool = st.tool
                        val needsSystem = cat == ToolCategory.SYSTEM && !s.systemControlEnabled
                        SettingRow(
                            tool.title, m, sh, subtitle = tool.description,
                            trailing = {
                                WispSwitch(
                                    tool.name !in s.disabledTools,
                                    { on -> vm.setToolEnabled(tool, on) },
                                    enabled = !needsSystem,
                                )
                            },
                            below = if (st.missing.isEmpty() && tool.optionalAccess.all { a -> vm.permissions.hasSpecial(a) }) null else {
                                {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (st.missing.isNotEmpty()) StatusPill(tr("Fehlt: ${st.missing.joinToString()}", "Missing: ${st.missing.joinToString()}"), false)
                                        else StatusPill("Optional: ${tool.optionalAccess.filterNot { vm.permissions.hasSpecial(it) }.joinToString { it.title }}", true)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    FilledTonalButton(
                                        onClick = {
                                            val runtime = tool.permissions.filterNot(vm.permissions::hasPermission)
                                            val special = (tool.specialAccess + tool.optionalAccess).firstOrNull { !vm.permissions.hasSpecial(it) }
                                            when {
                                                runtime.isNotEmpty() -> launcher.launch(runtime.toTypedArray())
                                                special == SpecialAccess.PRIVILEGED -> vm.requestShizuku()
                                                special != null -> vm.specialIntent(special)?.let { context.startActivity(it) }
                                            }
                                        },
                                        shapes = ButtonDefaults.shapes(),
                                    ) {
                                        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(tr("Berechtigung erteilen", "Grant permission"))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        footnote(tr(
            "Shizuku: Shizuku-App installieren und per kabellosem Debugging starten, dann hier „Shizuku erlauben“. Ohne Shizuku/Root öffnet Wisp bei WLAN & Co. den passenden System-Schalter.",
            "Shizuku: install the Shizuku app and start it via wireless debugging, then tap “Allow Shizuku” here. Without Shizuku/root Wisp opens the matching system switch for Wi-Fi & co.",
        ))
    }
}

@Composable
fun AssistantScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val isDefault by vm.isDefaultAssistant.collectAsState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    SettingsScaffold(tr("Standard-Assistent", "Default assistant"), onBack) {
        group {
            row { m, sh ->
                SettingRow(
                    if (isDefault) tr("Wisp ist dein Assistent", "Wisp is your assistant") else tr("Wisp als Assistent festlegen", "Set Wisp as assistant"), m, sh,
                    subtitle = tr("Startet per langem Druck auf die Power-Taste, Wischen aus der unteren Ecke oder die Assist-Geste – als Overlay über der aktuellen App.", "Starts with a long press on the power button, a swipe from the bottom corner or the assist gesture – as an overlay on top of the current app."),
                    icon = Icons.Rounded.AutoAwesome, tint = if (isDefault) Tint.PRIMARY else Tint.TERTIARY, badgeShape = MaterialShapes.Sunny,
                    trailing = { StatusPill(if (isDefault) tr("aktiv", "active") else tr("aus", "off"), isDefault) },
                    below = {
                        Button(
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                                    .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(tr("Zur Systemeinstellung", "Open system setting"))
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                        }
                    },
                )
            }
            row { m, sh ->
                SettingRow(
                    tr("Grundrechte erteilen", "Grant basic permissions"), m, sh, subtitle = tr("Mikrofon und Benachrichtigung für das laufende Gespräch", "Microphone and notification for the ongoing conversation"),
                    icon = Icons.Rounded.Mic, tint = Tint.SECONDARY,
                    onClick = { mic.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                )
            }
        }
        footnote(tr(
            "In der Systemeinstellung „Digitaler Assistent“ Wisp auswählen. Auf Pixel: Einstellungen → Apps → Standard-Apps → Digitaler Assistent. Für „Bildschirm vorlesen“ dort zusätzlich „Text auf dem Bildschirm verwenden“ einschalten.",
            "Pick Wisp in the system setting “Digital assistant app”. On Pixel: Settings → Apps → Default apps → Digital assistant app. For “Read screen”, also turn on “Use text from screen” there.",
        ))
        group(tr("Weitere Starter", "Other launchers")) {
            row { m, sh -> SettingRow(tr("Schnelleinstellungs-Kachel", "Quick Settings tile"), m, sh, subtitle = tr("Kacheln bearbeiten → „Wisp“ hinzufügen", "Edit tiles → add “Wisp”"), icon = Icons.Rounded.GridView, tint = Tint.NEUTRAL) }
            row { m, sh -> SettingRow(tr("Homescreen-Widget", "Home screen widget"), m, sh, subtitle = tr("Lange auf den Homescreen drücken → Widgets → Wisp", "Long-press the home screen → Widgets → Wisp"), icon = Icons.Rounded.Widgets, tint = Tint.NEUTRAL) }
        }
    }
}

@Composable
fun UsageScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val session by vm.manager.state.collectAsState()
    SettingsScaffold(tr("Nutzung & Kosten", "Usage & cost"), onBack) {
        group(tr("Dieses Gespräch", "This conversation")) {
            row { m, sh ->
                SettingRow(
                    minutes(session.seconds), m, sh, subtitle = tr("ca. ${usd(session.costMicroUsd)} · ${session.provider.shortLabel}", "approx. ${usd(session.costMicroUsd)} · ${session.provider.shortLabel}"),
                    icon = Icons.Rounded.Timer, tint = Tint.PRIMARY, badgeShape = MaterialShapes.Cookie9Sided,
                )
            }
        }
        group(tr("Gesamt", "Total")) {
            row { m, sh ->
                SettingRow(
                    minutes(s.totalSeconds), m, sh, subtitle = tr("ca. ${usd(s.totalCostMicroUsd)} insgesamt", "approx. ${usd(s.totalCostMicroUsd)} in total"),
                    icon = Icons.Rounded.Insights, tint = Tint.SECONDARY, badgeShape = MaterialShapes.Pentagon,
                    trailing = { TextButton(onClick = vm::resetUsage) { Text(tr("Zurücksetzen", "Reset")) } },
                )
            }
        }
        footnote(tr(
            "Schätzung: xAI berechnet 0,08 $ pro Minute Gespräch. Bei OpenAI rechnet Wisp mit den Token-Zahlen jeder Antwort (Audio-Eingabe 32 $, Audio-Ausgabe 64 $ pro 1 Mio. Tokens). Maßgeblich ist die Abrechnung des Anbieters.",
            "Estimate: xAI charges $0.08 per minute of conversation. For OpenAI, Wisp uses the token counts of each response (audio input $32, audio output $64 per 1M tokens). The provider's bill is what counts.",
        ))
    }
}

@Composable
private fun Dropdown(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second ?: selected, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Rounded.UnfoldMore, null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = { onSelect(id); open = false },
                    trailingIcon = if (id == selected) ({ Icon(Icons.Rounded.Check, null) }) else null,
                )
            }
        }
    }
}
