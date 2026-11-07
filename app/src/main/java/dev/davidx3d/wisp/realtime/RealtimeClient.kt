package dev.davidx3d.wisp.realtime

import dev.davidx3d.wisp.i18n.tr
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What went wrong, in terms the user can act on. */
enum class FailureKind(val userMessage: String, val retryable: Boolean) {
    AUTH(tr("Der API-Key ist ungültig oder abgelaufen. Prüf ihn in den Einstellungen.", "The API key is invalid or expired. Check it in the settings."), false),
    QUOTA(tr("Kontingent aufgebraucht oder Limit erreicht. Prüfe dein Anbieter-Konto und dessen Rücksetzzeit.", "Quota exhausted or limit reached. Check your provider account and its quota reset time."), false),
    RATE_LIMIT(tr("Zu viele Anfragen. Kurz warten und nochmal versuchen.", "Too many requests. Wait a moment and try again."), true),
    MODEL(tr("Dieses Modell ist für deinen Key nicht verfügbar. Wähl ein anderes in den Einstellungen.", "This model is not available for your key. Pick another one in the settings."), false),
    NETWORK(tr("Keine Verbindung zum Internet.", "No internet connection."), true),
    SERVER(tr("Der Server des Anbieters hat ein Problem.", "The provider's server has a problem."), true),
    CLOSED(tr("Die Verbindung wurde getrennt.", "The connection was closed."), true),
    ;

    companion object {
        fun fromHttp(code: Int, body: String?): FailureKind = when {
            code == 401 || code == 403 -> if (body?.contains("quota", true) == true || body?.contains("credit", true) == true) QUOTA else AUTH
            code == 402 -> QUOTA
            code == 429 -> if (body?.contains("quota", true) == true || body?.contains("credit", true) == true || body?.contains("balance", true) == true) QUOTA else RATE_LIMIT
            code == 404 -> MODEL
            code >= 500 -> SERVER
            else -> CLOSED
        }

        /** Maps realtime `error` event codes. Returns null for recoverable, non-fatal errors. */
        fun fromErrorCode(code: String?, message: String): FailureKind? {
            val c = (code ?: "").lowercase()
            val m = message.lowercase()
            return when {
                c.contains("invalid_api_key") || c == "unauthorized" || c == "authentication_error" -> AUTH
                c.contains("insufficient_quota") || m.contains("insufficient credits") || m.contains("exceeded your current quota") -> QUOTA
                c.contains("model_not_found") || c == "not_found" -> MODEL
                c == "permission_denied" || c == "unauthenticated" -> AUTH
                c == "resource_exhausted" -> RATE_LIMIT
                c == "unavailable" || c == "internal" -> SERVER
                else -> null
            }
        }

        fun fromThrowable(t: Throwable): FailureKind = when (t) {
            is UnknownHostException -> NETWORK
            is SocketTimeoutException -> NETWORK
            is IOException -> if (t.message?.contains("unreachable", true) == true) NETWORK else CLOSED
            else -> CLOSED
        }
    }
}

sealed interface ConnectionSignal {
    data object Open : ConnectionSignal
    data class Message(val text: String) : ConnectionSignal
    data class Closed(val failure: FailureKind?, val detail: String?) : ConnectionSignal
}

/**
 * One WebSocket to a realtime endpoint. The API key goes only into the Authorization
 * header and is never logged; OkHttp logging is not installed on this client.
 */
class RealtimeClient(private val http: OkHttpClient) {

    private var socket: WebSocket? = null

    fun connect(url: String, apiKey: String, onSignal: (ConnectionSignal) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header(if (url.startsWith("wss://generativelanguage.googleapis.com/")) "x-goog-api-key" else "Authorization",
                if (url.startsWith("wss://generativelanguage.googleapis.com/")) apiKey else "Bearer $apiKey")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onSignal(ConnectionSignal.Open)
            override fun onMessage(webSocket: WebSocket, text: String) = onSignal(ConnectionSignal.Message(text))
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onSignal(ConnectionSignal.Message(bytes.utf8()))
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val failure = if (code == 1000) null else closeCodeFailure(code, reason)
                onSignal(ConnectionSignal.Closed(failure, reason.ifBlank { null }))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = if (response != null) {
                    val body = runCatching { response.peekBody(4096).string() }.getOrNull()
                    FailureKind.fromHttp(response.code, body)
                } else {
                    FailureKind.fromThrowable(t)
                }
                onSignal(ConnectionSignal.Closed(failure, t.javaClass.simpleName))
            }
        })
    }

    fun send(text: String): Boolean = if (text.isBlank()) true else socket?.send(text) ?: false

    /** Bytes queued but not yet sent; used to avoid piling up audio on a slow link. */
    fun queueSize(): Long = socket?.queueSize() ?: 0

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun closeCodeFailure(code: Int, reason: String): FailureKind = when {
        code == 4001 || code == 1008 && reason.contains("auth", true) -> FailureKind.AUTH
        reason.contains("quota", true) || reason.contains("credit", true) -> FailureKind.QUOTA
        code == 1011 -> FailureKind.SERVER
        else -> FailureKind.CLOSED
    }
}
