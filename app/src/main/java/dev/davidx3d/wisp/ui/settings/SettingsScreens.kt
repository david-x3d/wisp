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
import dev.davidx3d.wisp.data.settings.DEFAULT_SYSTEM_PROMPT
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
private fun usd(micro: Long) = "%.2f $".format(Locale.GERMAN, micro / 1_000_000.0)

@Composable
fun SettingsHome(vm: SettingsViewModel, onBack: () -> Unit, go: (String) -> Unit) {
    val s by vm.settings.collectAsState()
    val isDefault by vm.isDefaultAssistant.collectAsState()
    val tools by vm.tools.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val missing = tools.count { it.enabled && it.missing.isNotEmpty() }

    SettingsScaffold("Einstellungen", onBack) {
        group {
            row { m, sh ->
                SettingRow(
                    "Anbieter & API-Keys", m, sh,
                    subtitle = s.provider.label + if (!s.hasActiveKey) " · Key fehlt" else "",
                    icon = Icons.Rounded.Key, tint = if (s.hasActiveKey) Tint.PRIMARY else Tint.ERROR,
                    badgeShape = MaterialShapes.Cookie9Sided, onClick = { go(Routes.PROVIDER) },
                    trailing = { if (!s.hasActiveKey) StatusPill("fehlt", false) },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Stimme & Modell", m, sh,
                    subtitle = "${s.voice.replaceFirstChar { it.uppercase() }} · ${s.model}",
                    icon = Icons.Rounded.RecordVoiceOver, tint = Tint.SECONDARY, badgeShape = MaterialShapes.Clover4Leaf,
                    onClick = { go(Routes.VOICE) },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Persönlichkeit", m, sh, subtitle = "System-Prompt und Sprache",
                    icon = Icons.Rounded.Face, tint = Tint.TERTIARY, badgeShape = MaterialShapes.Flower,
                    onClick = { go(Routes.PERSONA) },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Gespräch", m, sh,
                    subtitle = when (s.turnMode) {
                        TurnMode.SERVER_VAD -> "Freihändig"
                        TurnMode.SEMANTIC_VAD -> "Freihändig, semantisch"
                        TurnMode.PUSH_TO_TALK -> "Push-to-Talk"
                    },
                    icon = Icons.Rounded.GraphicEq, tint = Tint.PRIMARY, badgeShape = MaterialShapes.SoftBurst,
                    onClick = { go(Routes.CONVERSATION) },
                )
            }
        }
        group("Fähigkeiten") {
            row { m, sh ->
                SettingRow(
                    "Werkzeuge", m, sh,
                    subtitle = "${tools.count { it.enabled }} aktiv" + if (missing > 0) " · $missing brauchen Berechtigungen" else "",
                    icon = Icons.Rounded.Build, tint = if (missing > 0) Tint.ERROR else Tint.SECONDARY,
                    badgeShape = MaterialShapes.Cookie6Sided, onClick = { go(Routes.TOOLS) },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Als Standard-Assistent", m, sh,
                    subtitle = if (isDefault) "Wisp startet per Power-Taste oder Wischgeste" else "Noch nicht festgelegt",
                    icon = Icons.Rounded.AutoAwesome, tint = if (isDefault) Tint.PRIMARY else Tint.TERTIARY,
                    badgeShape = MaterialShapes.Sunny, onClick = { go(Routes.ASSISTANT) },
                    trailing = { StatusPill(if (isDefault) "aktiv" else "aus", isDefault) },
                )
            }
        }
        group("Mehr") {
            row { m, sh ->
                SettingRow(
                    "Nutzung & Kosten", m, sh, subtitle = "Gesamt ${minutes(s.totalSeconds)} · ca. ${usd(s.totalCostMicroUsd)}",
                    icon = Icons.Rounded.Insights, tint = Tint.NEUTRAL, badgeShape = MaterialShapes.Pentagon,
                    onClick = { go(Routes.USAGE) },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Verlauf", m, sh, subtitle = "Vergangene Gespräche durchsuchen",
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
    SettingsScaffold("Anbieter", onBack, subtitle = "Wer am anderen Ende zuhört") {
        group("Aktiver Anbieter") {
            Provider.entries.forEach { p ->
                row { m, sh ->
                    SettingRow(
                        p.label, m, sh,
                        subtitle = if (p == Provider.XAI) "grok-voice · 0,08 $ pro Minute · Websuche eingebaut" else "gpt-realtime · Abrechnung nach Tokens",
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
                        hasKey = if (p == Provider.XAI) s.hasXaiKey else s.hasOpenAiKey,
                        test = test[p] ?: TestState.Idle,
                        onSave = { vm.saveKey(p, it) },
                        onTest = { vm.testKey(p) },
                        modifier = m, shape = sh,
                    )
                }
            }
        }
        footnote("Keys werden mit einem Schlüssel aus dem Android Keystore verschlüsselt gespeichert und nie geloggt. " +
            "xAI-Key: console.x.ai → API Keys. OpenAI-Key: platform.openai.com → API keys.")
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
        if (hasKey) "Key gespeichert" else "Kein Key", modifier, shape,
        subtitle = if (hasKey) "••••••••••••  verschlüsselt" else "Füg deinen ${provider.shortLabel}-Key ein",
        icon = if (hasKey) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
        tint = if (hasKey) Tint.PRIMARY else Tint.ERROR,
        trailing = {
            if (hasKey && !editing) TextButton(onClick = { editing = true }) { Text("Ändern") }
        },
        below = {
            AnimatedVisibility(editing) {
                Column {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("API-Key") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { visible = !visible }) {
                                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Verbergen" else "Anzeigen")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSave(text); text = ""; editing = false; visible = false; onTest() },
                            enabled = text.isNotBlank(), shapes = ButtonDefaults.shapes(),
                        ) { Text("Speichern & testen") }
                        if (hasKey) {
                            TextButton(onClick = { onSave(""); editing = true }) { Text("Löschen") }
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
                        Text("Verbindung testen")
                    }
                    Spacer(Modifier.width(12.dp))
                    when (test) {
                        TestState.Running -> LoadingIndicator(Modifier.size(32.dp))
                        TestState.Ok -> StatusPill("✓ Funktioniert", true)
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
    val voices = if (s.provider == Provider.XAI) xaiVoices else ProviderCatalog.openAiVoices
    SettingsScaffold("Stimme & Modell", onBack, subtitle = s.provider.label) {
        group("Modell") {
            row { m, sh ->
                SettingRow("Realtime-Modell", m, sh, icon = Icons.Rounded.Memory, tint = Tint.SECONDARY, below = {
                    Dropdown(
                        options = ProviderCatalog.models(s.provider).map { it.id to "${it.name} – ${it.description}" },
                        selected = s.model,
                        onSelect = { id -> vm.update { if (it.provider == Provider.XAI) it.copy(xaiModel = id) else it.copy(openAiModel = id) } },
                    )
                })
            }
            if (s.provider == Provider.XAI) {
                row { m, sh ->
                    SettingRow(
                        "Nachdenken (Reasoning)", m, sh, subtitle = "Klügere Antworten, etwas mehr Verzögerung",
                        icon = Icons.Rounded.Psychology, tint = Tint.TERTIARY,
                        trailing = { WispSwitch(s.xaiReasoning, { on -> vm.update { it.copy(xaiReasoning = on) } }) },
                    )
                }
                row { m, sh ->
                    SettingRow(
                        "Websuche", m, sh, subtitle = "Grok darf selbst im Web suchen (z. B. Wetter, News)",
                        icon = Icons.Rounded.TravelExplore, tint = Tint.PRIMARY,
                        trailing = { WispSwitch(s.webSearch, { on -> vm.update { it.copy(webSearch = on) } }) },
                    )
                }
            }
        }
        group("Stimme") {
            voices.forEach { v ->
                row { m, sh ->
                    val selected = s.voice.equals(v.id, ignoreCase = true)
                    SettingRow(
                        v.name, m, sh, subtitle = v.description.ifBlank { null },
                        icon = Icons.Rounded.GraphicEq, tint = if (selected) Tint.PRIMARY else Tint.NEUTRAL,
                        badgeShape = if (selected) MaterialShapes.Cookie9Sided else MaterialShapes.Circle,
                        onClick = { vm.update { if (it.provider == Provider.XAI) it.copy(xaiVoice = v.id) else it.copy(openAiVoice = v.id) } },
                        trailing = {
                            FilledTonalIconButton(onClick = { vm.preview(v) }, enabled = previewing == null, shapes = IconButtonDefaults.shapes()) {
                                if (previewing == v.id) LoadingIndicator(Modifier.size(24.dp)) else Icon(Icons.Rounded.PlayArrow, "Hörprobe")
                            }
                            RadioButton(selected = selected, onClick = null)
                        },
                    )
                }
            }
        }
        footnote("Die Hörprobe nutzt die Text-to-Speech-API des Anbieters und kostet einen Bruchteil eines Cents. Die Stimme gilt ab dem nächsten Gespräch.")
    }
}

@Composable
fun PersonaScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    var prompt by remember(s.systemPrompt) { mutableStateOf(s.systemPrompt) }
    SettingsScaffold("Persönlichkeit", onBack) {
        group("Sprache") {
            row { m, sh ->
                SettingRow("Sprache", m, sh, icon = Icons.Rounded.Translate, tint = Tint.SECONDARY, below = {
                    Dropdown(ProviderCatalog.languages, s.language) { lang -> vm.update { it.copy(language = lang) } }
                })
            }
        }
        group("System-Prompt") {
            row { m, sh ->
                SettingRow("So verhält sich Wisp", m, sh, icon = Icons.Rounded.Face, tint = Tint.TERTIARY, badgeShape = MaterialShapes.Flower, below = {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        shape = RoundedCornerShape(18.dp),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.update { it.copy(systemPrompt = prompt) } }, enabled = prompt != s.systemPrompt, shapes = ButtonDefaults.shapes()) {
                            Text("Speichern")
                        }
                        TextButton(onClick = { prompt = DEFAULT_SYSTEM_PROMPT; vm.update { it.copy(systemPrompt = DEFAULT_SYSTEM_PROMPT) } }) {
                            Text("Standard")
                        }
                    }
                })
            }
        }
        footnote("Tipp: Kurz halten. Gesprochene Antworten wirken natürlicher, wenn der Prompt Länge und Ton vorgibt statt Regeln aufzulisten.")
    }
}

@Composable
fun ConversationScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    var sens by remember(s.vadSensitivity) { mutableFloatStateOf(s.vadSensitivity) }
    SettingsScaffold("Gespräch", onBack) {
        group("Wann bin ich fertig mit Reden?") {
            row { m, sh ->
                SettingRow("Sprecherkennung", m, sh, icon = Icons.Rounded.GraphicEq, tint = Tint.PRIMARY, below = {
                    val modes = buildList {
                        add(TurnMode.SERVER_VAD to "Automatisch")
                        if (s.provider == Provider.OPENAI) add(TurnMode.SEMANTIC_VAD to "Semantisch")
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
                        "Empfindlichkeit", m, sh,
                        subtitle = when {
                            sens < 0.33f -> "Nur deutliche Sprache (laute Umgebung)"
                            sens > 0.66f -> "Reagiert auch auf leises Sprechen"
                            else -> "Ausgewogen"
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
                    "Lautsprecher", m, sh, subtitle = "Ohne Kopfhörer über den Lautsprecher statt die Ohrmuschel",
                    icon = Icons.Rounded.VolumeUp, tint = Tint.TERTIARY,
                    trailing = { WispSwitch(s.speakerByDefault, { on -> vm.update { it.copy(speakerByDefault = on) } }) },
                )
            }
        }
        footnote("Unterbrechen geht immer: Red einfach rein, dann verstummt Wisp sofort. Echo-Unterdrückung des Handys verhindert, dass Wisp sich selbst hört. " +
            "Bei xAI wirkt die Empfindlichkeit als Rauschfilter auf dem Handy, bei OpenAI direkt auf die Server-Erkennung.")
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

    SettingsScaffold("Werkzeuge", onBack, subtitle = "Was Wisp auf dem Handy darf") {
        group("Sicherheit") {
            row { m, sh ->
                SettingRow(
                    "Anrufe & Nachrichten bestätigen", m, sh, subtitle = "Wisp fragt vorher – per „ja“ oder Button",
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
                            "Systemsteuerung erlauben", m, sh,
                            subtitle = when (shell.backend) {
                                dev.davidx3d.wisp.tools.system.Backend.SHIZUKU -> "Bereit über Shizuku"
                                dev.davidx3d.wisp.tools.system.Backend.ROOT -> "Bereit über Root (KernelSU)"
                                null -> "WLAN, Flugmodus & Co. brauchen Shizuku oder Root"
                            },
                            icon = Icons.Rounded.AdminPanelSettings, tint = if (shell.available) Tint.PRIMARY else Tint.ERROR,
                            badgeShape = MaterialShapes.Gem,
                            trailing = { WispSwitch(s.systemControlEnabled, { on -> vm.update { it.copy(systemControlEnabled = on) } }) },
                            below = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = vm::requestShizuku, enabled = shell.shizukuRunning && !shell.shizukuGranted, shapes = ButtonDefaults.shapes()) {
                                        Text(
                                            when {
                                                !shell.shizukuRunning -> "Shizuku läuft nicht"
                                                shell.shizukuGranted -> "Shizuku ✓"
                                                else -> "Shizuku erlauben"
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    row { m, sh ->
                        SettingRow(
                            "Root als Ausweichweg", m, sh, subtitle = if (shell.rootGranted) "KernelSU hat Wisp Root gegeben" else "Nur nutzen, wenn Shizuku nicht läuft",
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
                                        if (st.missing.isNotEmpty()) StatusPill("Fehlt: ${st.missing.joinToString()}", false)
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
                                        Text("Berechtigung erteilen")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        footnote("Shizuku: Shizuku-App installieren und per kabellosem Debugging starten, dann hier „Shizuku erlauben“. " +
            "Ohne Shizuku/Root öffnet Wisp bei WLAN & Co. den passenden System-Schalter.")
    }
}

@Composable
fun AssistantScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val isDefault by vm.isDefaultAssistant.collectAsState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    SettingsScaffold("Standard-Assistent", onBack) {
        group {
            row { m, sh ->
                SettingRow(
                    if (isDefault) "Wisp ist dein Assistent" else "Wisp als Assistent festlegen", m, sh,
                    subtitle = "Startet per langem Druck auf die Power-Taste, Wischen aus der unteren Ecke oder die Assist-Geste – als Overlay über der aktuellen App.",
                    icon = Icons.Rounded.AutoAwesome, tint = if (isDefault) Tint.PRIMARY else Tint.TERTIARY, badgeShape = MaterialShapes.Sunny,
                    trailing = { StatusPill(if (isDefault) "aktiv" else "aus", isDefault) },
                    below = {
                        Button(
                            onClick = {
                                runCatching { context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                                    .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text("Zur Systemeinstellung")
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
                        }
                    },
                )
            }
            row { m, sh ->
                SettingRow(
                    "Grundrechte erteilen", m, sh, subtitle = "Mikrofon und Benachrichtigung für das laufende Gespräch",
                    icon = Icons.Rounded.Mic, tint = Tint.SECONDARY,
                    onClick = { mic.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
                )
            }
        }
        footnote("In der Systemeinstellung „Digitaler Assistent“ Wisp auswählen. Auf Pixel: Einstellungen → Apps → Standard-Apps → Digitaler Assistent. " +
            "Für „Bildschirm vorlesen“ dort zusätzlich „Text auf dem Bildschirm verwenden“ einschalten.")
        group("Weitere Starter") {
            row { m, sh -> SettingRow("Schnelleinstellungs-Kachel", m, sh, subtitle = "Kacheln bearbeiten → „Wisp“ hinzufügen", icon = Icons.Rounded.GridView, tint = Tint.NEUTRAL) }
            row { m, sh -> SettingRow("Homescreen-Widget", m, sh, subtitle = "Lange auf den Homescreen drücken → Widgets → Wisp", icon = Icons.Rounded.Widgets, tint = Tint.NEUTRAL) }
        }
    }
}

@Composable
fun UsageScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsState()
    val session by vm.manager.state.collectAsState()
    SettingsScaffold("Nutzung & Kosten", onBack) {
        group("Dieses Gespräch") {
            row { m, sh ->
                SettingRow(
                    minutes(session.seconds), m, sh, subtitle = "ca. ${usd(session.costMicroUsd)} · ${session.provider.shortLabel}",
                    icon = Icons.Rounded.Timer, tint = Tint.PRIMARY, badgeShape = MaterialShapes.Cookie9Sided,
                )
            }
        }
        group("Gesamt") {
            row { m, sh ->
                SettingRow(
                    minutes(s.totalSeconds), m, sh, subtitle = "ca. ${usd(s.totalCostMicroUsd)} insgesamt",
                    icon = Icons.Rounded.Insights, tint = Tint.SECONDARY, badgeShape = MaterialShapes.Pentagon,
                    trailing = { TextButton(onClick = vm::resetUsage) { Text("Zurücksetzen") } },
                )
            }
        }
        footnote("Schätzung: xAI berechnet 0,08 $ pro Minute Gespräch. Bei OpenAI rechnet Wisp mit den Token-Zahlen jeder Antwort " +
            "(Audio-Eingabe 32 $, Audio-Ausgabe 64 $ pro 1 Mio. Tokens). Maßgeblich ist die Abrechnung des Anbieters.")
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
