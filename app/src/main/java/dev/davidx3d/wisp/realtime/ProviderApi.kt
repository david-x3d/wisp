package dev.davidx3d.wisp.realtime

import android.content.Context
import android.media.MediaPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.davidx3d.wisp.data.settings.Provider
import dev.davidx3d.wisp.data.settings.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Small REST helpers next to the realtime socket: key check, voice list, voice preview. */
@Singleton
class ProviderApi @Inject constructor(
    private val http: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var player: MediaPlayer? = null

    /** Null on success, otherwise a user-facing German message. */
    suspend fun testKey(provider: Provider, key: String, model: String): String? = withContext(Dispatchers.IO) {
        val url = when (provider) {
            // Documented endpoint that needs a valid key and returns the voice roster.
            Provider.XAI -> "https://api.x.ai/v1/tts/voices"
            Provider.OPENAI -> "https://api.openai.com/v1/models/$model"
        }
        try {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $key").build()).execute().use { r ->
                if (r.isSuccessful) null
                else FailureKind.fromHttp(r.code, runCatching { r.peekBody(4096).string() }.getOrNull()).userMessage
            }
        } catch (e: IOException) {
            FailureKind.fromThrowable(e).userMessage
        }
    }

    /** xAI's live voice list (GET /v1/tts/voices). */
    suspend fun xaiVoices(key: String): List<Voice>? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("https://api.x.ai/v1/tts/voices").header("Authorization", "Bearer $key").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val root = json.parseToJsonElement(r.body.string())
                    val arr = (root as? JsonArray) ?: (root.jsonObject["voices"] as? JsonArray) ?: return@use null
                    arr.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val id = o.str("voice_id") ?: o.str("id") ?: return@mapNotNull null
                        Voice(id, o.str("name") ?: id.replaceFirstChar { it.uppercase() }, o.str("description") ?: "")
                    }
                }
        }.getOrNull()
    }

    /** Synthesises a short German sample with the provider's TTS endpoint and plays it. */
    suspend fun preview(provider: Provider, key: String, voice: String): String? = withContext(Dispatchers.IO) {
        val text = "Hallo David, ich bin Wisp. So klinge ich mit dieser Stimme."
        val (url, body) = when (provider) {
            Provider.XAI -> "https://api.x.ai/v1/tts" to buildJsonObject {
                put("text", text); put("voice_id", voice); put("language", "de")
            }
            Provider.OPENAI -> "https://api.openai.com/v1/audio/speech" to buildJsonObject {
                put("model", "gpt-4o-mini-tts"); put("voice", voice); put("input", text); put("response_format", "mp3")
            }
        }
        try {
            val file = File(context.cacheDir, "voice-preview.mp3")
            http.newCall(
                Request.Builder().url(url).header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
            ).execute().use { r ->
                if (!r.isSuccessful) return@withContext FailureKind.fromHttp(r.code, runCatching { r.peekBody(2048).string() }.getOrNull()).userMessage
                file.outputStream().use { r.body.byteStream().copyTo(it) }
            }
            withContext(Dispatchers.Main) {
                player?.release()
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare()
                    start()
                }
            }
            null
        } catch (e: IOException) {
            FailureKind.fromThrowable(e).userMessage
        }
    }
}
