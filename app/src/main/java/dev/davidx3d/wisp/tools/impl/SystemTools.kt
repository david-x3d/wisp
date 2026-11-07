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
import dev.davidx3d.wisp.i18n.tr

private suspend fun privileged(ctx: ToolContext, command: String, chip: String, vararg data: Pair<String, Any?>): ToolResult {
    val status = PrivilegedShell.status(ctx.settings.rootEnabled)
    if (!status.available) {
        val msg = when {
            !status.shizukuRunning && !ctx.settings.rootEnabled ->
                tr("Shizuku läuft nicht. Starte die Shizuku-App (kabelloses Debugging) oder erlaube Root in den Wisp-Einstellungen.", "Shizuku is not running. Start the Shizuku app (wireless debugging) or allow root in Wisp's settings.")
            status.shizukuRunning && !status.shizukuGranted ->
                tr("Wisp hat noch keine Shizuku-Berechtigung. Erteile sie in Wisp unter Einstellungen → Systemsteuerung.", "Wisp doesn't have Shizuku permission yet. Grant it in Wisp under Settings → System control.")
            else -> tr("Weder Shizuku noch Root ist verfügbar. Starte Shizuku oder erteile Wisp Root-Rechte in KernelSU.", "Neither Shizuku nor root is available. Start Shizuku or grant Wisp root in KernelSU.")
        }
        return ToolResult.fail("no_privileged_access", msg, tr("Shizuku/Root nicht verfügbar", "Shizuku/root not available"))
    }
    val r = PrivilegedShell.run(command, ctx.settings.rootEnabled)
    if (!r.ok) return ToolResult.fail("command_failed", tr("Der Systembefehl ist fehlgeschlagen: ", "The system command failed: ") + r.err.ifBlank { "Code ${r.code}" }.take(200))
    return ToolResult.ok(chip, "via" to status.backend?.label, *data)
}

class ToggleConnectivityTool : Tool {
    override val name = "set_connectivity"
    override val title = tr("WLAN, Daten, Bluetooth, Flugmodus", "Wi-Fi, data, Bluetooth, airplane mode")
    override val description = tr("Schaltet WLAN, mobile Daten, Bluetooth, Flugmodus, NFC oder Standort an oder aus (über Shizuku oder Root).", "Turns Wi-Fi, mobile data, Bluetooth, airplane mode, NFC or location on or off (via Shizuku or root).")
    override val category = ToolCategory.SYSTEM
    override val optionalAccess = listOf(SpecialAccess.PRIVILEGED)
    override val parameters = schema {
        string("target", tr("Was geschaltet wird", "What to switch"), required = true, enum = listOf("wifi", "mobile_data", "bluetooth", "airplane_mode", "nfc", "location"))
        boolean("enabled", tr("true = an, false = aus", "true = on, false = off"), required = true)
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val on = args.requireBool("enabled")
        val en = if (on) "enable" else "disable"
        val (cmd, label) = when (args.requireString("target")) {
            "wifi" -> "svc wifi $en" to tr("WLAN", "Wi-Fi")
            "mobile_data" -> "svc data $en" to tr("Mobile Daten", "Mobile data")
            "bluetooth" -> "svc bluetooth $en" to "Bluetooth"
            "airplane_mode" -> "cmd connectivity airplane-mode $en" to tr("Flugmodus", "Airplane mode")
            "nfc" -> "svc nfc $en" to "NFC"
            "location" -> "cmd location set-location-enabled ${on}" to tr("Standort", "Location")
            else -> return ToolResult.fail("invalid_arguments", tr("Unbekanntes Ziel.", "Unknown target."))
        }
        val target = args.requireString("target")
        if (!PrivilegedShell.status(ctx.settings.rootEnabled).available) {
            // Without Shizuku/root Android only lets apps open its own quick panel.
            connectivityPanel(target)?.let { panel ->
                if (ctx.app.launch(panel)) {
                    return ToolResult.ok(
                        tr("$label-Schalter geöffnet", "Opened the $label switch"),
                        "note" to tr("Ohne Shizuku oder Root darf Wisp das nicht selbst schalten. Ich habe den Schalter geöffnet, der Nutzer muss ihn antippen.", "Without Shizuku or root Wisp can't switch this itself. I opened the switch; the user has to tap it."),
                    )
                }
            }
        }
        val icon = mapOf("wifi" to "📶", "mobile_data" to "📱", "bluetooth" to "🔷", "airplane_mode" to "✈️", "nfc" to "📳", "location" to "📍")[target]
        return privileged(ctx, cmd, "$icon $label " + if (on) tr("an", "on") else tr("aus", "off"), "target" to label, "enabled" to on)
    }
}

class KillAppTool : Tool {
    override val name = "force_stop_app"
    override val title = tr("App beenden", "Force-stop app")
    override val description = tr("Beendet eine App vollständig (force-stop) über Shizuku oder Root.", "Fully stops an app (force-stop) via Shizuku or root.")
    override val category = ToolCategory.SYSTEM
    override val specialAccess = listOf(SpecialAccess.PRIVILEGED)
    override val parameters = schema { string("app", tr("Name der App, z. B. Spotify", "App name, e.g. Spotify"), required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val apps = installedLaunchableApps(ctx.app)
        val match = Fuzzy.rank(args.requireString("app"), apps) { it.label }
        val app = match.singleOrNull()?.item
            ?: return if (match.isEmpty()) ToolResult.fail("not_found", tr("Keine App mit diesem Namen gefunden.", "No app with this name found."))
            else ToolResult.fail("ambiguous", tr("Mehrere Apps passen: ${match.take(5).joinToString { it.item.label }}. Welche meinst du?", "Several apps match: ${match.take(5).joinToString { it.item.label }}. Which one?"))
        return privileged(ctx, "am force-stop ${app.packageName}", tr("🛑 ${app.label} beendet", "🛑 ${app.label} stopped"), "app" to app.label)
    }
}

class RebootTool : Tool {
    override val name = "reboot_device"
    override val title = tr("Neustart", "Restart")
    override val description = tr("Startet das Handy neu (über Shizuku oder Root). Nur auf ausdrücklichen Wunsch.", "Restarts the phone (via Shizuku or root). Only when explicitly asked.")
    override val category = ToolCategory.SYSTEM
    override val specialAccess = listOf(SpecialAccess.PRIVILEGED)
    override val confirmable = true
    override val parameters = schema {
        string("mode", tr("Art des Neustarts", "Restart type"), enum = listOf("normal", "recovery", "bootloader"))
    }

    override fun confirmation(args: ToolArgs, ctx: ToolContext): String =
        when (args.string("mode")) {
            "recovery" -> tr("Handy in den Recovery-Modus neu starten", "Restart the phone into recovery")
            "bootloader" -> tr("Handy in den Bootloader neu starten", "Restart the phone into the bootloader")
            else -> tr("Handy jetzt neu starten", "Restart the phone now")
        }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val cmd = when (args.string("mode")) {
            "recovery" -> "svc power reboot recovery"
            "bootloader" -> "svc power reboot bootloader"
            else -> "svc power reboot"
        }
        return privileged(ctx, cmd, tr("🔄 Neustart", "🔄 Restarting"))
    }
}

/** Opens the Wi-Fi/internet panel when no privileged access exists (Android's own UI). */
internal fun connectivityPanel(target: String): Intent? = when (target) {
    "wifi" -> Intent(android.provider.Settings.Panel.ACTION_WIFI)
    "mobile_data" -> Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    "nfc" -> Intent(android.provider.Settings.Panel.ACTION_NFC)
    else -> null
}
