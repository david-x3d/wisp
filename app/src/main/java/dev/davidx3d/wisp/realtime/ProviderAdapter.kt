package dev.davidx3d.wisp.realtime

import dev.davidx3d.wisp.i18n.tr
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.TurnMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/** A function tool as the model sees it. */
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

/** Everything needed to configure a realtime session, independent of the provider. */
data class SessionConfig(
    val model: String,
    val voice: String,
    val instructions: String,
    val language: String,
    val turnMode: TurnMode,
    val vadSensitivity: Float,
    val reasoning: Boolean,
    val webSearch: Boolean,
    val sampleRate: Int = 24_000,
)

/**
 * Encapsulates the differences between xAI and OpenAI. The two APIs share almost all
 * event names (xAI is OpenAI-compatible), so the common client events live here and the
 * adapters only override session configuration, URLs and the few naming differences.
 */
abstract class ProviderAdapter {
    abstract val provider: Provider
    abstract fun url(model: String, resumeConversationId: String?): String
    abstract fun sessionUpdate(config: SessionConfig, tools: List<ToolSpec>): JsonObject

    /** Called when the server rejects part of the config; return a fixed config or null. */
    open fun fallbackAfterError(error: RealtimeEvent.Error): Boolean = false

    fun newParser() = EventParser(this)

    // ---- Client events (identical for both providers) ----

    fun audioAppend(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): String {
        val b64 = Base64.getEncoder().encodeToString(pcm.copyOfRange(offset, offset + length))
        return """{"type":"input_audio_buffer.append","audio":"$b64"}"""
    }

    fun audioCommit() = obj("input_audio_buffer.commit")
    fun audioClear() = obj("input_audio_buffer.clear")
    fun responseCreate() = obj("response.create")
    fun responseCancel() = obj("response.cancel")

    fun functionOutput(callId: String, output: String) = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        })
    }.toString()

    /** A text turn, e.g. typed input or a note like "user confirmed via button". */
    fun textMessage(role: String, text: String) = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "message")
            put("role", role)
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", if (role == "assistant") "output_text" else "input_text")
                    put("text", text)
                })
            })
        })
    }.toString()

    fun truncate(itemId: String, audioEndMs: Long) = buildJsonObject {
        put("type", "conversation.item.truncate")
        put("item_id", itemId)
        put("content_index", 0)
        put("audio_end_ms", audioEndMs)
    }.toString()

    // ---- Server event mapping hooks ----

    /** Event names that carry the user's live transcript. */
    open fun userTranscriptPartial(type: String): Boolean? = when (type) {
        "conversation.item.input_audio_transcription.delta" -> false // incremental
        "conversation.item.input_audio_transcription.updated" -> true // cumulative
        else -> null
    }

    protected fun obj(type: String) = """{"type":"$type"}"""

    protected fun toolsJson(tools: List<ToolSpec>) = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("type", "function")
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.parameters)
            })
        }
    }

    protected fun pcmFormat(rate: Int) = buildJsonObject {
        put("type", "audio/pcm")
        put("rate", rate)
    }
}

/**
 * Turns raw JSON frames into [RealtimeEvent]s. Keeps a little state per connection so a
 * `function_call_arguments.done` without a name can still be resolved.
 */
class EventParser(private val adapter: ProviderAdapter) {
    private val json = Json { ignoreUnknownKeys = true }
    private val functionNames = HashMap<String, String>() // item_id / call_id -> name

    fun parse(raw: String): RealtimeEvent {
        val o = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            return RealtimeEvent.Ignored("invalid-json")
        }
        val type = o.str("type") ?: return RealtimeEvent.Ignored("no-type")
        adapter.userTranscriptPartial(type)?.let { cumulative ->
            val text = o.str("delta") ?: o.str("transcript") ?: o.str("text") ?: ""
            return RealtimeEvent.UserTranscript(o.str("item_id") ?: "", text, cumulative, final = false)
        }
        return when (type) {
            "session.created" -> RealtimeEvent.SessionCreated(null)
            "conversation.created" -> RealtimeEvent.SessionCreated(
                o.obj("conversation")?.str("id") ?: o.str("conversation_id"),
            )
            "session.updated" -> RealtimeEvent.SessionUpdated
            "input_audio_buffer.speech_started" -> RealtimeEvent.SpeechStarted
            "input_audio_buffer.speech_stopped" -> RealtimeEvent.SpeechStopped
            "conversation.item.input_audio_transcription.completed" ->
                RealtimeEvent.UserTranscript(o.str("item_id") ?: "", o.str("transcript") ?: "", cumulative = true, final = true)
            "response.created" -> RealtimeEvent.ResponseCreated(o.obj("response")?.str("id") ?: "")
            "response.output_item.added" -> {
                val item = o.obj("item")
                val itemType = item?.str("type") ?: "message"
                if (itemType == "function_call") {
                    val name = item?.str("name")
                    if (name != null) {
                        item.str("id")?.let { functionNames[it] = name }
                        item.str("call_id")?.let { functionNames[it] = name }
                    }
                }
                RealtimeEvent.OutputItemAdded(item?.str("id") ?: "", o.str("response_id"), itemType)
            }
            "response.output_audio.delta", "response.audio.delta" -> {
                val bytes = runCatching { Base64.getDecoder().decode(o.str("delta") ?: "") }.getOrDefault(ByteArray(0))
                RealtimeEvent.AudioDelta(o.str("item_id") ?: "", bytes)
            }
            "response.output_audio.done", "response.audio.done" -> RealtimeEvent.AudioDone(o.str("item_id") ?: "")
            "response.output_audio_transcript.delta", "response.audio_transcript.delta",
            "response.output_text.delta", "response.text.delta",
            -> RealtimeEvent.AssistantTranscriptDelta(o.str("item_id") ?: "", o.str("delta") ?: "")
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                RealtimeEvent.AssistantTranscriptDone(o.str("item_id") ?: "", o.str("transcript") ?: "")
            "response.function_call_arguments.done" -> {
                val callId = o.str("call_id") ?: ""
                val name = o.str("name") ?: functionNames[callId] ?: functionNames[o.str("item_id") ?: ""] ?: ""
                RealtimeEvent.FunctionCall(callId, name, o.str("arguments") ?: "{}")
            }
            "response.done" -> parseResponseDone(o)
            "error" -> {
                val e = o.obj("error") ?: o
                RealtimeEvent.Error(e.str("code") ?: e.str("type"), e.str("message") ?: "Unbekannter Fehler", e.str("param"))
            }
            else -> RealtimeEvent.Ignored(type)
        }
    }

    private fun parseResponseDone(o: JsonObject): RealtimeEvent.ResponseDone {
        val r = o.obj("response")
        val calls = (r?.get("output") as? JsonArray).orEmpty().mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            if (item.str("type") != "function_call") return@mapNotNull null
            RealtimeEvent.FunctionCall(item.str("call_id") ?: "", item.str("name") ?: "", item.str("arguments") ?: "{}")
        }
        val usage = r?.obj("usage")?.let { u ->
            val inD = u.obj("input_token_details")
            val outD = u.obj("output_token_details")
            val cached = inD?.int("cached_tokens") ?: 0
            TokenUsage(
                textIn = (inD?.int("text_tokens") ?: 0) - (inD?.obj("cached_tokens_details")?.int("text_tokens") ?: 0),
                audioIn = (inD?.int("audio_tokens") ?: 0) - (inD?.obj("cached_tokens_details")?.int("audio_tokens") ?: 0),
                cachedIn = cached,
                textOut = outD?.int("text_tokens") ?: 0,
                audioOut = outD?.int("audio_tokens") ?: 0,
            )
        }
        val details = r?.obj("status_details")
        val msg = details?.obj("error")?.str("message") ?: details?.str("reason")
        return RealtimeEvent.ResponseDone(r?.str("id"), r?.str("status") ?: "completed", calls, usage, msg)
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun turnDetectionNull(): JsonElement = JsonNull
