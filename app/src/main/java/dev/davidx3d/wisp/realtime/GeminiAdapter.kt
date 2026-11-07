package dev.davidx3d.wisp.realtime

import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.TurnMode
import kotlinx.serialization.json.*
import java.util.Base64

/** Native Google Live wire protocol; never sends OpenAI event envelopes to Google. */
class GeminiAdapter : ProviderAdapter() {
    override val provider = Provider.GEMINI
    private val calls = mutableMapOf<String, String>()
    private var resumeHandle: String? = null
    private var turn = 0
    private var assistantStarted = false
    private var userText = ""
    override fun url(model: String, resumeConversationId: String?) =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    override fun sessionUpdate(config: SessionConfig, tools: List<ToolSpec>) = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/${config.model}")
            put("sessionResumption", buildJsonObject {
                resumeHandle?.let { put("handle", it) }
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", config.voice) })
                    })
                })
            })
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", config.instructions) }) })
            })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject {
                    put("disabled", config.turnMode == TurnMode.PUSH_TO_TALK)
                })
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", buildJsonArray {
                        tools.forEach { tool -> add(buildJsonObject {
                            put("name", tool.name); put("description", tool.description)
                            // parametersJsonSchema accepts the full JSON Schema used by Wisp's tools.
                            put("parametersJsonSchema", tool.parameters)
                        }) }
                    })
                })
            })
        })
    }

    override fun audioAppend(pcm: ByteArray, offset: Int, length: Int) = buildJsonObject {
        put("realtimeInput", buildJsonObject {
            put("audio", buildJsonObject {
                put("mimeType", "audio/pcm;rate=24000")
                put("data", Base64.getEncoder().encodeToString(pcm.copyOfRange(offset, offset + length)))
            })
        })
    }.toString()
    override fun audioClear() = """{"realtimeInput":{"activityStart":{}}}"""
    override fun audioCommit() = """{"realtimeInput":{"activityEnd":{}}}"""
    override fun responseCreate() = "" // Google generates automatically after a turn or tool result.
    override fun responseCancel() = "" // Google interrupts when user activity starts.
    override fun truncate(itemId: String, audioEndMs: Long) = ""
    override fun textMessage(role: String, text: String) = buildJsonObject {
        put("clientContent", buildJsonObject {
            put("turns", buildJsonArray { add(buildJsonObject {
                put("role", if (role == "assistant") "model" else "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
            }) })
            put("turnComplete", role != "assistant")
        })
    }.toString()
    override fun functionOutput(callId: String, output: String) = buildJsonObject {
        put("toolResponse", buildJsonObject {
            put("functionResponses", buildJsonArray { add(buildJsonObject {
                put("id", callId); put("name", calls.remove(callId).orEmpty())
                put("response", runCatching { Json.parseToJsonElement(output).jsonObject }
                    .getOrElse { buildJsonObject { put("result", output) } })
            }) })
        })
    }.toString()

    override fun parseEvents(raw: String): List<RealtimeEvent> = buildList {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
            add(RealtimeEvent.Ignored("invalid-json")); return@buildList
        }
        root.obj("error")?.let { add(RealtimeEvent.Error(it.str("status"), it.str("message").orEmpty())) }
        root.obj("sessionResumptionUpdate")?.let {
            if (it["resumable"]?.jsonPrimitive?.booleanOrNull == true) resumeHandle = it.str("newHandle")
        }
        if (root.containsKey("setupComplete")) add(RealtimeEvent.SessionUpdated)
        root.obj("toolCall")?.get("functionCalls")?.jsonArray?.forEach { element ->
            val call = element.jsonObject
            val id = call.str("id").orEmpty()
            val name = call.str("name").orEmpty()
            calls[id] = name
            add(RealtimeEvent.FunctionCall(id, name, call["args"]?.toString() ?: "{}"))
        }
        root.obj("serverContent")?.let { content ->
            if (content["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                if (userText.isNotEmpty()) add(RealtimeEvent.UserTranscript("user-$turn", userText, true, true))
                add(RealtimeEvent.ResponseDone("gemini-$turn", "cancelled", emptyList(), null))
                turn++; userText = ""; assistantStarted = false
                add(RealtimeEvent.SpeechStarted)
            }
            val id = "gemini-$turn"
            content.obj("inputTranscription")?.str("text")?.let {
                userText += it
                add(RealtimeEvent.UserTranscript("user-$turn", it, cumulative = false, final = false))
            }
            val parts = content.obj("modelTurn")?.get("parts") as? JsonArray
            if ((parts != null || content.containsKey("outputTranscription")) && !assistantStarted) {
                assistantStarted = true
                add(RealtimeEvent.ResponseCreated(id))
            }
            parts?.forEach { part ->
                val inline = part.jsonObject.obj("inlineData") ?: return@forEach
                val data = inline.str("data") ?: return@forEach
                runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let {
                    add(RealtimeEvent.AudioDelta(id, it))
                }
            }
            content.obj("outputTranscription")?.str("text")?.let {
                add(RealtimeEvent.AssistantTranscriptDelta(id, it))
            }
            if (content["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                if (userText.isNotEmpty()) add(RealtimeEvent.UserTranscript("user-$turn", userText, true, true))
                add(RealtimeEvent.AudioDone(id))
                add(RealtimeEvent.ResponseDone(id, "completed", emptyList(), null))
                turn++; assistantStarted = false; userText = ""
            }
        }
        root.obj("usageMetadata")?.let { usage ->
            fun tokens(field: String, modality: String) = (usage[field] as? JsonArray).orEmpty().sumOf {
                val item = it.jsonObject
                if (item.str("modality") == modality) item.int("tokenCount") ?: 0 else 0
            }
            add(RealtimeEvent.Usage(TokenUsage(
                textIn = tokens("promptTokensDetails", "TEXT"), audioIn = tokens("promptTokensDetails", "AUDIO"),
                textOut = tokens("responseTokensDetails", "TEXT"), audioOut = tokens("responseTokensDetails", "AUDIO"),
            )))
        }
    }
}
