package dev.davidx3d.wisp.tools.impl

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

internal fun Context.launch(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(packageManager) == null) return false
    startActivity(intent)
    return true
}

internal fun noApp(what: String) = ToolResult.fail("no_app", "Auf dem Handy ist keine App installiert, die $what kann.")

internal fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it h" },
        m.takeIf { it > 0 }?.let { "$it min" },
        s.takeIf { it > 0 }?.let { "$it s" },
    ).joinToString(" ").ifEmpty { "0 s" }
}

class SetTimerTool : Tool {
    override val name = "set_timer"
    override val title = "Timer stellen"
    override val description = "Stellt einen Countdown-Timer in der Uhr-App, ohne sie zu öffnen."
    override val category = ToolCategory.TIME
    override val parameters = schema {
        integer("hours", "Stunden")
        integer("minutes", "Minuten")
        integer("seconds", "Sekunden")
        string("label", "Optionaler Name, z. B. Pizza")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val total = (args.int("hours") ?: 0) * 3600 + (args.int("minutes") ?: 0) * 60 + (args.int("seconds") ?: 0)
        if (total <= 0) return ToolResult.fail("invalid_arguments", "Wie lange soll der Timer laufen?")
        if (total > 24 * 3600) return ToolResult.fail("invalid_arguments", "Ein Timer kann höchstens 24 Stunden laufen.")
        val label = args.string("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (!ctx.app.launch(intent)) return noApp("Timer stellen")
        val d = formatDuration(total)
        return ToolResult.ok("⏱ Timer $d gestellt", "duration" to d, "label" to label)
    }
}

class CancelTimerTool : Tool {
    override val name = "cancel_timer"
    override val title = "Timer abbrechen"
    override val description = "Beendet bzw. verwirft den laufenden oder klingelnden Timer."
    override val category = ToolCategory.TIME

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        if (!ctx.app.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER))) return noApp("Timer abbrechen")
        return ToolResult.ok("⏱ Timer abgebrochen", "note" to "Die Uhr-App hat den Befehl bekommen. Manche Uhr-Apps beenden nur klingelnde Timer.")
    }
}

private val dayMap = mapOf(
    "mo" to Calendar.MONDAY, "montag" to Calendar.MONDAY, "monday" to Calendar.MONDAY,
    "di" to Calendar.TUESDAY, "dienstag" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY,
    "mi" to Calendar.WEDNESDAY, "mittwoch" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY,
    "do" to Calendar.THURSDAY, "donnerstag" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY,
    "fr" to Calendar.FRIDAY, "freitag" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY,
    "sa" to Calendar.SATURDAY, "samstag" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY,
    "so" to Calendar.SUNDAY, "sonntag" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY,
)

/** "Mo", "montag", "werktags", "wochenende", "täglich" → Calendar day constants. */
fun parseDays(input: List<String>): List<Int> = input.flatMap { raw ->
    when (val d = raw.trim().lowercase(Locale.GERMAN)) {
        "werktags", "weekdays", "wochentags" -> listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)
        "wochenende", "weekend" -> listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        "täglich", "taeglich", "jeden tag", "daily", "every day" -> (Calendar.SUNDAY..Calendar.SATURDAY).toList()
        else -> listOfNotNull(dayMap[d] ?: dayMap[d.take(2)])
    }
}.distinct()

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val title = "Wecker stellen"
    override val description = "Stellt einen Wecker in der Uhr-App. Für wiederkehrende Wecker Wochentage angeben."
    override val category = ToolCategory.TIME
    override val parameters = schema {
        integer("hour", "Stunde 0-23", required = true)
        integer("minute", "Minute 0-59")
        stringArray("days", "Wochentage für Wiederholung: mo, di, mi, do, fr, sa, so, werktags, wochenende oder täglich")
        string("label", "Optionaler Name des Weckers")
        boolean("vibrate", "Vibrieren (Standard: an)")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val hour = args.requireInt("hour")
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.fail("invalid_arguments", "Die Uhrzeit $hour:$minute gibt es nicht.")
        val days = parseDays(args.stringList("days"))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, args.bool("vibrate") ?: true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (!ctx.app.launch(intent)) return noApp("Wecker stellen")
        val time = "%02d:%02d".format(hour, minute)
        return ToolResult.ok("⏰ Wecker $time gestellt", "time" to time, "repeating_days" to days.size)
    }
}

class NextAlarmTool : Tool {
    override val name = "get_alarms"
    override val title = "Wecker anzeigen"
    override val description = "Nennt den nächsten gestellten Wecker. Mit open=true wird zusätzlich die Weckerliste geöffnet."
    override val category = ToolCategory.TIME
    override val parameters = schema { boolean("open", "Weckerliste in der Uhr-App öffnen") }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        if (args.bool("open") == true) ctx.app.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        val next = ctx.app.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return ToolResult.ok("⏰ Kein Wecker gestellt", "next_alarm" to null)
        val t = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
        val text = t.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM 'um' HH:mm", Locale.GERMAN))
        return ToolResult.ok("⏰ Nächster Wecker: ${t.format(DateTimeFormatter.ofPattern("EE HH:mm", Locale.GERMAN))}", "next_alarm" to text)
    }
}

class DismissAlarmTool : Tool {
    override val name = "dismiss_alarm"
    override val title = "Wecker ausschalten"
    override val description = "Schaltet Wecker aus: den nächsten, nach Uhrzeit, nach Name oder alle. " +
        "Android erlaubt das nur über die Uhr-App, die eventuell kurz nachfragt."
    override val category = ToolCategory.TIME
    override val parameters = schema {
        string("mode", "Welche Wecker", required = true, enum = listOf("next", "time", "label", "all"))
        integer("hour", "Stunde (bei mode=time)")
        integer("minute", "Minute (bei mode=time)")
        string("label", "Name (bei mode=label)")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM)
        when (args.requireString("mode")) {
            "next" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            "all" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
            "label" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                .putExtra(AlarmClock.EXTRA_MESSAGE, args.requireString("label"))
            "time" -> intent.putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                .putExtra(AlarmClock.EXTRA_HOUR, args.requireInt("hour"))
                .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute") ?: 0)
            else -> return ToolResult.fail("invalid_arguments", "mode muss next, time, label oder all sein.")
        }
        if (!ctx.app.launch(intent)) return noApp("Wecker ausschalten")
        return ToolResult.ok("⏰ Wecker ausgeschaltet", "note" to "An die Uhr-App übergeben.")
    }
}
