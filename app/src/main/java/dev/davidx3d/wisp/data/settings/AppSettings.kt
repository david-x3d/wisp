package dev.davidx3d.wisp.data.settings

/** Which realtime backend a session talks to. */
enum class Provider(val label: String, val shortLabel: String) {
    XAI("xAI · Grok Voice", "Grok"),
    OPENAI("OpenAI · Realtime", "OpenAI"),
}

/** How the end of a user turn is detected. */
enum class TurnMode {
    /** The server decides when I stopped talking (hands-free). */
    SERVER_VAD,

    /** OpenAI only: a classifier decides based on what was said. */
    SEMANTIC_VAD,

    /** Hold the button while talking. */
    PUSH_TO_TALK,
}

data class Voice(val id: String, val name: String, val description: String)

data class ModelOption(val id: String, val name: String, val description: String)

/** Static knowledge about each provider, taken from the official docs (Nov 2026). */
object ProviderCatalog {
    val xaiModels = listOf(
        ModelOption("grok-voice-latest", "Grok Voice (neuestes)", "Alias, zeigt immer auf das aktuelle Modell"),
        ModelOption("grok-voice-3.0", "Grok Voice 3.0", "Flaggschiff, fest gepinnt"),
    )
    val openAiModels = listOf(
        ModelOption("gpt-realtime-2.1", "GPT Realtime 2.1", "Empfohlen: bessere Unterbrechungen und Zahlen"),
        ModelOption("gpt-realtime-2", "GPT Realtime 2", "Vorgänger"),
        ModelOption("gpt-realtime-1.5", "GPT Realtime 1.5", "Günstiger, ohne Reasoning"),
    )

    /** Fallback list. The real xAI roster is fetched from GET /v1/tts/voices. */
    val xaiVoices = listOf(
        Voice("eve", "Eve", "Energisch und fröhlich"),
        Voice("ara", "Ara", "Warm und gesprächig"),
        Voice("rex", "Rex", "Klar und professionell"),
        Voice("sal", "Sal", "Ruhig und ausgewogen"),
        Voice("leo", "Leo", "Kräftig und souverän"),
    )
    val openAiVoices = listOf(
        Voice("marin", "Marin", "Empfohlen, natürlich"),
        Voice("cedar", "Cedar", "Empfohlen, tief und ruhig"),
        Voice("alloy", "Alloy", "Neutral"),
        Voice("ash", "Ash", "Locker"),
        Voice("ballad", "Ballad", "Weich, erzählend"),
        Voice("coral", "Coral", "Freundlich, hell"),
        Voice("echo", "Echo", "Sachlich"),
        Voice("sage", "Sage", "Besonnen"),
        Voice("shimmer", "Shimmer", "Lebhaft"),
        Voice("verse", "Verse", "Ausdrucksstark"),
    )

    val languages = listOf(
        "de" to "Deutsch",
        "en" to "Englisch",
        "fr" to "Französisch",
        "es-ES" to "Spanisch",
        "it" to "Italienisch",
        "nl" to "Niederländisch",
        "pl" to "Polnisch",
        "tr" to "Türkisch",
    )

    fun models(provider: Provider) = if (provider == Provider.XAI) xaiModels else openAiModels
    fun voices(provider: Provider) = if (provider == Provider.XAI) xaiVoices else openAiVoices
    fun defaultModel(provider: Provider) = models(provider).first().id
    fun defaultVoice(provider: Provider) = if (provider == Provider.XAI) "eve" else "marin"

    /** xAI bills per minute; OpenAI per token (see UsageMeter). */
    const val XAI_USD_PER_MINUTE = 0.08
}

const val DEFAULT_SYSTEM_PROMPT = """Du bist Wisp, ein persönlicher Sprachassistent auf Davids Android-Handy.
Du sprichst, du schreibst nicht: Antworte kurz, natürlich und locker auf Deutsch, meistens in ein bis zwei Sätzen.
Keine Aufzählungen, kein Markdown, keine Emojis. Zahlen, Uhrzeiten und Einheiten so, wie man sie ausspricht.
Wenn du etwas am Handy erledigen kannst, nutze direkt das passende Werkzeug, statt zu erklären, wie es geht.
Sag danach in einem kurzen Satz, was du getan hast. Wenn ein Werkzeug einen Fehler meldet, erkläre ihn einfach und sag, was David tun muss.
Bei Anrufen und Nachrichten fragst du vorher nach, wenn das Werkzeug eine Bestätigung verlangt.
Wenn du etwas nicht verstanden hast oder es mehrere Treffer gibt, frag kurz nach.
Wenn David dich unterbricht, hör sofort auf und geh auf das Neue ein."""

data class AppSettings(
    val provider: Provider = Provider.XAI,
    val xaiModel: String = ProviderCatalog.defaultModel(Provider.XAI),
    val openAiModel: String = ProviderCatalog.defaultModel(Provider.OPENAI),
    val xaiVoice: String = ProviderCatalog.defaultVoice(Provider.XAI),
    val openAiVoice: String = ProviderCatalog.defaultVoice(Provider.OPENAI),
    val language: String = "de",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val turnMode: TurnMode = TurnMode.SERVER_VAD,
    /** 0 = only loud speech counts, 1 = even whispers count. */
    val vadSensitivity: Float = 0.5f,
    val xaiReasoning: Boolean = true,
    val webSearch: Boolean = true,
    val disabledTools: Set<String> = emptySet(),
    val confirmActions: Boolean = true,
    /** Enables the system-control tools (WLAN, airplane mode, ...) via Shizuku or root. */
    val systemControlEnabled: Boolean = false,
    /** Allow KernelSU/Magisk root as a fallback when Shizuku is not running. */
    val rootEnabled: Boolean = false,
    val speakerByDefault: Boolean = true,
    val totalSeconds: Long = 0,
    val totalCostMicroUsd: Long = 0,
    val hasXaiKey: Boolean = false,
    val hasOpenAiKey: Boolean = false,
) {
    val model get() = if (provider == Provider.XAI) xaiModel else openAiModel
    val voice get() = if (provider == Provider.XAI) xaiVoice else openAiVoice
    val hasActiveKey get() = if (provider == Provider.XAI) hasXaiKey else hasOpenAiKey
}
