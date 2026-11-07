package dev.davidx3d.wisp.ui

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.davidx3d.wisp.data.history.HistoryRepository
import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.ProviderCatalog
import dev.davidx3d.wisp.data.settings.SettingsRepository
import dev.davidx3d.wisp.data.settings.Voice
import dev.davidx3d.wisp.realtime.ProviderApi
import dev.davidx3d.wisp.session.VoiceSessionManager
import dev.davidx3d.wisp.tools.PermissionChecker
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolRegistry
import dev.davidx3d.wisp.tools.system.PrivilegedShell
import dev.davidx3d.wisp.tools.system.ShellStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import dev.davidx3d.wisp.i18n.tr

@HiltViewModel
class HomeViewModel @Inject constructor(
    val manager: VoiceSessionManager,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
}

/** Null until DataStore has been read, so the app never flashes the wrong start screen. */
@HiltViewModel
class StartViewModel @Inject constructor(settingsRepo: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Ok : TestState
    data class Failed(val message: String) : TestState
}

data class ToolStatus(val tool: Tool, val missing: List<String>, val enabled: Boolean)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val api: ProviderApi,
    private val registry: ToolRegistry,
    val permissions: PermissionChecker,
    val manager: VoiceSessionManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _test = MutableStateFlow<Map<Provider, TestState>>(emptyMap())
    val test = _test.asStateFlow()

    private val _xaiVoices = MutableStateFlow(ProviderCatalog.xaiVoices)
    val xaiVoices = _xaiVoices.asStateFlow()

    private val _previewing = MutableStateFlow<String?>(null)
    val previewing = _previewing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _tools = MutableStateFlow<List<ToolStatus>>(emptyList())
    val tools = _tools.asStateFlow()

    private val _shell = MutableStateFlow(ShellStatus(false, false, false, false))
    val shell = _shell.asStateFlow()

    private val _isDefaultAssistant = MutableStateFlow(false)
    val isDefaultAssistant = _isDefaultAssistant.asStateFlow()

    init {
        viewModelScope.launch {
            repo.apiKey(Provider.XAI)?.let { key -> api.xaiVoices(key)?.takeIf { it.isNotEmpty() }?.let { _xaiVoices.value = it } }
        }
        viewModelScope.launch { settings.collect { refresh() } }
    }

    /** Re-reads permission and access state; call on resume. */
    fun refresh() {
        val s = settings.value
        _tools.value = registry.all.map { ToolStatus(it, permissions.missing(it), registry.isEnabled(it, s)) }
        _shell.value = PrivilegedShell.status(s.rootEnabled)
        val assistant = Settings.Secure.getString(context.contentResolver, "assistant").orEmpty()
        _isDefaultAssistant.value = assistant.startsWith(context.packageName)
    }

    fun update(f: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(f) }

    fun setToolEnabled(tool: Tool, on: Boolean) = update {
        it.copy(disabledTools = if (on) it.disabledTools - tool.name else it.disabledTools + tool.name)
    }

    fun saveKey(provider: Provider, key: String) = viewModelScope.launch {
        repo.setApiKey(provider, key)
        _test.update { it + (provider to TestState.Idle) }
        if (provider == Provider.XAI && key.isNotBlank()) api.xaiVoices(key.trim())?.takeIf { it.isNotEmpty() }?.let { _xaiVoices.value = it }
    }

    fun testKey(provider: Provider) = viewModelScope.launch {
        _test.update { it + (provider to TestState.Running) }
        val key = repo.apiKey(provider)
        val result = if (key == null) tr("Noch kein Key gespeichert.", "No key saved yet.") else {
            val model = settings.value.copy(provider = provider).model
            api.testKey(provider, key, model)
        }
        _test.update { it + (provider to if (result == null) TestState.Ok else TestState.Failed(result)) }
    }

    fun preview(voice: Voice) = viewModelScope.launch {
        val provider = settings.value.provider
        val key = repo.apiKey(provider) ?: run { _message.value = tr("Für eine Hörprobe brauche ich zuerst einen API-Key.", "I need an API key first to play a sample."); return@launch }
        _previewing.value = voice.id
        api.preview(provider, key, voice.id, settings.value.userName)?.let { _message.value = it }
        _previewing.value = null
    }

    fun requestShizuku() = viewModelScope.launch {
        if (!PrivilegedShell.requestShizuku()) _message.value = tr("Shizuku läuft nicht oder die Berechtigung wurde abgelehnt.", "Shizuku is not running or permission was denied.")
        refresh()
    }

    fun requestRoot() = viewModelScope.launch {
        if (!PrivilegedShell.requestRoot()) _message.value = tr("Root wurde nicht gewährt. Erlaube Wisp in der KernelSU-App.", "Root was not granted. Allow Wisp in the KernelSU app.")
        refresh()
    }

    fun specialIntent(a: SpecialAccess) = permissions.intentFor(a)

    fun consumeMessage() {
        _message.value = null
    }

    fun resetUsage() = viewModelScope.launch { repo.resetUsage() }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: HistoryRepository) : ViewModel() {
    val query = MutableStateFlow("")
    val conversations = query.flatMapLatest { repo.search(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun messages(id: Long) = repo.messages(id)
    fun conversation(id: Long) = repo.conversation(id)
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun deleteAll() = viewModelScope.launch { repo.deleteAll() }
}
