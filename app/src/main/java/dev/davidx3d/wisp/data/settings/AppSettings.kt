package dev.davidx3d.wisp.data.settings

import dev.davidx3d.wisp.i18n.tr

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
        ModelOption("grok-voice-latest", tr("Grok Voice (neuestes)", "Grok Voice (latest)"), tr("Alias, zeigt immer auf das aktuelle Modell", "Alias that always points to the current model")),
        ModelOption("grok-voice-3.0", "Grok Voice 3.0", tr("Flaggschiff, fest gepinnt", "Flagship, pinned")),
    )
    val openAiModels = listOf(
        ModelOption("gpt-realtime-2.1", "GPT Realtime 2.1", tr("Empfohlen: bessere Unterbrechungen und Zahlen", "Recommended: better interruptions and numbers")),
        ModelOption("gpt-realtime-2", "GPT Realtime 2", tr("Vorgänger", "Previous version")),
        ModelOption("gpt-realtime-1.5", "GPT Realtime 1.5", tr("Günstiger, ohne Reasoning", "Cheaper, no reasoning")),
    )

    /** Fallback list. The real xAI roster is fetched from GET /v1/tts/voices. */
    val xaiVoices = listOf(
        Voice("eve", "Eve", tr("Energisch und fröhlich", "Energetic and upbeat")),
        Voice("ara", "Ara", tr("Warm und gesprächig", "Warm and conversational")),
        Voice("rex", "Rex", tr("Klar und professionell", "Clear and professional")),
        Voice("sal", "Sal", tr("Ruhig und ausgewogen", "Smooth and balanced")),
        Voice("leo", "Leo", tr("Kräftig und souverän", "Strong and authoritative")),
    )
    val openAiVoices = listOf(
        Voice("marin", "Marin", tr("Empfohlen, natürlich", "Recommended, natural")),
        Voice("cedar", "Cedar", tr("Empfohlen, tief und ruhig", "Recommended, deep and calm")),
        Voice("alloy", "Alloy", tr("Neutral", "Neutral")),
        Voice("ash", "Ash", tr("Locker", "Casual")),
        Voice("ballad", "Ballad", tr("Weich, erzählend", "Soft, narrative")),
        Voice("coral", "Coral", tr("Freundlich, hell", "Friendly, bright")),
        Voice("echo", "Echo", tr("Sachlich", "Matter-of-fact")),
        Voice("sage", "Sage", tr("Besonnen", "Thoughtful")),
        Voice("shimmer", "Shimmer", tr("Lebhaft", "Lively")),
        Voice("verse", "Verse", tr("Ausdrucksstark", "Expressive")),
    )

    val languages = listOf(
        "de" to tr("Deutsch", "German"),
        "en" to tr("Englisch", "English"),
        "fr" to tr("Französisch", "French"),
        "es-ES" to tr("Spanisch", "Spanish"),
        "it" to tr("Italienisch", "Italian"),
        "nl" to tr("Niederländisch", "Dutch"),
        "pl" to tr("Polnisch", "Polish"),
        "tr" to tr("Türkisch", "Turkish"),
    )

    fun models(provider: Provider) = if (provider == Provider.XAI) xaiModels else openAiModels
    fun voices(provider: Provider) = if (provider == Provider.XAI) xaiVoices else openAiVoices
    fun defaultModel(provider: Provider) = models(provider).first().id
    fun defaultVoice(provider: Provider) = if (provider == Provider.XAI) "ara" else "marin"

    /** xAI bills per minute; OpenAI per token (see UsageMeter). */
    const val XAI_USD_PER_MINUTE = 0.08
}

const val DEFAULT_SYSTEM_PROMPT_DE = """Du bist Wisp, ein persönlicher Sprachassistent auf dem Android-Handy von {name}.
Du sprichst, du schreibst nicht: Antworte kurz, natürlich und locker auf Deutsch, meistens in ein bis zwei Sätzen.
Keine Aufzählungen, kein Markdown, keine Emojis. Zahlen, Uhrzeiten und Einheiten so, wie man sie ausspricht.
Wenn du etwas am Handy erledigen kannst, nutze direkt das passende Werkzeug, statt zu erklären, wie es geht.
Sag danach in einem kurzen Satz, was du getan hast. Wenn ein Werkzeug einen Fehler meldet, erkläre ihn einfach und sag, was {name} tun muss.
Bei Anrufen und Nachrichten fragst du vorher nach, wenn das Werkzeug eine Bestätigung verlangt.
Wenn du etwas nicht verstanden hast oder es mehrere Treffer gibt, frag kurz nach.
Wenn {name} dich unterbricht, hör sofort auf und geh auf das Neue ein."""

const val DEFAULT_SYSTEM_PROMPT_EN = """You are Wisp, a personal voice assistant on {name}'s Android phone.
You speak, you don't write: answer briefly, naturally and casually, usually in one or two sentences.
No lists, no markdown, no emojis. Say numbers, times and units the way people say them out loud.
If you can do something on the phone, use the matching tool right away instead of explaining how.
Afterwards say in one short sentence what you did. If a tool reports an error, explain it simply and say what {name} needs to do.
For calls and messages, ask first when the tool asks for confirmation.
If you did not understand something or there are several matches, ask briefly.
If {name} interrupts you, stop right away and go with the new topic."""

/** The built-in prompt in the device language. */
fun defaultSystemPrompt(): String = if (dev.davidx3d.wisp.i18n.Lang.isGerman) DEFAULT_SYSTEM_PROMPT_DE else DEFAULT_SYSTEM_PROMPT_EN

/** Default conversation language: the system language if Wisp supports it, otherwise English. */
fun defaultLanguage(): String {
    val sys = java.util.Locale.getDefault().language
    return ProviderCatalog.languages.map { it.first }.firstOrNull { it.substringBefore('-') == sys } ?: "en"
}

data class AppSettings(
    /** How the assistant addresses the user. */
    val userName: String = "",
    val onboardingDone: Boolean = false,
    val provider: Provider = Provider.XAI,
    val xaiModel: String = ProviderCatalog.defaultModel(Provider.XAI),
    val openAiModel: String = ProviderCatalog.defaultModel(Provider.OPENAI),
    val xaiVoice: String = ProviderCatalog.defaultVoice(Provider.XAI),
    val openAiVoice: String = ProviderCatalog.defaultVoice(Provider.OPENAI),
    val language: String = defaultLanguage(),
    /** Empty = use [defaultSystemPrompt] in the device language. */
    val systemPrompt: String = "",
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
