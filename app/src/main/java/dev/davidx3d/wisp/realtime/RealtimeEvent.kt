package dev.davidx3d.wisp.realtime

/** Provider-neutral server events. Both adapters map their JSON onto these. */
sealed interface RealtimeEvent {
    /** `session.created` / `conversation.created`. [conversationId] is used for xAI resumption. */
    data class SessionCreated(val conversationId: String?) : RealtimeEvent
    data object SessionUpdated : RealtimeEvent

    data object SpeechStarted : RealtimeEvent
    data object SpeechStopped : RealtimeEvent

    /**
     * User transcript. OpenAI sends deltas, xAI sends the whole text so far ([cumulative]).
     * [final] marks the `completed` event, whose text is always the full transcript.
     */
    data class UserTranscript(
        val itemId: String,
        val text: String,
        val cumulative: Boolean,
        val final: Boolean,
    ) : RealtimeEvent

    data class ResponseCreated(val responseId: String) : RealtimeEvent
    data class OutputItemAdded(val itemId: String, val responseId: String?, val type: String) : RealtimeEvent
    data class AudioDelta(val itemId: String, val pcm: ByteArray) : RealtimeEvent
    data class AudioDone(val itemId: String) : RealtimeEvent
    data class AssistantTranscriptDelta(val itemId: String, val delta: String) : RealtimeEvent
    data class AssistantTranscriptDone(val itemId: String, val transcript: String) : RealtimeEvent

    data class FunctionCall(val callId: String, val name: String, val arguments: String) : RealtimeEvent

    data class ResponseDone(
        val responseId: String?,
        val status: String,
        val functionCalls: List<FunctionCall>,
        val usage: TokenUsage?,
        val statusMessage: String? = null,
    ) : RealtimeEvent

    data class Error(val code: String?, val message: String, val param: String? = null) : RealtimeEvent

    data class Usage(val tokens: TokenUsage) : RealtimeEvent
    data class Ignored(val type: String) : RealtimeEvent
}

/** Token counts from OpenAI's `response.done.response.usage`. xAI bills per minute instead. */
data class TokenUsage(
    val textIn: Int = 0,
    val audioIn: Int = 0,
    val cachedIn: Int = 0,
    val textOut: Int = 0,
    val audioOut: Int = 0,
) {
    /** Cost in micro-USD, using gpt-realtime-2.x list prices (per 1M tokens). */
    fun costMicroUsd(): Long {
        val usd = textIn * 4.0 + audioIn * 32.0 + cachedIn * 0.4 + textOut * 24.0 + audioOut * 64.0
        // usd is "dollars per 1M tokens * tokens" -> divide by 1M, then micro = * 1M.
        return usd.toLong()
    }
}
