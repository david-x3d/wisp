package dev.davidx3d.wisp.tools.impl

import android.content.Intent
import dev.davidx3d.wisp.tools.Fuzzy
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import dev.davidx3d.wisp.tools.system.PrivilegedShell

private suspend fun privileged(ctx: ToolContext, command: String, chip: String, vararg data: Pair<String, Any?>): ToolResult {
    val status = PrivilegedShell.status(ctx.settings.rootEnabled)
    if (!status.available) {
        val msg = when {
            !status.shizukuRunning && !ctx.settings.rootEnabled ->
                "Shizuku läuft nicht. Starte die Shizuku-App (kabelloses Debugging) oder erlaube Root in den Wisp-Einstellungen."
            status.shizukuRunning && !status.shizukuGranted ->
                "Wisp hat noch keine Shizuku-Berechtigung. Erteile sie in Wisp unter Einstellungen → Systemsteuerung."
            else -> "Weder Shizuku noch Root ist verfügbar. Starte Shizuku oder erteile Wisp Root-Rechte in KernelSU."
        }
        return ToolResult.fail("no_privileged_access", msg, "Shizuku/Root nicht verfügbar")
    }
    val r = PrivilegedShell.run(command, ctx.settings.rootEnabled)
    if (!r.ok) return ToolResult.fail("command_failed", "Der Systembefehl ist fehlgeschlagen: ${r.err.ifBlank { "Code ${r.code}" }.take(200)}")
    return ToolResult.ok(chip, "via" to status.backend?.label, *data)
}

class ToggleConnectivityTool : Tool {
    override val name = "set_connectivity"
    override val title = "WLAN, Daten, Bluetooth, Flugmodus"
    override val description = "Schaltet WLAN, mobile Daten, Bluetooth, Flugmodus, NFC oder Standort an oder aus (über Shizuku oder Root)."
    override val category = ToolCategory.SYSTEM
    override val optionalAccess = listOf(SpecialAccess.PRIVILEGED)
    override val parameters = schema {
        string("target", "Was geschaltet wird", required = true, enum = listOf("wifi", "mobile_data", "bluetooth", "airplane_mode", "nfc", "location"))
        boolean("enabled", "true = an, false = aus", required = true)
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val on = args.requireBool("enabled")
        val en = if (on) "enable" else "disable"
        val (cmd, label) = when (args.requireString("target")) {
            "wifi" -> "svc wifi $en" to "WLAN"
            "mobile_data" -> "svc data $en" to "Mobile Daten"
            "bluetooth" -> "svc bluetooth $en" to "Bluetooth"
            "airplane_mode" -> "cmd connectivity airplane-mode $en" to "Flugmodus"
            "nfc" -> "svc nfc $en" to "NFC"
            "location" -> "cmd location set-location-enabled ${on}" to "Standort"
            else -> return ToolResult.fail("invalid_arguments", "Unbekanntes Ziel.")
        }
        val target = args.requireString("target")
        if (!PrivilegedShell.status(ctx.settings.rootEnabled).available) {
            // Without Shizuku/root Android only lets apps open its own quick panel.
            connectivityPanel(target)?.let { panel ->
                if (ctx.app.launch(panel)) {
                    return ToolResult.ok(
                        "$label-Schalter geöffnet",
                        "note" to "Ohne Shizuku oder Root darf Wisp das nicht selbst schalten. Ich habe den Schalter geöffnet, David muss ihn antippen.",
                    )
                }
            }
        }
        val icon = mapOf("WLAN" to "📶", "Mobile Daten" to "📱", "Bluetooth" to "🔷", "Flugmodus" to "✈️", "NFC" to "📳", "Standort" to "📍")[label]
        return privileged(ctx, cmd, "$icon $label ${if (on) "an" else "aus"}", "target" to label, "enabled" to on)
    }
}

class KillAppTool : Tool {
    override val name = "force_stop_app"
    override val title = "App beenden"
    override val description = "Beendet eine App vollständig (force-stop) über Shizuku oder Root."
    override val category = ToolCategory.SYSTEM
    override val specialAccess = listOf(SpecialAccess.PRIVILEGED)
    override val parameters = schema { string("app", "Name der App, z. B. Spotify", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val apps = installedLaunchableApps(ctx.app)
        val match = Fuzzy.rank(args.requireString("app"), apps) { it.label }
        val app = match.singleOrNull()?.item
            ?: return if (match.isEmpty()) ToolResult.fail("not_found", "Keine App mit diesem Namen gefunden.")
            else ToolResult.fail("ambiguous", "Mehrere Apps passen: ${match.take(5).joinToString { it.item.label }}. Welche meinst du?")
        return privileged(ctx, "am force-stop ${app.packageName}", "🛑 ${app.label} beendet", "app" to app.label)
    }
}

class RebootTool : Tool {
    override val name = "reboot_device"
    override val title = "Neustart"
    override val description = "Startet das Handy neu (über Shizuku oder Root). Nur auf ausdrücklichen Wunsch."
    override val category = ToolCategory.SYSTEM
    override val specialAccess = listOf(SpecialAccess.PRIVILEGED)
    override val confirmable = true
    override val parameters = schema {
        string("mode", "Art des Neustarts", enum = listOf("normal", "recovery", "bootloader"))
    }

    override fun confirmation(args: ToolArgs, ctx: ToolContext): String =
        when (args.string("mode")) {
            "recovery" -> "Handy in den Recovery-Modus neu starten"
            "bootloader" -> "Handy in den Bootloader neu starten"
            else -> "Handy jetzt neu starten"
        }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val cmd = when (args.string("mode")) {
            "recovery" -> "svc power reboot recovery"
            "bootloader" -> "svc power reboot bootloader"
            else -> "svc power reboot"
        }
        return privileged(ctx, cmd, "🔄 Neustart")
    }
}

/** Opens the Wi-Fi/internet panel when no privileged access exists (Android's own UI). */
internal fun connectivityPanel(target: String): Intent? = when (target) {
    "wifi" -> Intent(android.provider.Settings.Panel.ACTION_WIFI)
    "mobile_data" -> Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    "nfc" -> Intent(android.provider.Settings.Panel.ACTION_NFC)
    else -> null
}
