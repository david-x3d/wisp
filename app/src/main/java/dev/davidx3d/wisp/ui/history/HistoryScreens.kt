package dev.davidx3d.wisp.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.davidx3d.wisp.session.ChatItem
import dev.davidx3d.wisp.session.Role
import dev.davidx3d.wisp.ui.HistoryViewModel
import dev.davidx3d.wisp.ui.components.SettingRow
import dev.davidx3d.wisp.ui.components.SettingsScaffold
import dev.davidx3d.wisp.ui.components.Tint
import dev.davidx3d.wisp.ui.components.Transcript
import dev.davidx3d.wisp.ui.components.footnote
import dev.davidx3d.wisp.ui.components.group
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.davidx3d.wisp.i18n.tr

private val fmt get() = DateTimeFormatter.ofPattern(tr("EEE, d. MMM · HH:mm", "EEE, MMM d · HH:mm"), dev.davidx3d.wisp.i18n.Lang.locale)

@Composable
fun HistoryScreen(vm: HistoryViewModel, onBack: () -> Unit, open: (Long) -> Unit) {
    val list by vm.conversations.collectAsState()
    val q by vm.query.collectAsState()
    var confirmAll by remember { mutableStateOf(false) }
    SettingsScaffold(tr("Verlauf", "History"), onBack, subtitle = tr("Lokal auf diesem Handy gespeichert", "Stored only on this phone")) {
        item {
            OutlinedTextField(
                value = q, onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text(tr("Gespräche durchsuchen", "Search conversations")) },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (list.isEmpty()) {
            footnote(if (q.isBlank()) tr("Noch keine Gespräche.", "No conversations yet.") else tr("Nichts gefunden.", "Nothing found."))
        } else {
            group(tr("${list.size} Gespräche", "${list.size} conversations")) {
                list.forEach { c ->
                    row { m, sh ->
                        SettingRow(
                            c.title, m, sh,
                            subtitle = "${Instant.ofEpochMilli(c.startedAt).atZone(ZoneId.systemDefault()).format(fmt)} · ${c.provider} · ${c.durationSeconds / 60} min",
                            icon = Icons.Rounded.ChatBubble, tint = Tint.SECONDARY, badgeShape = MaterialShapes.Cookie6Sided,
                            onClick = { open(c.id) },
                            trailing = {
                                FilledTonalIconButton(onClick = { vm.delete(c.id) }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(Icons.Rounded.DeleteOutline, tr("Löschen", "Delete"))
                                }
                            },
                        )
                    }
                }
            }
            group {
                row { m, sh ->
                    SettingRow(tr("Alles löschen", "Delete all"), m, sh, icon = Icons.Rounded.DeleteSweep, tint = Tint.ERROR, onClick = { confirmAll = true })
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(tr("Ganzen Verlauf löschen?", "Delete all history?")) },
            text = { Text(tr("Alle gespeicherten Gespräche werden entfernt. Das lässt sich nicht rückgängig machen.", "All saved conversations will be removed. This can't be undone.")) },
            confirmButton = { TextButton(onClick = { vm.deleteAll(); confirmAll = false }) { Text(tr("Löschen", "Delete")) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(tr("Abbrechen", "Cancel")) } },
        )
    }
}

@Composable
fun HistoryDetailScreen(vm: HistoryViewModel, id: Long, onBack: () -> Unit) {
    val conv by vm.conversation(id).collectAsState(null)
    val messages by vm.messages(id).collectAsState(emptyList())
    val items = messages.map {
        ChatItem(
            "h-${it.id}",
            when (it.role) { "user" -> Role.USER; "assistant" -> Role.ASSISTANT; "tool" -> Role.TOOL; else -> Role.NOTICE },
            it.text, isError = it.isError, time = it.timestamp,
        )
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Zurück", "Back"))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(conv?.title ?: tr("Gespräch", "Conversation"), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                conv?.let {
                    Text(
                        Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).format(fmt) + " · " + it.provider,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Transcript(items, Modifier.weight(1f).fillMaxWidth(), PaddingValues(16.dp))
    }
}
