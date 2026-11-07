package dev.davidx3d.wisp.tools.impl

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import dev.davidx3d.wisp.services.ScreenContext
import dev.davidx3d.wisp.services.WispAccessibilityService
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
suspend fun currentLocation(context: Context): Location? {
    val lm = context.getSystemService(LocationManager::class.java)
    val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        ?: return null
    val fresh = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            cont.invokeOnCancellation { signal.cancel() }
        }
    }
    return fresh ?: listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

suspend fun reverseGeocode(context: Context, lat: Double, lon: Double): Address? {
    if (!Geocoder.isPresent()) return null
    val g = Geocoder(context, Locale.GERMAN)
    return if (Build.VERSION.SDK_INT >= 33) {
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont ->
                g.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) { cont.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { cont.resume(null) }
                })
            }
        }
    } else {
        withContext(Dispatchers.IO) {
            @Suppress("DEPRECATION")
            runCatching { g.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        }
    }
}

class LocationTool : Tool {
    override val name = "get_location"
    override val title = "Standort"
    override val description = "Ermittelt den aktuellen Standort (Adresse, Ort)."
    override val category = ToolCategory.INFO
    override val permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION)

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val loc = currentLocation(ctx.app) ?: return ToolResult.fail("unavailable", "Ich bekomme gerade keinen Standort. Ist die Standortermittlung an?")
        val a = reverseGeocode(ctx.app, loc.latitude, loc.longitude)
        val place = a?.locality ?: a?.subAdminArea ?: "unbekannt"
        return ToolResult.ok(
            "📍 $place",
            "address" to a?.getAddressLine(0),
            "city" to a?.locality,
            "district" to a?.subLocality,
            "country" to a?.countryName,
            "accuracy_m" to loc.accuracy.roundToInt(),
        )
    }
}

class WeatherTool(private val http: OkHttpClient) : Tool {
    override val name = "get_weather"
    override val title = "Wetter"
    override val description = "Aktuelles Wetter und Vorhersage für die nächsten Tage (Open-Meteo). Ohne Ort: aktueller Standort."
    override val category = ToolCategory.INFO
    override val optionalAccess = listOf<SpecialAccess>()
    override val parameters = schema {
        string("place", "Ort, z. B. München. Leer = aktueller Standort")
        integer("days", "Tage Vorhersage 1-7 (Standard 3)")
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val place = args.string("place")
        val (lat, lon, name) = if (place != null) {
            geocode(place) ?: return@withContext ToolResult.fail("not_found", "Den Ort „$place“ kenne ich nicht.")
        } else {
            val granted = ctx.app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return@withContext ToolResult.missingPermission("Standort (oder nenne einen Ort)")
            val loc = currentLocation(ctx.app) ?: return@withContext ToolResult.fail("unavailable", "Kein Standort verfügbar. Nenne einen Ort.")
            Triple(loc.latitude, loc.longitude, reverseGeocode(ctx.app, loc.latitude, loc.longitude)?.locality ?: "hier")
        }
        val days = (args.int("days") ?: 3).coerceIn(1, 7)
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(Locale.US, lat))
            .addQueryParameter("longitude", "%.4f".format(Locale.US, lon))
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation,relative_humidity_2m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", days.toString())
            .build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use {
            if (!it.isSuccessful) return@withContext ToolResult.fail("failed", "Der Wetterdienst antwortet nicht (${it.code}).")
            it.body.string()
        }
        val root = json.parseToJsonElement(body).jsonObject
        val cur = root["current"]!!.jsonObject
        val daily = root["daily"]!!.jsonObject
        fun arr(k: String) = daily[k] as JsonArray
        val temp = cur.d("temperature_2m")
        val forecast = arr("time").indices.map { i ->
            mapOf(
                "date" to (arr("time")[i] as JsonPrimitive).content,
                "condition" to wmo((arr("weather_code")[i] as JsonPrimitive).intOrNull ?: 0),
                "max_c" to (arr("temperature_2m_max")[i] as JsonPrimitive).doubleOrNull?.roundToInt(),
                "min_c" to (arr("temperature_2m_min")[i] as JsonPrimitive).doubleOrNull?.roundToInt(),
                "rain_chance_percent" to (arr("precipitation_probability_max")[i] as? JsonPrimitive)?.intOrNull,
            )
        }
        ToolResult.ok(
            "🌤 $name ${temp?.roundToInt()} °C",
            "place" to name,
            "now" to mapOf(
                "condition" to wmo(cur["weather_code"]?.jsonPrimitive?.intOrNull ?: 0),
                "temperature_c" to temp?.roundToInt(),
                "feels_like_c" to cur.d("apparent_temperature")?.roundToInt(),
                "wind_kmh" to cur.d("wind_speed_10m")?.roundToInt(),
                "humidity_percent" to cur.d("relative_humidity_2m")?.roundToInt(),
            ),
            "forecast" to forecast,
        )
    }

    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun geocode(place: String): Triple<Double, Double, String>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", place).addQueryParameter("count", "1").addQueryParameter("language", "de").build()
        val body = http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.string() else return null }
        val r = (json.parseToJsonElement(body).jsonObject["results"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return null
        return Triple(r.d("latitude") ?: return null, r.d("longitude") ?: return null, (r["name"] as? JsonPrimitive)?.content ?: place)
    }

    companion object {
        /** WMO weather interpretation codes → German. */
        fun wmo(code: Int): String = when (code) {
            0 -> "klar"
            1 -> "überwiegend klar"
            2 -> "teils bewölkt"
            3 -> "bedeckt"
            45, 48 -> "Nebel"
            51, 53, 55 -> "Nieselregen"
            56, 57 -> "gefrierender Nieselregen"
            61 -> "leichter Regen"
            63 -> "Regen"
            65 -> "starker Regen"
            66, 67 -> "gefrierender Regen"
            71 -> "leichter Schneefall"
            73 -> "Schneefall"
            75 -> "starker Schneefall"
            77 -> "Schneegriesel"
            80, 81 -> "Regenschauer"
            82 -> "heftige Regenschauer"
            85, 86 -> "Schneeschauer"
            95 -> "Gewitter"
            96, 99 -> "Gewitter mit Hagel"
            else -> "unbekannt"
        }
    }
}

class ReadScreenTool : Tool {
    override val name = "read_screen"
    override val title = "Bildschirm vorlesen"
    override val description = "Liest den Text, der gerade auf dem Bildschirm (in der App hinter Wisp) zu sehen ist."
    override val category = ToolCategory.SCREEN
    override val optionalAccess = listOf(SpecialAccess.ACCESSIBILITY)

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val fromAssist = ScreenContext.latest()
        val text = fromAssist ?: withContext(Dispatchers.Main) { WispAccessibilityService.instance?.screenText() }
        if (text.isNullOrBlank()) {
            return ToolResult.fail(
                "unavailable",
                "Ich sehe den Bildschirm gerade nicht. Aktiviere die Bedienungshilfe „Wisp Bildschirm lesen“ " +
                    "oder in den Assistent-Einstellungen „Text auf dem Bildschirm verwenden“.",
                "Bildschirm nicht lesbar",
            )
        }
        return ToolResult.ok("👁 Bildschirm gelesen", "source" to if (fromAssist != null) "assist" else "accessibility", "text" to text.take(6000))
    }
}
