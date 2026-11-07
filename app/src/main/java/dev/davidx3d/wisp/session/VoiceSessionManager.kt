package dev.davidx3d.wisp.session

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.davidx3d.wisp.audio.AudioEngine
import dev.davidx3d.wisp.data.history.ConversationEntity
import dev.davidx3d.wisp.data.history.HistoryRepository
import dev.davidx3d.wisp.data.history.MessageEntity
import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.ProviderCatalog
import dev.davidx3d.wisp.data.settings.SettingsRepository
import dev.davidx3d.wisp.data.settings.TurnMode
import dev.davidx3d.wisp.realtime.ConnectionSignal
import dev.davidx3d.wisp.realtime.EventParser
import dev.davidx3d.wisp.realtime.FailureKind
import dev.davidx3d.wisp.realtime.ProviderAdapter
import dev.davidx3d.wisp.realtime.RealtimeClient
import dev.davidx3d.wisp.realtime.RealtimeEvent
import dev.davidx3d.wisp.realtime.SessionConfig
import dev.davidx3d.wisp.realtime.adapterFor
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolRegistry
import dev.davidx3d.wisp.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class Phase { IDLE, CONNECTING, LISTENING, USER_SPEAKING, THINKING, SPEAKING, RECONNECTING, ERROR }

enum class Role { USER, ASSISTANT, TOOL, NOTICE }

data class ChatItem(
    val id: String,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val isError: Boolean = false,
    val time: Long = System.currentTimeMillis(),
)

data class Confirmation(val id: String, val summary: String)

data class SessionState(
    val active: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val items: List<ChatItem> = emptyList(),
    val error: String? = null,
    val confirmation: Confirmation? = null,
    val muted: Boolean = false,
    val pushToTalk: Boolean = false,
    val pttHeld: Boolean = false,
    val provider: Provider = Provider.XAI,
    val seconds: Long = 0,
    val costMicroUsd: Long = 0,
)

/**
 * Runs one live voice conversation: WebSocket, audio, transcript, tools.
 *
 * All state changes happen on a single-threaded dispatcher ([loop]), so the many
 * concurrent sources (socket thread, audio threads, tool coroutines, UI) never race.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VoiceSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val registry: ToolRegistry,
    private val history: HistoryRepository,
    private val http: OkHttpClient,
) {
    private val loop = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + loop)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val audio = AudioEngine(context)
    val inputLevel get() = audio.inputLevel
    val outputLevel get() = audio.outputLevel

    // --- connection state (loop only) ---
    private var settings = AppSettings()
    private var apiKey: String? = null
    private var adapter: ProviderAdapter? = null
    private var parser: EventParser? = null
    @Volatile private var client: RealtimeClient? = null
    @Volatile private var ready = false
    private var generation = 0
    private var conversationId: String? = null
    private var reconnectAttempts = 0
    private var userStopped = false
    private var signalJob: Job? = null
    private var tickerJob: Job? = null
    private var signals = Channel<Pair<Int, ConnectionSignal>>(Channel.UNLIMITED)
    private var startedAt = 0L
    private var sessionConfig: SessionConfig? = null
    private var pendingText: String? = null

    // --- conversation state (loop only) ---
    private var counter = 0
    private var pendingUserBubble: String? = null
    private val userBubbles = HashMap<String, String>()
    private val handledCalls = HashSet<String>()
    private var runningTools = 0
    private var followUpNeeded = false
    private var responseActive = false
    private var waitingForIdle = false
    private var tokenCostMicro = 0L
    @Volatile private var pttHeld = false
    @Volatile private var pushToTalk = false

    init {
        audio.onPlaybackIdle = { scope.launch { onPlaybackIdle() } }
    }

    // ===================== public API =====================

    /** Starts a conversation. [initialText] is sent as the first user turn once connected. */
    fun start(initialText: String? = null) {
        scope.launch {
            if (_state.value.active) {
                if (initialText != null) sendText(initialText)
                return@launch
            }
            pendingText = initialText
            settings = settingsRepo.current()
            val key = settingsRepo.apiKey(settings.provider)
            if (key == null) {
                _state.value = SessionState(phase = Phase.ERROR, provider = settings.provider,
                    error = "Kein API-Key für ${settings.provider.shortLabel}. Trag ihn in den Einstellungen ein.")
                return@launch
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                _state.value = SessionState(phase = Phase.ERROR, provider = settings.provider,
                    error = "Wisp braucht Zugriff aufs Mikrofon. Öffne die App und erlaube ihn.")
                return@launch
            }
            apiKey = key
            resetConversation()
            pushToTalk = settings.turnMode == TurnMode.PUSH_TO_TALK
            _state.value = SessionState(
                active = true, phase = Phase.CONNECTING, provider = settings.provider, pushToTalk = pushToTalk,
            )
            startedAt = System.currentTimeMillis()
            userStopped = false
            reconnectAttempts = 0
            conversationId = null
            adapter = adapterFor(settings.provider)
            audio.gateSensitivity = if (settings.provider == Provider.XAI && !pushToTalk) settings.vadSensitivity else null
            sessionConfig = buildConfig(settings)
            startForegroundService()
            signals = Channel(Channel.UNLIMITED)
            signalJob = scope.launch { for ((gen, s) in signals) if (gen == generation) onSignal(s) }
            tickerJob = scope.launch {
                while (isActive) {
                    delay(1000)
                    val secs = (System.currentTimeMillis() - startedAt) / 1000
                    _state.update { it.copy(seconds = secs, costMicroUsd = costFor(secs)) }
                }
            }
            connect()
        }
    }

    fun stop() {
        scope.launch { finish(error = null) }
    }

    fun toggle() = if (_state.value.active) stop() else start()

    fun toggleMute() {
        audio.muted = !audio.muted
        _state.update { it.copy(muted = audio.muted) }
    }

    fun sendText(text: String) {
        scope.launch {
            val c = client ?: return@launch
            val a = adapter ?: return@launch
            if (text.isBlank()) return@launch
            interruptPlayback()
            addItem(ChatItem("t-${counter++}", Role.USER, text.trim()))
            c.send(a.textMessage("user", text.trim()))
            if (!responseActive) c.send(a.responseCreate())
            setPhase(Phase.THINKING)
        }
    }

    /** Push-to-talk pressed. */
    fun pttDown() {
        scope.launch {
            val c = client ?: return@launch
            val a = adapter ?: return@launch
            interruptPlayback()
            if (responseActive) c.send(a.responseCancel())
            c.send(a.audioClear())
            pttHeld = true
            _state.update { it.copy(pttHeld = true) }
            ensureUserBubble()
            setPhase(Phase.USER_SPEAKING)
        }
    }

    /** Push-to-talk released: commit the turn and ask for an answer. */
    fun pttUp() {
        scope.launch {
            if (!pttHeld) return@launch
            pttHeld = false
            _state.update { it.copy(pttHeld = false) }
            val c = client ?: return@launch
            val a = adapter ?: return@launch
            delay(120) // let the last frames reach the socket
            c.send(a.audioCommit())
            c.send(a.responseCreate())
            setPhase(Phase.THINKING)
        }
    }

    /** The user tapped "Ja"/"Nein" on a confirmation card. */
    fun resolveConfirmation(approved: Boolean) {
        scope.launch {
            val conf = _state.value.confirmation ?: return@launch
            _state.update { it.copy(confirmation = null) }
            val result = withContext(Dispatchers.IO) { registry.resolve(conf.id, approved, toolContext()) }
            addItem(ChatItem("c-${counter++}", Role.TOOL, result.chip, isError = result is ToolResult.Failure))
            val c = client ?: return@launch
            val a = adapter ?: return@launch
            val note = if (approved) {
                "[Ich habe per Button bestätigt: ${conf.summary}. Ergebnis: ${result.toOutput()}] Sag kurz, ob es geklappt hat."
            } else {
                "[Ich habe per Button abgelehnt: ${conf.summary}] Bestätige kurz, dass du es nicht machst."
            }
            c.send(a.textMessage("user", note))
            if (!responseActive) c.send(a.responseCreate())
        }
    }

    fun dismissError() = _state.update { if (it.active) it.copy(error = null) else it.copy(error = null, phase = Phase.IDLE) }

    // ===================== connection =====================

    private fun buildConfig(s: AppSettings): SessionConfig {
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN))
        val languageName = ProviderCatalog.languages.firstOrNull { it.first == s.language }?.second ?: s.language
        val name = s.userName.trim().ifEmpty { "dem Nutzer" }
        val who = if (s.userName.isBlank()) "Den Namen des Nutzers kennst du nicht." else "Der Nutzer heißt ${s.userName.trim()}. Sprich ihn gelegentlich mit Namen an, aber nicht in jedem Satz."
        val instructions = s.systemPrompt.trim().replace("{name}", name) +
            "\n\n$who\nHeute ist $today. Sprich $languageName, außer der Nutzer wechselt die Sprache." +
            (if (s.provider == Provider.OPENAI) "\nFür Wetter nutze get_weather." else "")
        return SessionConfig(
            model = s.model, voice = s.voice, instructions = instructions, language = s.language,
            turnMode = if (s.provider == Provider.XAI && s.turnMode == TurnMode.SEMANTIC_VAD) TurnMode.SERVER_VAD else s.turnMode,
            vadSensitivity = s.vadSensitivity, reasoning = s.xaiReasoning, webSearch = s.webSearch,
        )
    }

    private fun connect() {
        val a = adapter ?: return
        val key = apiKey ?: return
        generation++
        val gen = generation
        ready = false
        parser = a.newParser()
        client?.close()
        val c = RealtimeClient(http)
        client = c
        val url = a.url(settings.model, conversationId)
        c.connect(url, key) { signal -> signals.trySend(gen to signal) }
    }

    private suspend fun onSignal(s: ConnectionSignal) {
        when (s) {
            ConnectionSignal.Open -> sendSessionUpdate()
            is ConnectionSignal.Message -> parser?.parse(s.text)?.let { onEvent(it) }
            is ConnectionSignal.Closed -> onClosed(s.failure, s.detail)
        }
    }

    private fun sendSessionUpdate() {
        val a = adapter ?: return
        val cfg = sessionConfig ?: return
        client?.send(a.sessionUpdate(cfg, registry.specs(settings)).toString())
    }

    private suspend fun onClosed(failure: FailureKind?, detail: String?) {
        ready = false
        if (userStopped || !_state.value.active) return
        Log.w(TAG, "connection closed: $failure ($detail)")
        val kind = failure ?: FailureKind.CLOSED
        if (!kind.retryable || reconnectAttempts >= MAX_RECONNECTS) {
            finish(error = kind.userMessage)
            return
        }
        reconnectAttempts++
        interruptPlayback()
        responseActive = false
        setPhase(Phase.RECONNECTING)
        delay((500L shl reconnectAttempts).coerceAtMost(8_000))
        if (userStopped || !_state.value.active) return
        connect()
    }

    // ===================== server events =====================

    private suspend fun onEvent(e: RealtimeEvent) {
        val c = client ?: return
        val a = adapter ?: return
        when (e) {
            is RealtimeEvent.SessionCreated -> e.conversationId?.let { conversationId = it }
            RealtimeEvent.SessionUpdated -> {
                if (!ready) {
                    val wasReconnect = reconnectAttempts > 0
                    ready = true
                    reconnectAttempts = 0
                    if (wasReconnect && a.provider == Provider.OPENAI) seedHistory()
                    startAudio()
                    pendingText?.let { text ->
                        pendingText = null
                        addItem(ChatItem("t-${counter++}", Role.USER, text))
                        c.send(a.textMessage("user", text))
                        c.send(a.responseCreate())
                        setPhase(Phase.THINKING)
                        return
                    }
                    if (_state.value.phase == Phase.CONNECTING || _state.value.phase == Phase.RECONNECTING) setPhase(Phase.LISTENING)
                }
            }
            RealtimeEvent.SpeechStarted -> {
                interruptPlayback()
                ensureUserBubble()
                setPhase(Phase.USER_SPEAKING)
            }
            RealtimeEvent.SpeechStopped -> setPhase(Phase.THINKING)
            is RealtimeEvent.UserTranscript -> onUserTranscript(e)
            is RealtimeEvent.ResponseCreated -> {
                responseActive = true
                if (_state.value.phase != Phase.SPEAKING) setPhase(Phase.THINKING)
            }
            is RealtimeEvent.OutputItemAdded -> if (e.type == "message") ensureAssistantBubble(e.itemId)
            is RealtimeEvent.AudioDelta -> {
                audio.enqueue(e.itemId, e.pcm)
                if (_state.value.phase != Phase.SPEAKING && !pttHeld) setPhase(Phase.SPEAKING)
            }
            is RealtimeEvent.AudioDone -> Unit
            is RealtimeEvent.AssistantTranscriptDelta -> {
                val id = ensureAssistantBubble(e.itemId)
                updateItem(id) { it.copy(text = it.text + e.delta, streaming = true) }
            }
            is RealtimeEvent.AssistantTranscriptDone -> {
                val id = ensureAssistantBubble(e.itemId)
                updateItem(id) { it.copy(text = e.transcript.ifBlank { it.text }, streaming = false) }
            }
            is RealtimeEvent.FunctionCall -> onFunctionCall(e)
            is RealtimeEvent.ResponseDone -> {
                responseActive = false
                e.functionCalls.forEach { onFunctionCall(it) }
                e.usage?.let { tokenCostMicro += it.costMicroUsd() }
                _state.update { s -> s.copy(items = s.items.map { if (it.role == Role.ASSISTANT && it.streaming) it.copy(streaming = false) else it }) }
                if (e.status == "failed") {
                    val msg = e.statusMessage ?: "Die Antwort ist fehlgeschlagen."
                    val fatal = FailureKind.fromErrorCode(null, msg)
                    if (fatal != null) finish(fatal.userMessage) else addNotice(msg)
                }
                if (!audio.isPlaying && runningTools == 0 && !pttHeld) setPhase(Phase.LISTENING)
                maybeFollowUp()
            }
            is RealtimeEvent.Error -> onServerError(e)
            is RealtimeEvent.Ignored -> Unit
        }
        if (c !== client) return
    }

    private suspend fun onServerError(e: RealtimeEvent.Error) {
        val code = e.code ?: ""
        if (code in BENIGN_ERRORS) return
        Log.w(TAG, "server error $code: ${e.message}")
        if (adapter?.fallbackAfterError(e) == true) {
            sendSessionUpdate()
            return
        }
        FailureKind.fromErrorCode(e.code, e.message)?.let {
            finish(it.userMessage)
            return
        }
        addNotice(e.message)
    }

    private fun onUserTranscript(e: RealtimeEvent.UserTranscript) {
        val bubble = userBubbles[e.itemId]
            ?: pendingUserBubble?.also { userBubbles[e.itemId] = it; pendingUserBubble = null }
            ?: newUserBubble().also { userBubbles[e.itemId] = it }
        updateItem(bubble) {
            val text = if (e.cumulative) e.text else it.text + e.text
            it.copy(text = text, streaming = !e.final)
        }
        if (e.final && e.text.isBlank()) removeItem(bubble)
    }

    private fun ensureUserBubble() {
        val existing = pendingUserBubble
        if (existing != null && _state.value.items.any { it.id == existing }) return
        pendingUserBubble = newUserBubble()
    }

    private fun newUserBubble(): String {
        val id = "u-${counter++}"
        addItem(ChatItem(id, Role.USER, "", streaming = true))
        return id
    }

    private fun ensureAssistantBubble(itemId: String): String {
        val id = "a-$itemId"
        if (_state.value.items.none { it.id == id }) addItem(ChatItem(id, Role.ASSISTANT, "", streaming = true))
        return id
    }

    // ===================== tools =====================

    private fun onFunctionCall(fc: RealtimeEvent.FunctionCall) {
        if (fc.callId.isEmpty() || !handledCalls.add(fc.callId)) return
        val chipId = "f-${fc.callId}"
        val title = registry.get(fc.name)?.title ?: if (fc.name == ToolRegistry.CONFIRM_TOOL) "Bestätigung" else fc.name
        addItem(ChatItem(chipId, Role.TOOL, "$title …", streaming = true))
        runningTools++
        setPhase(Phase.THINKING)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                registry.run(fc.name, fc.arguments, toolContext()) { pending ->
                    scope.launch { _state.update { it.copy(confirmation = Confirmation(pending.id, pending.summary)) } }
                }
            }
            if (fc.name == ToolRegistry.CONFIRM_TOOL) _state.update { it.copy(confirmation = null) }
            updateItem(chipId) { it.copy(text = result.chip, streaming = false, isError = result is ToolResult.Failure) }
            client?.send(adapter?.functionOutput(fc.callId, result.toOutput()) ?: return@launch)
            runningTools--
            followUpNeeded = true
            maybeFollowUp()
        }
    }

    private fun maybeFollowUp() {
        if (runningTools > 0 || !followUpNeeded || responseActive) return
        // xAI: wait until current speech has finished, otherwise answers overlap.
        if (audio.isPlaying) {
            waitingForIdle = true
            return
        }
        followUpNeeded = false
        waitingForIdle = false
        client?.send(adapter?.responseCreate() ?: return)
    }

    private fun onPlaybackIdle() {
        if (_state.value.phase == Phase.SPEAKING) {
            setPhase(if (responseActive || runningTools > 0) Phase.THINKING else Phase.LISTENING)
        }
        if (waitingForIdle) maybeFollowUp()
    }

    private fun toolContext() = ToolContext(context, settings)

    // ===================== audio =====================

    private fun startAudio() {
        audio.start(useSpeaker = settings.speakerByDefault) { frame ->
            val c = client
            val a = adapter
            if (!ready || c == null || a == null) return@start
            if (pushToTalk && !pttHeld) return@start
            if (c.queueSize() > MAX_QUEUED_BYTES) return@start
            c.send(a.audioAppend(frame))
        }
    }

    /** Barge-in: stop speaking now and tell the server what was actually heard. */
    private fun interruptPlayback() {
        val heard = audio.interrupt() ?: return
        val (itemId, ms) = heard
        client?.send(adapter?.truncate(itemId, ms) ?: return)
        updateItem("a-$itemId") { it.copy(streaming = false) }
    }

    // ===================== lifecycle =====================

    private fun seedHistory() {
        val c = client ?: return
        val a = adapter ?: return
        _state.value.items.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && it.text.isNotBlank() }
            .takeLast(16)
            .forEach { c.send(a.textMessage(if (it.role == Role.USER) "user" else "assistant", it.text)) }
    }

    private suspend fun finish(error: String?) {
        if (!_state.value.active && error == null) {
            _state.update { it.copy(phase = Phase.IDLE) }
            return
        }
        userStopped = true
        ready = false
        generation++
        signalJob?.cancel()
        tickerJob?.cancel()
        withContext(Dispatchers.IO) { audio.stop() }
        client?.close()
        client = null
        registry.clearPending()
        val seconds = (System.currentTimeMillis() - startedAt) / 1000
        val cost = costFor(seconds)
        val items = _state.value.items.filter { it.text.isNotBlank() }
        _state.update {
            it.copy(
                active = false, phase = if (error != null) Phase.ERROR else Phase.IDLE, error = error,
                confirmation = null, pttHeld = false, items = items, seconds = seconds, costMicroUsd = cost,
            )
        }
        withContext(NonCancellable + Dispatchers.IO) {
            if (startedAt > 0) settingsRepo.addUsage(seconds, cost)
            saveHistory(items, seconds)
        }
        startedAt = 0
    }

    private suspend fun saveHistory(items: List<ChatItem>, seconds: Long) {
        if (items.none { it.role == Role.USER || it.role == Role.ASSISTANT }) return
        val title = items.firstOrNull { it.role == Role.USER }?.text?.take(60) ?: "Gespräch"
        val conv = ConversationEntity(
            startedAt = startedAt, endedAt = System.currentTimeMillis(), provider = settings.provider.shortLabel,
            title = title, durationSeconds = seconds,
        )
        val messages = items.map {
            MessageEntity(conversationId = 0, role = it.role.name.lowercase(), text = it.text, timestamp = it.time, isError = it.isError)
        }
        runCatching { history.save(conv, messages) }.onFailure { Log.w(TAG, "history save failed", it) }
    }

    private fun costFor(seconds: Long): Long = when (settings.provider) {
        Provider.XAI -> (seconds * ProviderCatalog.XAI_USD_PER_MINUTE / 60.0 * 1_000_000).toLong()
        Provider.OPENAI -> tokenCostMicro
    }

    private fun resetConversation() {
        counter = 0
        pendingUserBubble = null
        userBubbles.clear()
        handledCalls.clear()
        runningTools = 0
        followUpNeeded = false
        responseActive = false
        waitingForIdle = false
        tokenCostMicro = 0
        pttHeld = false
        audio.muted = false
    }

    private fun startForegroundService() {
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, VoiceSessionService::class.java))
        }.onFailure { Log.w(TAG, "foreground service not started", it) }
    }

    // ===================== state helpers =====================

    private fun setPhase(p: Phase) = _state.update { if (it.active) it.copy(phase = p) else it }
    private fun addItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }
    private fun removeItem(id: String) = _state.update { s -> s.copy(items = s.items.filterNot { it.id == id }) }
    private fun addNotice(text: String) = addItem(ChatItem("n-${counter++}", Role.NOTICE, text, isError = true))
    private fun updateItem(id: String, f: (ChatItem) -> ChatItem) =
        _state.update { s -> s.copy(items = s.items.map { if (it.id == id) f(it) else it }) }

    companion object {
        private const val TAG = "WispSession"
        private const val MAX_RECONNECTS = 5
        private const val MAX_QUEUED_BYTES = 256 * 1024L
        private val BENIGN_ERRORS = setOf(
            "response_cancel_not_active",
            "conversation_already_has_active_response",
            "input_audio_buffer_commit_empty",
            "item_truncate_invalid_item_id",
        )
    }
}
