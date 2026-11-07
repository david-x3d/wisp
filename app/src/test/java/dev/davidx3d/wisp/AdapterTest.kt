package dev.davidx3d.wisp

import dev.davidx3d.wisp.data.settings.TurnMode
import dev.davidx3d.wisp.realtime.OpenAiAdapter
import dev.davidx3d.wisp.realtime.RealtimeEvent
import dev.davidx3d.wisp.realtime.SessionConfig
import dev.davidx3d.wisp.realtime.ToolSpec
import dev.davidx3d.wisp.realtime.XaiAdapter
import dev.davidx3d.wisp.tools.schema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AdapterTest {

    private val tools = listOf(ToolSpec("set_timer", "Timer", schema { integer("minutes", "Minuten") }))
    private fun config(mode: TurnMode = TurnMode.SERVER_VAD, model: String = "m") = SessionConfig(
        model = model, voice = "eve", instructions = "Sei nett", language = "de",
        turnMode = mode, vadSensitivity = 0.5f, reasoning = false, webSearch = true,
    )

    private fun JsonObject.o(k: String) = this[k]!!.jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    // ---------- xAI ----------

    @Test
    fun xaiSessionUpdate() {
        val a = XaiAdapter()
        val s = a.sessionUpdate(config(), tools).o("session")
        assertEquals("eve", s.s("voice")) // config passes the voice through unchanged
        assertEquals("server_vad", s.o("turn_detection").s("type"))
        assertEquals("none", s.o("reasoning").s("effort"))
        val input = s.o("audio").o("input")
        assertEquals("audio/pcm", input.o("format").s("type"))
        assertEquals("24000", input.o("format").s("rate"))
        assertEquals("grok-transcribe", input.o("transcription").s("model"))
        assertEquals("de", input.o("transcription").s("language_hint"))
        val toolTypes = s["tools"]!!.jsonArray.map { it.jsonObject.s("type") }
        assertEquals(listOf("function", "web_search"), toolTypes)
        assertEquals("set_timer", s["tools"]!!.jsonArray[0].jsonObject.s("name"))
        assertEquals("wss://api.x.ai/v1/realtime?model=grok-voice-latest", a.url("grok-voice-latest", null))
        assertTrue(a.url("m", "conv1").endsWith("&conversation_id=conv1"))
    }

    @Test
    fun xaiPushToTalkDisablesVad() {
        val s = XaiAdapter().sessionUpdate(config(TurnMode.PUSH_TO_TALK), tools).o("session")
        assertEquals(JsonNull, s["turn_detection"])
    }

    @Test
    fun xaiCumulativeTranscriptAndConversationId() {
        val p = XaiAdapter().newParser()
        val created = p.parse("""{"type":"conversation.created","conversation":{"id":"c42"}}""")
        assertEquals(RealtimeEvent.SessionCreated("c42"), created)
        val e = p.parse("""{"type":"conversation.item.input_audio_transcription.updated","item_id":"i1","transcript":"Stell einen"}""")
        assertEquals(RealtimeEvent.UserTranscript("i1", "Stell einen", cumulative = true, final = false), e)
    }

    // ---------- OpenAI ----------

    @Test
    fun openAiSessionUpdate() {
        val s = OpenAiAdapter().sessionUpdate(config(model = "gpt-realtime-2.1"), tools).o("session")
        assertEquals("realtime", s.s("type"))
        assertEquals("gpt-realtime-2.1", s.s("model"))
        assertEquals("eve", s.o("audio").o("output").s("voice"))
        val td = s.o("audio").o("input").o("turn_detection")
        assertEquals("server_vad", td.s("type"))
        assertEquals("0.55", td.s("threshold"))
        assertEquals("true", td.s("interrupt_response"))
        assertEquals("gpt-live-transcribe", s.o("audio").o("input").o("transcription").s("model"))
        // No server-side web search in OpenAI realtime: only function tools.
        assertEquals(1, s["tools"]!!.jsonArray.size)
    }

    @Test
    fun openAiSemanticVadEagerness() {
        val c = config(TurnMode.SEMANTIC_VAD).copy(vadSensitivity = 0.9f)
        val td = OpenAiAdapter().sessionUpdate(c, tools).o("session").o("audio").o("input").o("turn_detection")
        assertEquals("semantic_vad", td.s("type"))
        assertEquals("high", td.s("eagerness"))
    }

    @Test
    fun openAiFallsBackWhenTranscriptionModelIsRejected() {
        val a = OpenAiAdapter()
        val err = RealtimeEvent.Error("invalid_value", "Invalid model", "session.audio.input.transcription.model")
        assertTrue(a.fallbackAfterError(err))
        assertEquals("gpt-transcribe", a.sessionUpdate(config(), tools).o("session").o("audio").o("input").o("transcription").s("model"))
        assertTrue(a.fallbackAfterError(err))
        assertTrue(a.sessionUpdate(config(), tools).o("session").o("audio").o("input")["transcription"] == null)
        assertTrue(!a.fallbackAfterError(err))
    }

    @Test
    fun openAiTranscriptDeltaIsIncremental() {
        val e = OpenAiAdapter().newParser()
            .parse("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"i1","delta":"Hallo"}""")
        assertEquals(RealtimeEvent.UserTranscript("i1", "Hallo", cumulative = false, final = false), e)
    }

    // ---------- shared events ----------

    @Test
    fun audioAndTranscriptEvents() {
        val p = OpenAiAdapter().newParser()
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val audio = p.parse("""{"type":"response.output_audio.delta","item_id":"a1","delta":"$b64"}""") as RealtimeEvent.AudioDelta
        assertArrayEquals(pcm, audio.pcm)
        // Old beta name still understood.
        assertTrue(p.parse("""{"type":"response.audio.delta","item_id":"a1","delta":"$b64"}""") is RealtimeEvent.AudioDelta)
        assertEquals(
            RealtimeEvent.AssistantTranscriptDelta("a1", "Hi"),
            p.parse("""{"type":"response.output_audio_transcript.delta","item_id":"a1","delta":"Hi"}"""),
        )
        assertEquals(RealtimeEvent.SpeechStarted, p.parse("""{"type":"input_audio_buffer.speech_started"}"""))
    }

    @Test
    fun functionCallNameResolvedFromOutputItem() {
        for (adapter in listOf(XaiAdapter(), OpenAiAdapter())) {
            val p = adapter.newParser()
            p.parse("""{"type":"response.output_item.added","response_id":"r","item":{"id":"it1","type":"function_call","name":"set_timer","call_id":"call_1"}}""")
            val fc = p.parse("""{"type":"response.function_call_arguments.done","item_id":"it1","call_id":"call_1","arguments":"{\"minutes\":10}"}""")
            assertEquals(RealtimeEvent.FunctionCall("call_1", "set_timer", """{"minutes":10}"""), fc)
        }
    }

    @Test
    fun responseDoneCarriesCallsAndUsage() {
        val raw = """{"type":"response.done","response":{"id":"r1","status":"completed","output":[
            {"type":"function_call","name":"set_timer","call_id":"c9","arguments":"{}"}],
            "usage":{"input_token_details":{"text_tokens":100,"audio_tokens":1000,"cached_tokens":0},
                     "output_token_details":{"text_tokens":50,"audio_tokens":2000}}}}"""
        val e = OpenAiAdapter().newParser().parse(raw) as RealtimeEvent.ResponseDone
        assertEquals("completed", e.status)
        assertEquals(listOf(RealtimeEvent.FunctionCall("c9", "set_timer", "{}")), e.functionCalls)
        // 100*4 + 1000*32 + 50*24 + 2000*64 micro-USD
        assertEquals(400L + 32_000 + 1_200 + 128_000, e.usage!!.costMicroUsd())
    }

    @Test
    fun errorsAreParsed() {
        val e = XaiAdapter().newParser().parse("""{"type":"error","error":{"type":"invalid_request_error","code":"invalid_api_key","message":"bad key"}}""")
        assertEquals(RealtimeEvent.Error("invalid_api_key", "bad key", null), e)
        assertTrue(XaiAdapter().newParser().parse("not json") is RealtimeEvent.Ignored)
    }

    @Test
    fun clientEventsAreIdenticalForBothProviders() {
        val x = XaiAdapter()
        val o = OpenAiAdapter()
        assertEquals(x.functionOutput("c1", "{}"), o.functionOutput("c1", "{}"))
        assertEquals(x.truncate("i1", 1500), o.truncate("i1", 1500))
        val out = Json.parseToJsonElement(x.functionOutput("c1", """{"ok":true}""")).jsonObject
        assertEquals("function_call_output", out.o("item").s("type"))
        assertEquals("""{"ok":true}""", out.o("item").s("output"))
        val append = Json.parseToJsonElement(x.audioAppend(byteArrayOf(0, 1))).jsonObject
        assertEquals("input_audio_buffer.append", append.s("type"))
        assertEquals("AAE=", append.s("audio"))
    }
}
