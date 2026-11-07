package dev.davidx3d.wisp.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.davidx3d.wisp.R
import dev.davidx3d.wisp.session.ChatItem
import dev.davidx3d.wisp.session.Confirmation
import dev.davidx3d.wisp.session.Phase
import dev.davidx3d.wisp.session.Role
import dev.davidx3d.wisp.session.SessionState
import dev.davidx3d.wisp.ui.theme.Wisp
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.davidx3d.wisp.i18n.tr

/** Live transcript: user bubbles right, Wisp on the left, tool chips in between. */
@Composable
fun Transcript(
    items: List<ChatItem>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    compact: Boolean = false,
) {
    val listState = rememberLazyListState()
    // Follow the conversation, unless the user scrolled up to read.
    LaunchedEffect(listState) {
        snapshotFlow { items.size to (items.lastOrNull()?.text?.length ?: 0) }
            .distinctUntilChanged()
            .collect {
                if (items.isEmpty()) return@collect
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= items.size - 3 || !listState.canScrollForward) {
                    listState.animateScrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE / 4)
                }
            }
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Box(Modifier.animateItem()) {
                when (item.role) {
                    Role.USER -> UserBubble(item)
                    Role.ASSISTANT -> AssistantBubble(item, compact)
                    Role.TOOL -> if (item.nutrition != null) NutritionCard(item.nutrition) else ToolChip(item)
                    Role.NOTICE -> NoticeCard(item.text)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(item: ChatItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 26.dp, bottomEnd = 8.dp))
                .background(Wisp.colors.userBubble)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            if (item.text.isBlank() && item.streaming) {
                TypingDots(Wisp.colors.onUserBubble)
            } else {
                Text(item.text, color = Wisp.colors.onUserBubble, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Image(
            painterResource(R.drawable.ic_wisp_mark), null,
            Modifier.padding(top = 6.dp).size(if (compact) 22.dp else 26.dp),
        )
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                if (item.text.isBlank()) {
                    TypingDots(MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        item.text + if (item.streaming) " ●" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolChip(item: ChatItem) {
    val bg = if (item.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (item.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
        Surface(shape = CircleShape, color = bg, contentColor = fg) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(fg.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(item.streaming to item.isError, label = "chip") { (running, error) ->
                        when {
                            running -> LoadingIndicator(Modifier.size(20.dp), color = fg)
                            error -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp))
                            else -> Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(item.text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun NoticeCard(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null)
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun TypingDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(7.dp).scale(0.7f + a * 0.3f).alpha(a).clip(CircleShape).background(color))
        }
    }
}

/** Asks "really?" before calls and messages. Answering by voice works too. */
@Composable
fun ConfirmCard(confirmation: Confirmation?, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = confirmation != null,
        enter = slideInVertically { it / 2 } + fadeIn() + scaleIn(initialScale = 0.92f),
        exit = slideOutVertically { it / 2 } + fadeOut() + scaleOut(targetScale = 0.92f),
        modifier = modifier,
    ) {
        val c = confirmation ?: return@AnimatedVisibility
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(MaterialShapes.Clover4Leaf.toShape())
                            .background(MaterialTheme.colorScheme.tertiary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onTertiary) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(tr("Soll ich das machen?", "Should I do this?"), style = MaterialTheme.typography.titleMedium)
                        Text(tr("Du kannst auch einfach „ja“ sagen", "You can also just say “yes”"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(c.summary, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { onAnswer(false) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Nein", "No"))
                    }
                    Button(
                        onClick = { onAnswer(true) }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    ) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Ja, mach", "Yes, do it"))
                    }
                }
            }
        }
    }
}

fun phaseLabel(s: SessionState): String = when (s.phase) {
    Phase.IDLE -> tr("Tippen zum Sprechen", "Tap to talk")
    Phase.CONNECTING -> tr("Verbinde mit ${s.provider.shortLabel} …", "Connecting to ${s.provider.shortLabel} …")
    Phase.RECONNECTING -> tr("Verbindung weg – versuche es erneut …", "Connection lost – retrying …")
    Phase.LISTENING -> when {
        s.muted -> tr("Stummgeschaltet", "Muted")
        s.pushToTalk -> tr("Halten zum Sprechen", "Hold to talk")
        else -> tr("Ich höre zu", "Listening")
    }
    Phase.USER_SPEAKING -> tr("Ich höre zu …", "Listening …")
    Phase.THINKING -> tr("Denke nach …", "Thinking …")
    Phase.SPEAKING -> tr("Spricht – red einfach rein", "Speaking – just talk to interrupt")
    Phase.ERROR -> tr("Etwas ist schiefgelaufen", "Something went wrong")
}

/**
 * Bottom control dock: mute · big main button · keyboard.
 * The main button morphs its shape with the state and doubles as push-to-talk.
 */
@Composable
fun ControlDock(
    state: SessionState,
    onMain: () -> Unit,
    onMute: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typing by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(typing && state.active) {
            TextInput(onSend = { onSendText(it); typing = false })
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onMute, enabled = state.active, modifier = Modifier.size(52.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (state.muted) tr("Mikro an", "Unmute") else tr("Stumm", "Mute")) }

                MainButton(state, onMain, onPttDown, onPttUp)

                FilledTonalIconButton(
                    onClick = { typing = !typing }, enabled = state.active, modifier = Modifier.size(52.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) { Icon(Icons.Rounded.Keyboard, tr("Tippen", "Type")) }
            }
        }
    }
}

@Composable
private fun MainButton(state: SessionState, onMain: () -> Unit, onPttDown: () -> Unit, onPttUp: () -> Unit) {
    val shape = when {
        !state.active -> MaterialShapes.Cookie9Sided
        state.pttHeld -> MaterialShapes.SoftBurst
        state.pushToTalk -> MaterialShapes.Cookie12Sided
        else -> MaterialShapes.Square
    }.toShape()
    val ptt = state.active && state.pushToTalk
    val container = if (state.active && !ptt) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val content = if (state.active && !ptt) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier
            .size(72.dp)
            .clip(shape)
            .background(container)
            .pointerInput(ptt) {
                if (ptt) {
                    detectTapGestures(onPress = {
                        onPttDown()
                        tryAwaitRelease()
                        onPttUp()
                    }, onDoubleTap = { onMain() })
                } else {
                    detectTapGestures(onTap = { onMain() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(state.active to ptt, transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "main") { (active, isPtt) ->
            when {
                !active -> Icon(Icons.Rounded.Mic, tr("Gespräch starten", "Start conversation"), tint = content, modifier = Modifier.size(32.dp))
                isPtt -> Icon(Icons.Rounded.Mic, tr("Halten zum Sprechen", "Hold to talk"), tint = content, modifier = Modifier.size(32.dp))
                else -> Icon(Icons.Rounded.Stop, tr("Gespräch beenden", "End conversation"), tint = content, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun TextInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (text.isEmpty()) Text(tr("Nachricht an Wisp", "Message Wisp"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text); text = "" }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilledIconButton(onClick = { if (text.isNotBlank()) onSend(text); text = "" }) {
                Icon(Icons.AutoMirrored.Rounded.Send, tr("Senden", "Send"))
            }
        }
    }
}


@Composable
private fun NutritionCard(receipt: dev.davidx3d.wisp.session.NutritionReceipt) {
    val format = remember { java.text.NumberFormat.getIntegerInstance() }
    val remaining = receipt.remainingKcal
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Check, null)
                Text(tr("In Nomi eingetragen", "Logged in Nomi"), style = MaterialTheme.typography.titleMedium)
            }
            Text(receipt.food, style = MaterialTheme.typography.bodyLarge)
            Text("${format.format(receipt.addedKcal)} kcal", style = MaterialTheme.typography.headlineLarge)
            if (remaining != null) {
                Text(if (remaining >= 0) tr("Noch ${format.format(remaining)} kcal übrig", "${format.format(remaining)} kcal remaining")
                    else tr("${format.format(-remaining)} kcal über dem Tagesziel", "${format.format(-remaining)} kcal over the daily target"),
                    style = MaterialTheme.typography.titleMedium)
            }
            val target = receipt.targetKcal
            if (target != null && target > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (receipt.totalKcal / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(tr("Heute ${format.format(receipt.totalKcal)} von ${format.format(target)} kcal", "Today ${format.format(receipt.totalKcal)} of ${format.format(target)} kcal"),
                    style = MaterialTheme.typography.bodyMedium)
            } else Text(tr("Kein Tagesziel in Nomi gesetzt", "No daily target set in Nomi"), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
