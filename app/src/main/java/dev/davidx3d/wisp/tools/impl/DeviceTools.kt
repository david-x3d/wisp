package dev.davidx3d.wisp.tools.impl

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import dev.davidx3d.wisp.services.WispNotificationListener
import dev.davidx3d.wisp.tools.Fuzzy
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class FlashlightTool : Tool {
    override val name = "set_flashlight"
    override val title = "Taschenlampe"
    override val description = "Schaltet die Taschenlampe an oder aus."
    override val category = ToolCategory.DEVICE
    override val parameters = schema { boolean("on", "true = an, false = aus", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val on = args.requireBool("on")
        val cm = ctx.app.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            val c = cm.getCameraCharacteristics(it)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return ToolResult.fail("unsupported", "Dieses Handy hat keine Taschenlampe.")
        cm.setTorchMode(id, on)
        return ToolResult.ok(if (on) "🔦 Taschenlampe an" else "🔦 Taschenlampe aus", "on" to on)
    }
}

class VolumeTool : Tool {
    override val name = "set_volume"
    override val title = "Lautstärke"
    override val description = "Setzt die Lautstärke für Medien, Klingelton, Wecker oder Benachrichtigungen (0-100 %) oder macht lauter/leiser."
    override val category = ToolCategory.DEVICE
    override val parameters = schema {
        string("stream", "Welche Lautstärke", required = true, enum = listOf("media", "ring", "alarm", "notification", "call"))
        integer("percent", "Zielwert 0-100")
        string("change", "Relativ ändern", enum = listOf("up", "down", "mute", "unmute"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val am = ctx.app.getSystemService(AudioManager::class.java)
        val (stream, label) = when (args.requireString("stream")) {
            "ring" -> AudioManager.STREAM_RING to "Klingelton"
            "alarm" -> AudioManager.STREAM_ALARM to "Wecker"
            "notification" -> AudioManager.STREAM_NOTIFICATION to "Benachrichtigungen"
            "call" -> AudioManager.STREAM_VOICE_CALL to "Anrufe"
            else -> AudioManager.STREAM_MUSIC to "Medien"
        }
        val max = am.getStreamMaxVolume(stream)
        val min = am.getStreamMinVolume(stream)
        val percent = args.int("percent")
        when {
            percent != null -> {
                val idx = (min + (max - min) * percent.coerceIn(0, 100) / 100.0).roundToInt()
                am.setStreamVolume(stream, idx, AudioManager.FLAG_SHOW_UI)
            }
            else -> when (args.string("change")) {
                "up" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                "down" -> repeat(2) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                "mute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                "unmute" -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                else -> return ToolResult.fail("invalid_arguments", "Gib percent oder change an.")
            }
        }
        val now = ((am.getStreamVolume(stream) - min) * 100.0 / (max - min)).roundToInt()
        return ToolResult.ok("🔊 $label $now %", "stream" to label, "percent" to now)
    }
}

class DndTool : Tool {
    override val name = "set_do_not_disturb"
    override val title = "Nicht stören"
    override val description = "Schaltet „Bitte nicht stören“ an oder aus."
    override val category = ToolCategory.DEVICE
    override val specialAccess = listOf(SpecialAccess.DND_POLICY)
    override val parameters = schema { boolean("on", "true = an", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val on = args.requireBool("on")
        ctx.app.getSystemService(NotificationManager::class.java).setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL,
        )
        return ToolResult.ok(if (on) "🌙 Nicht stören an" else "🌙 Nicht stören aus", "on" to on)
    }
}

class BrightnessTool : Tool {
    override val name = "set_brightness"
    override val title = "Helligkeit"
    override val description = "Setzt die Bildschirmhelligkeit in Prozent oder schaltet automatische Helligkeit an/aus."
    override val category = ToolCategory.DEVICE
    override val specialAccess = listOf(SpecialAccess.WRITE_SETTINGS)
    override val parameters = schema {
        integer("percent", "0-100")
        boolean("auto", "Automatische Helligkeit")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val cr = ctx.app.contentResolver
        val auto = args.bool("auto")
        val percent = args.int("percent")
        if (auto == null && percent == null) return ToolResult.fail("invalid_arguments", "Gib percent oder auto an.")
        if (percent != null) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            // Perceived brightness is roughly quadratic, so 50 % should not be blinding.
            val p = percent.coerceIn(0, 100) / 100.0
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (1 + p * p * 254).roundToInt())
        }
        if (auto != null) {
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
        }
        val chip = when {
            auto == true -> "☀️ Auto-Helligkeit an"
            percent != null -> "☀️ Helligkeit $percent %"
            else -> "☀️ Auto-Helligkeit aus"
        }
        return ToolResult.ok(chip, "percent" to percent, "auto" to auto)
    }
}

class RotationTool : Tool {
    override val name = "set_auto_rotate"
    override val title = "Automatisch drehen"
    override val description = "Schaltet das automatische Drehen des Bildschirms an oder aus."
    override val category = ToolCategory.DEVICE
    override val specialAccess = listOf(SpecialAccess.WRITE_SETTINGS)
    override val parameters = schema { boolean("on", "true = an", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val on = args.requireBool("on")
        Settings.System.putInt(ctx.app.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        return ToolResult.ok(if (on) "🔄 Drehen an" else "🔄 Drehen aus", "on" to on)
    }
}

class DeviceStatusTool : Tool {
    override val name = "get_device_status"
    override val title = "Gerätestatus"
    override val description = "Akkustand, Ladezustand, Uhrzeit, Datum und freier Speicher."
    override val category = ToolCategory.DEVICE

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val bm = ctx.app.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val battery = ctx.app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val chargeLeft = bm.computeChargeTimeRemaining().takeIf { it > 0 }?.let { (it / 60_000).toInt() }
        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val now = LocalDateTime.now()
        return ToolResult.ok(
            "🔋 $level %",
            "battery_percent" to level,
            "charging" to (plugged != 0 || bm.isCharging),
            "charger" to when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "Netzteil"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "kabellos"
                else -> null
            },
            "minutes_until_full" to chargeLeft,
            "time" to now.format(DateTimeFormatter.ofPattern("HH:mm")),
            "date" to now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN)),
            "storage_free_gb" to "%.1f".format(Locale.GERMAN, stat.availableBytes / gb),
            "storage_total_gb" to "%.0f".format(Locale.GERMAN, stat.totalBytes / gb),
        )
    }
}

class ClipboardTool : Tool {
    override val name = "clipboard"
    override val title = "Zwischenablage"
    override val description = "Liest die Zwischenablage oder kopiert Text hinein."
    override val category = ToolCategory.DEVICE
    override val parameters = schema {
        string("action", "Lesen oder schreiben", required = true, enum = listOf("read", "write"))
        string("text", "Text zum Kopieren (bei write)")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult = withContext(Dispatchers.Main) {
        val cm = ctx.app.getSystemService(ClipboardManager::class.java)
        if (args.requireString("action") == "write") {
            val text = args.requireString("text")
            cm.setPrimaryClip(ClipData.newPlainText("Wisp", text))
            ToolResult.ok("📋 Kopiert", "copied_chars" to text.length)
        } else {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx.app)?.toString()
            if (text == null) {
                ToolResult.fail(
                    "unavailable",
                    "Die Zwischenablage ist leer oder Android lässt mich gerade nicht ran (nur möglich, solange Wisp im Vordergrund ist).",
                    "Zwischenablage nicht lesbar",
                )
            } else {
                ToolResult.ok("📋 Zwischenablage gelesen", "text" to text.take(2000))
            }
        }
    }
}

data class AppEntry(val label: String, val packageName: String, val activity: String)

internal fun installedLaunchableApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0).map {
        AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name)
    }.distinctBy { it.packageName }
}

class OpenAppTool : Tool {
    override val name = "open_app"
    override val title = "Apps öffnen"
    override val description = "Öffnet eine installierte App per Name."
    override val category = ToolCategory.DEVICE
    override val parameters = schema { string("app", "Name der App", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val q = args.requireString("app")
        val matches = Fuzzy.rank(q, installedLaunchableApps(ctx.app)) { it.label }
        val app = when {
            matches.isEmpty() -> return ToolResult.fail("not_found", "Ich finde keine App „$q“.", "App „$q“ nicht gefunden")
            matches.size > 1 && matches[0].score < 0.9 ->
                return ToolResult.fail("ambiguous", "Meinst du ${matches.take(4).joinToString(" oder ") { it.item.label }}?")
            else -> matches.first().item
        }
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.packageName, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        ctx.app.launch(intent)
        return ToolResult.ok("📱 ${app.label} geöffnet", "app" to app.label)
    }
}

private fun activeController(context: Context): MediaController? = runCatching {
    val msm = context.getSystemService(MediaSessionManager::class.java)
    val sessions = msm.getActiveSessions(ComponentName(context, WispNotificationListener::class.java))
    sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
}.getOrNull()

class MediaControlTool : Tool {
    override val name = "media_control"
    override val title = "Medien steuern"
    override val description = "Steuert die gerade aktive Medienwiedergabe (Spotify, YouTube Music, Podcasts …)."
    override val category = ToolCategory.MEDIA
    override val optionalAccess = listOf(SpecialAccess.NOTIFICATION_LISTENER)
    override val parameters = schema {
        string("action", "Befehl", required = true, enum = listOf("play", "pause", "toggle", "next", "previous", "stop"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val action = args.requireString("action")
        val controller = activeController(ctx.app)
        if (controller != null) {
            val t = controller.transportControls
            when (action) {
                "play" -> t.play()
                "pause" -> t.pause()
                "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                "next" -> t.skipToNext()
                "previous" -> t.skipToPrevious()
                "stop" -> t.stop()
            }
        } else {
            // Without notification access: plain media key events to whoever owns media focus.
            val code = when (action) {
                "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = ctx.app.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        val label = mapOf("play" to "▶️ Wiedergabe", "pause" to "⏸ Pause", "toggle" to "⏯ Play/Pause", "next" to "⏭ Nächster Titel", "previous" to "⏮ Vorheriger Titel", "stop" to "⏹ Stopp")[action] ?: action
        return ToolResult.ok(label, "action" to action, "app" to controller?.packageName)
    }
}

class NowPlayingTool : Tool {
    override val name = "now_playing"
    override val title = "Aktueller Titel"
    override val description = "Sagt, welcher Titel gerade läuft (Titel, Künstler, App)."
    override val category = ToolCategory.MEDIA
    override val specialAccess = listOf(SpecialAccess.NOTIFICATION_LISTENER)

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val c = activeController(ctx.app) ?: return ToolResult.ok("🎵 Nichts läuft", "playing" to false)
        val md = c.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val appName = runCatching {
            ctx.app.packageManager.getApplicationLabel(ctx.app.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        return ToolResult.ok(
            "🎵 ${title ?: "Unbekannt"}",
            "playing" to (c.playbackState?.state == PlaybackState.STATE_PLAYING),
            "title" to title, "artist" to artist, "album" to md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            "app" to appName,
        )
    }
}
