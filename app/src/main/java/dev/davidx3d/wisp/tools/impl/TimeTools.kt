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
import dev.davidx3d.wisp.i18n.tr

internal fun Context.launch(intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(packageManager) == null) return false
    startActivity(intent)
    return true
}

internal fun noApp(what: String) = ToolResult.fail("no_app", tr("Auf dem Handy ist keine App installiert, die $what kann.", "No app on this phone can $what."))

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
    override val title = tr("Timer stellen", "Set timer")
    override val description = tr("Stellt einen Countdown-Timer in der Uhr-App, ohne sie zu öffnen.", "Sets a countdown timer in the clock app without opening it.")
    override val category = ToolCategory.TIME
    override val parameters = schema {
        integer("hours", tr("Stunden", "Hours"))
        integer("minutes", tr("Minuten", "Minutes"))
        integer("seconds", tr("Sekunden", "Seconds"))
        string("label", tr("Optionaler Name, z. B. Pizza", "Optional name, e.g. pizza"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val total = (args.int("hours") ?: 0) * 3600 + (args.int("minutes") ?: 0) * 60 + (args.int("seconds") ?: 0)
        if (total <= 0) return ToolResult.fail("invalid_arguments", tr("Wie lange soll der Timer laufen?", "How long should the timer run?"))
        if (total > 24 * 3600) return ToolResult.fail("invalid_arguments", tr("Ein Timer kann höchstens 24 Stunden laufen.", "A timer can run for at most 24 hours."))
        val label = args.string("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (label != null) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        if (!ctx.app.launch(intent)) return noApp(tr("Timer stellen", "set timers"))
        val d = formatDuration(total)
        return ToolResult.ok(tr("⏱ Timer $d gestellt", "⏱ Timer set for $d"), "duration" to d, "label" to label)
    }
}

class CancelTimerTool : Tool {
    override val name = "cancel_timer"
    override val title = tr("Timer abbrechen", "Cancel timer")
    override val description = tr("Beendet bzw. verwirft den laufenden oder klingelnden Timer.", "Stops or dismisses the running or ringing timer.")
    override val category = ToolCategory.TIME

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        if (!ctx.app.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER))) return noApp(tr("Timer abbrechen", "cancel timers"))
        return ToolResult.ok(tr("⏱ Timer abgebrochen", "⏱ Timer cancelled"), "note" to tr("Die Uhr-App hat den Befehl bekommen. Manche Uhr-Apps beenden nur klingelnde Timer.", "The clock app got the command. Some clock apps only stop ringing timers."))
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
    override val title = tr("Wecker stellen", "Set alarm")
    override val description = tr("Stellt einen Wecker in der Uhr-App. Für wiederkehrende Wecker Wochentage angeben.", "Sets an alarm in the clock app. Give weekdays for repeating alarms.")
    override val category = ToolCategory.TIME
    override val parameters = schema {
        integer("hour", tr("Stunde 0-23", "Hour 0-23"), required = true)
        integer("minute", tr("Minute 0-59", "Minute 0-59"))
        stringArray("days", tr("Wochentage für Wiederholung: mo, di, mi, do, fr, sa, so, werktags, wochenende oder täglich", "Repeat days: monday … sunday (or mo, tu …), weekdays, weekend or daily"))
        string("label", tr("Optionaler Name des Weckers", "Optional alarm label"))
        boolean("vibrate", tr("Vibrieren (Standard: an)", "Vibrate (default: on)"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val hour = args.requireInt("hour")
        val minute = args.int("minute") ?: 0
        if (hour !in 0..23 || minute !in 0..59) return ToolResult.fail("invalid_arguments", tr("Die Uhrzeit $hour:$minute gibt es nicht.", "$hour:$minute is not a valid time."))
        val days = parseDays(args.stringList("days"))
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_VIBRATE, args.bool("vibrate") ?: true)
        args.string("label")?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (!ctx.app.launch(intent)) return noApp(tr("Wecker stellen", "set alarms"))
        val time = "%02d:%02d".format(hour, minute)
        return ToolResult.ok(tr("⏰ Wecker $time gestellt", "⏰ Alarm set for $time"), "time" to time, "repeating_days" to days.size)
    }
}

class NextAlarmTool : Tool {
    override val name = "get_alarms"
    override val title = tr("Wecker anzeigen", "Show alarms")
    override val description = tr("Nennt den nächsten gestellten Wecker. Mit open=true wird zusätzlich die Weckerliste geöffnet.", "Tells the next alarm. With open=true the alarm list opens as well.")
    override val category = ToolCategory.TIME
    override val parameters = schema { boolean("open", tr("Weckerliste in der Uhr-App öffnen", "Open the alarm list in the clock app")) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        if (args.bool("open") == true) ctx.app.launch(Intent(AlarmClock.ACTION_SHOW_ALARMS))
        val next = ctx.app.getSystemService(AlarmManager::class.java).nextAlarmClock
            ?: return ToolResult.ok(tr("⏰ Kein Wecker gestellt", "⏰ No alarm set"), "next_alarm" to null)
        val t = Instant.ofEpochMilli(next.triggerTime).atZone(ZoneId.systemDefault())
        val text = t.format(DateTimeFormatter.ofPattern(tr("EEEE, d. MMMM 'um' HH:mm", "EEEE, MMMM d 'at' HH:mm"), dev.davidx3d.wisp.i18n.Lang.locale))
        return ToolResult.ok(tr("⏰ Nächster Wecker: ", "⏰ Next alarm: ") + t.format(DateTimeFormatter.ofPattern("EE HH:mm", dev.davidx3d.wisp.i18n.Lang.locale)), "next_alarm" to text)
    }
}

class DismissAlarmTool : Tool {
    override val name = "dismiss_alarm"
    override val title = tr("Wecker ausschalten", "Dismiss alarm")
    override val description = tr(
        "Schaltet Wecker aus: den nächsten, nach Uhrzeit, nach Name oder alle. Android erlaubt das nur über die Uhr-App, die eventuell kurz nachfragt.",
        "Turns alarms off: the next one, by time, by label or all. Android only allows this through the clock app, which may ask briefly.",
    )
    override val category = ToolCategory.TIME
    override val parameters = schema {
        string("mode", tr("Welche Wecker", "Which alarms"), required = true, enum = listOf("next", "time", "label", "all"))
        integer("hour", tr("Stunde (bei mode=time)", "Hour (for mode=time)"))
        integer("minute", tr("Minute (bei mode=time)", "Minute (for mode=time)"))
        string("label", tr("Name (bei mode=label)", "Label (for mode=label)"))
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
            else -> return ToolResult.fail("invalid_arguments", tr("mode muss next, time, label oder all sein.", "mode must be next, time, label or all."))
        }
        if (!ctx.app.launch(intent)) return noApp(tr("Wecker ausschalten", "dismiss alarms"))
        return ToolResult.ok(tr("⏰ Wecker ausgeschaltet", "⏰ Alarm dismissed"), "note" to tr("An die Uhr-App übergeben.", "Handed to the clock app."))
    }
}
