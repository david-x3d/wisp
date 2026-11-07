package dev.davidx3d.wisp

import dev.davidx3d.wisp.data.settings.*
import dev.davidx3d.wisp.realtime.*
import dev.davidx3d.wisp.tools.schema
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiAdapterTest {
    private val adapter = GeminiAdapter()
    private fun config(mode: TurnMode = TurnMode.SERVER_VAD) = SessionConfig(
        "gemini-3.8-live", "Kore", "Be brief", "de", mode, 0.5f, false, false,
    )
    @Test fun setupUsesNativeProtocolAndTools() {
        val setup = adapter.sessionUpdate(config(), listOf(ToolSpec("add_nomi_food", "Food", schema { string("food", "Meal", true) })))
        assertFalse(setup.containsKey("type"))
        val body = setup["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", body["model"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", body["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(body["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject.containsKey("parametersJsonSchema"))
        assertFalse(body["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.boolean)
        val ptt = adapter.sessionUpdate(config(TurnMode.PUSH_TO_TALK), emptyList())["setup"]!!.jsonObject
        assertTrue(ptt["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.boolean)
    }
    @Test fun audioAndManualTurnsUseGoogleEnvelopes() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val audio = Json.parseToJsonElement(adapter.audioAppend(pcm, 1, 2)).jsonObject["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=24000", audio["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AgM=", audio["data"]!!.jsonPrimitive.content)
        assertTrue(adapter.audioClear().contains("activityStart"))
        assertTrue(adapter.audioCommit().contains("activityEnd"))
        assertEquals("", adapter.responseCreate())
        assertTrue(adapter.textMessage("user", "hello").contains("turnComplete"))
    }
    @Test fun framePreservesAudioTranscriptsAndCompletion() {
        assertEquals(RealtimeEvent.SessionUpdated, adapter.parseEvents("""{"setupComplete":{}}""").single())
        val events = adapter.parseEvents("""{"serverContent":{"inputTranscription":{"text":"bread"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}}]},"outputTranscription":{"text":"Okay"},"turnComplete":true}}""")
        assertArrayEquals(byteArrayOf(1, 2), events.filterIsInstance<RealtimeEvent.AudioDelta>().single().pcm)
        assertEquals("Okay", events.filterIsInstance<RealtimeEvent.AssistantTranscriptDelta>().single().delta)
        assertEquals("bread", events.filterIsInstance<RealtimeEvent.UserTranscript>().last().text)
        assertTrue(events.filterIsInstance<RealtimeEvent.UserTranscript>().last().final)
        assertEquals(1, events.filterIsInstance<RealtimeEvent.ResponseDone>().size)
    }
    @Test fun parallelToolCallsKeepTheirNamesAndIds() {
        val events = adapter.parseEvents("""{"toolCall":{"functionCalls":[{"id":"a","name":"add_nomi_food","args":{"food":"200 g rice"}},{"id":"b","name":"set_timer","args":{"minutes":2}}]}}""")
        assertEquals(listOf("a", "b"), events.filterIsInstance<RealtimeEvent.FunctionCall>().map { it.callId })
        val output = Json.parseToJsonElement(adapter.functionOutput("a", """{"ok":true}""")).jsonObject["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray.single().jsonObject
        assertEquals("add_nomi_food", output["name"]!!.jsonPrimitive.content)
        assertEquals("a", output["id"]!!.jsonPrimitive.content)
        assertTrue(output["response"]!!.jsonObject["ok"]!!.jsonPrimitive.boolean)
    }
    @Test fun usageDoesNotEndAnActiveResponse() {
        val events = adapter.parseEvents("""{"usageMetadata":{"promptTokensDetails":[{"modality":"AUDIO","tokenCount":20}],"responseTokensDetails":[{"modality":"TEXT","tokenCount":5}]}}""")
        assertEquals(20, (events.single() as RealtimeEvent.Usage).tokens.audioIn)
        assertFalse(events.any { it is RealtimeEvent.ResponseDone })
    }
    @Test fun googleErrorsAndProviderSelection() {
        assertEquals(FailureKind.RATE_LIMIT, FailureKind.fromErrorCode("RESOURCE_EXHAUSTED", "Quota"))
        assertEquals("gemini-3.8-live", AppSettings(provider = Provider.GEMINI).model)
        assertEquals("Kore", AppSettings(provider = Provider.GEMINI).voice)
        assertFalse(AppSettings(provider = Provider.GEMINI, hasOpenAiKey = true).hasActiveKey)
        assertTrue(AppSettings(provider = Provider.GEMINI, hasGeminiKey = true).hasActiveKey)
        assertFalse(adapter.url("m", null).contains("key="))
    }
    @Test fun resumptionAndInterruptionsPreserveTurnIdentity() {
        adapter.parseEvents("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"resume-123"}}""")
        val setup = adapter.sessionUpdate(config(), emptyList())["setup"]!!.jsonObject
        assertEquals("resume-123", setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        val first = adapter.parseEvents("""{"serverContent":{"outputTranscription":{"text":"one"}}}""")
            .filterIsInstance<RealtimeEvent.AssistantTranscriptDelta>().single()
        assertTrue(adapter.parseEvents("""{"serverContent":{"interrupted":true}}""").contains(RealtimeEvent.SpeechStarted))
        val second = adapter.parseEvents("""{"serverContent":{"outputTranscription":{"text":"two"}}}""")
            .filterIsInstance<RealtimeEvent.AssistantTranscriptDelta>().single()
        assertNotEquals(first.itemId, second.itemId)
    }

}
