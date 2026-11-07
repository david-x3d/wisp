package dev.davidx3d.wisp.realtime

import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.TurnMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * xAI Grok Voice Agent API (`wss://api.x.ai/v1/realtime`).
 * Docs: docs.x.ai → Voice → Speech to Speech.
 *  - Session config lives under `session` with `audio.input/output.format`.
 *  - Live user captions need `audio.input.transcription.model = "grok-transcribe"` and arrive
 *    as cumulative `conversation.item.input_audio_transcription.updated` events.
 *  - `web_search` is a server-side tool: the model runs it itself.
 *  - Turn detection knows only `server_vad` or null (no tuning knobs), so sensitivity is
 *    implemented client-side with a noise gate (see AudioEngine).
 *  - `resumption` lets a dropped connection continue the same conversation.
 */
class XaiAdapter : ProviderAdapter() {
    override val provider = Provider.XAI

    override fun url(model: String, resumeConversationId: String?): String {
        val base = "wss://api.x.ai/v1/realtime?model=$model"
        return if (resumeConversationId != null) "$base&conversation_id=$resumeConversationId" else base
    }

    override fun sessionUpdate(config: SessionConfig, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("voice", config.voice)
            put("instructions", config.instructions)
            put("reasoning", buildJsonObject { put("effort", if (config.reasoning) "high" else "none") })
            if (config.turnMode == TurnMode.PUSH_TO_TALK) {
                put("turn_detection", JsonNull)
            } else {
                put("turn_detection", buildJsonObject { put("type", "server_vad") })
            }
            put("resumption", buildJsonObject { put("enabled", true) })
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", pcmFormat(config.sampleRate))
                    put("transcription", buildJsonObject {
                        put("model", "grok-transcribe")
                        put("language_hint", config.language)
                        put("keyterms", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("Wisp")) })
                    })
                })
                put("output", buildJsonObject {
                    put("format", pcmFormat(config.sampleRate))
                })
            })
            put("tools", buildJsonArray {
                toolsJson(tools).forEach { add(it) }
                if (config.webSearch) add(buildJsonObject { put("type", "web_search") })
            })
        })
    }
}

/**
 * OpenAI Realtime API (`wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1`), GA format.
 * Docs: developers.openai.com → Realtime conversations / VAD / function calling.
 *  - `session.type = "realtime"`, audio config under `session.audio.input/output`.
 *  - Input transcription arrives as `...input_audio_transcription.delta` (incremental).
 *  - Supports `server_vad` (threshold, silence) and `semantic_vad` (eagerness).
 */
class OpenAiAdapter : ProviderAdapter() {
    override val provider = Provider.OPENAI

    /** 0 = gpt-live-transcribe, 1 = gpt-transcribe, 2 = no transcription model. */
    var transcriptionLevel = 0
        private set

    override fun url(model: String, resumeConversationId: String?) = "wss://api.openai.com/v1/realtime?model=$model"

    override fun sessionUpdate(config: SessionConfig, tools: List<ToolSpec>): JsonObject = buildJsonObject {
        put("type", "session.update")
        put("session", buildJsonObject {
            put("type", "realtime")
            put("model", config.model)
            put("output_modalities", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("audio")) })
            put("instructions", config.instructions)
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", pcmFormat(config.sampleRate))
                    put("noise_reduction", buildJsonObject { put("type", "near_field") })
                    when (transcriptionLevel) {
                        0 -> put("transcription", buildJsonObject {
                            put("model", "gpt-live-transcribe")
                            put("languages", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(config.language)) })
                        })
                        1 -> put("transcription", buildJsonObject {
                            put("model", "gpt-transcribe")
                            put("language", config.language.substringBefore('-'))
                        })
                        else -> Unit
                    }
                    put("turn_detection", turnDetection(config))
                })
                put("output", buildJsonObject {
                    put("format", pcmFormat(config.sampleRate))
                    put("voice", config.voice)
                })
            })
            put("tools", toolsJson(tools))
            put("tool_choice", "auto")
        })
    }

    override fun fallbackAfterError(error: RealtimeEvent.Error): Boolean {
        val about = (error.param ?: "") + " " + error.message
        if (transcriptionLevel < 2 && about.contains("transcription", ignoreCase = true)) {
            transcriptionLevel++
            return true
        }
        return false
    }

    private fun turnDetection(config: SessionConfig) = when (config.turnMode) {
        TurnMode.PUSH_TO_TALK -> JsonNull
        TurnMode.SEMANTIC_VAD -> buildJsonObject {
            put("type", "semantic_vad")
            put("eagerness", when {
                config.vadSensitivity > 0.66f -> "high"
                config.vadSensitivity < 0.33f -> "low"
                else -> "auto"
            })
            put("create_response", true)
            put("interrupt_response", true)
        }
        TurnMode.SERVER_VAD -> buildJsonObject {
            put("type", "server_vad")
            // Higher sensitivity -> lower threshold (quieter speech counts).
            put("threshold", vadThreshold(config.vadSensitivity))
            put("prefix_padding_ms", 300)
            put("silence_duration_ms", 550)
            put("create_response", true)
            put("interrupt_response", true)
        }
    }

    companion object {
        fun vadThreshold(sensitivity: Float): Double =
            (0.8 - sensitivity.coerceIn(0f, 1f) * 0.5).let { Math.round(it * 100) / 100.0 }
    }
}

fun adapterFor(provider: Provider): ProviderAdapter = when (provider) {
    Provider.XAI -> XaiAdapter()
    Provider.OPENAI -> OpenAiAdapter()
    Provider.GEMINI -> GeminiAdapter()
}
