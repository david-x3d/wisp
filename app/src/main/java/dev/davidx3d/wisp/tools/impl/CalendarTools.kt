package dev.davidx3d.wisp.tools.impl

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolArgumentException
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone
import dev.davidx3d.wisp.i18n.tr

private val zone get() = ZoneId.systemDefault()
private val timeFmt get() = DateTimeFormatter.ofPattern("HH:mm", dev.davidx3d.wisp.i18n.Lang.locale)
private val dayFmt get() = DateTimeFormatter.ofPattern(tr("EEEE, d. MMMM", "EEEE, MMMM d"), dev.davidx3d.wisp.i18n.Lang.locale)

/** Accepts "2026-11-08T14:00", "2026-11-08 14:00", "2026-11-08" (start of day). */
fun parseDateTime(s: String): LocalDateTime {
    val t = s.trim().replace(' ', 'T').removeSuffix("Z")
    runCatching { return LocalDateTime.parse(t) }
    runCatching { return LocalDateTime.parse(t + ":00") }
    runCatching { return LocalDate.parse(t).atStartOfDay() }
    runCatching { return LocalDate.now().atTime(LocalTime.parse(t)) }
    throw ToolArgumentException(tr("Datum/Uhrzeit '$s' nicht verstanden. Format: JJJJ-MM-TTTHH:MM", "Could not understand date/time '$s'. Format: YYYY-MM-DDTHH:MM"))
}

class CalendarEventsTool : Tool {
    override val name = "get_calendar_events"
    override val title = tr("Termine lesen", "Read events")
    override val description = tr("Liest Termine aus allen Kalendern für heute, morgen, diese Woche oder einen Zeitraum.", "Reads events from all calendars for today, tomorrow, this week or a date range.")
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR)
    override val parameters = schema {
        string("range", tr("Zeitraum", "Range"), enum = listOf("today", "tomorrow", "week", "custom"))
        string("from", tr("Startdatum JJJJ-MM-TT (bei custom)", "Start date YYYY-MM-DD (for custom)"))
        string("to", tr("Enddatum JJJJ-MM-TT inklusive (bei custom)", "End date YYYY-MM-DD inclusive (for custom)"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val today = LocalDate.now()
        val (from, to) = when (args.string("range") ?: "today") {
            "tomorrow" -> today.plusDays(1) to today.plusDays(1)
            "week" -> today to today.plusDays(6)
            "custom" -> parseDateTime(args.requireString("from")).toLocalDate() to
                (args.string("to")?.let { parseDateTime(it).toLocalDate() } ?: parseDateTime(args.requireString("from")).toLocalDate())
            else -> today to today
        }
        val events = queryInstances(ctx.app, from, to)
        val label = if (from == to) from.format(dayFmt) else "${from.format(dayFmt)} ${tr("bis", "to")} ${to.format(dayFmt)}"
        return ToolResult.ok(
            tr("📅 ${events.size} Termine", "📅 ${events.size} events"),
            "period" to label,
            "count" to events.size,
            "events" to events,
        )
    }

    private fun queryInstances(context: Context, from: LocalDate, to: LocalDate): List<Map<String, Any?>> {
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val out = mutableListOf<Map<String, Any?>>()
        context.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < 40) {
                val allDay = c.getInt(3) == 1
                val start = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(if (allDay) ZoneId.of("UTC") else zone)
                val stop = java.time.Instant.ofEpochMilli(c.getLong(2)).atZone(if (allDay) ZoneId.of("UTC") else zone)
                out += mapOf(
                    "title" to (c.getString(0) ?: tr("(ohne Titel)", "(untitled)")),
                    "day" to start.format(dayFmt),
                    "start" to if (allDay) tr("ganztägig", "all day") else start.format(timeFmt),
                    "end" to if (allDay) null else stop.format(timeFmt),
                    "location" to c.getString(4)?.takeIf { it.isNotBlank() },
                    "calendar" to c.getString(5),
                )
            }
        }
        return out
    }
}

internal fun primaryCalendarId(context: Context): Long? {
    val proj = arrayOf(
        CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.VISIBLE,
    )
    var best: Long? = null
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI, proj,
        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
        null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            if (c.getInt(1) == 1 && c.getInt(3) == 1) return id
            if (best == null && c.getInt(3) == 1) best = id
        }
    }
    return best
}

internal fun insertEvent(
    context: Context,
    title: String,
    start: LocalDateTime,
    end: LocalDateTime,
    allDay: Boolean,
    location: String?,
    description: String?,
    reminderMinutes: Int?,
): Long? {
    val calendarId = primaryCalendarId(context) ?: return null
    val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        if (allDay) {
            val utc = ZoneId.of("UTC")
            put(CalendarContract.Events.DTSTART, start.toLocalDate().atStartOfDay(utc).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, start.toLocalDate().plusDays(1).atStartOfDay(utc).toInstant().toEpochMilli())
            put(CalendarContract.Events.ALL_DAY, 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            put(CalendarContract.Events.DTSTART, start.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.DTEND, end.atZone(zone).toInstant().toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        put(CalendarContract.Events.HAS_ALARM, if (reminderMinutes != null) 1 else 0)
    }
    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
    val eventId = ContentUris.parseId(uri)
    if (reminderMinutes != null) {
        context.contentResolver.insert(
            CalendarContract.Reminders.CONTENT_URI,
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            },
        )
    }
    return eventId
}

class CreateEventTool : Tool {
    override val name = "create_calendar_event"
    override val title = tr("Termin erstellen", "Create event")
    override val description = tr("Trägt einen Termin in den Hauptkalender ein. Zeiten in Ortszeit.", "Adds an event to the main calendar. Times in local time.")
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    override val parameters = schema {
        string("title", tr("Titel", "Title"), required = true)
        string("start", tr("Beginn als JJJJ-MM-TTTHH:MM (oder nur Datum bei ganztägig)", "Start as YYYY-MM-DDTHH:MM (or just the date for all-day)"), required = true)
        string("end", tr("Ende als JJJJ-MM-TTTHH:MM", "End as YYYY-MM-DDTHH:MM"))
        integer("duration_minutes", tr("Dauer in Minuten, falls kein Ende (Standard 60)", "Duration in minutes if no end (default 60)"))
        boolean("all_day", tr("Ganztägiger Termin", "All-day event"))
        string("location", tr("Ort", "Location"))
        string("notes", tr("Notiz", "Note"))
        integer("reminder_minutes", tr("Erinnerung so viele Minuten vorher", "Reminder this many minutes before"))
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val title = args.requireString("title")
        val start = parseDateTime(args.requireString("start"))
        val end = args.string("end")?.let(::parseDateTime) ?: start.plusMinutes((args.int("duration_minutes") ?: 60).toLong())
        if (!end.isAfter(start) && args.bool("all_day") != true) return ToolResult.fail("invalid_arguments", tr("Das Ende liegt vor dem Beginn.", "The end is before the start."))
        insertEvent(ctx.app, title, start, end, args.bool("all_day") == true, args.string("location"), args.string("notes"), args.int("reminder_minutes"))
            ?: return ToolResult.fail("no_calendar", tr("Ich habe keinen beschreibbaren Kalender gefunden.", "I couldn't find a writable calendar."))
        val whenText = start.format(DateTimeFormatter.ofPattern(tr("EEEE, d. MMMM 'um' HH:mm", "EEEE, MMMM d 'at' HH:mm"), dev.davidx3d.wisp.i18n.Lang.locale))
        return ToolResult.ok(tr("📅 „$title“ eingetragen", "📅 “$title” added"), "title" to title, "when" to whenText)
    }
}

class CreateReminderTool : Tool {
    override val name = "create_reminder"
    override val title = tr("Erinnerung", "Reminder")
    override val description = tr("Erstellt eine Erinnerung zu einer Uhrzeit (als kurzer Kalendereintrag mit Benachrichtigung).", "Creates a reminder at a time (as a short calendar entry with a notification).")
    override val category = ToolCategory.CALENDAR
    override val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    override val parameters = schema {
        string("text", tr("Woran erinnert werden soll", "What to be reminded of"), required = true)
        string("at", tr("Zeitpunkt JJJJ-MM-TTTHH:MM", "Time YYYY-MM-DDTHH:MM"), required = true)
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val text = args.requireString("text")
        val at = parseDateTime(args.requireString("at"))
        if (at.isBefore(LocalDateTime.now().minusMinutes(1))) return ToolResult.fail("invalid_arguments", tr("Der Zeitpunkt liegt in der Vergangenheit.", "That time is in the past."))
        insertEvent(ctx.app, tr("Erinnerung: $text", "Reminder: $text"), at, at.plusMinutes(5), false, null, null, 0)
            ?: return ToolResult.fail("no_calendar", tr("Ich habe keinen beschreibbaren Kalender gefunden.", "I couldn't find a writable calendar."))
        return ToolResult.ok(tr("🔔 Erinnerung ${at.format(timeFmt)}", "🔔 Reminder ${at.format(timeFmt)}"), "text" to text, "at" to at.format(DateTimeFormatter.ofPattern(tr("EEEE 'um' HH:mm", "EEEE 'at' HH:mm"), dev.davidx3d.wisp.i18n.Lang.locale)))
    }
}
