package dev.davidx3d.wisp.tools.impl

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import dev.davidx3d.wisp.services.NotificationStore
import dev.davidx3d.wisp.services.StoredNotification
import dev.davidx3d.wisp.tools.Fuzzy
import dev.davidx3d.wisp.tools.SpecialAccess
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Phone(val number: String, val type: String, val primary: Boolean)
data class Contact(val id: Long, val name: String, val phones: List<Phone>)

object Contacts {
    fun all(context: Context): List<Contact> {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        val map = LinkedHashMap<Long, Contact>()
        context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: continue
                val number = c.getString(2)?.replace(" ", "") ?: continue
                val type = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                val phone = Phone(number, type, c.getInt(5) == 1)
                val old = map[id]
                map[id] = if (old == null) Contact(id, name, listOf(phone))
                else if (old.phones.any { it.number == number }) old
                else old.copy(phones = old.phones + phone)
            }
        }
        return map.values.toList()
    }

    fun nameForNumber(context: Context, number: String): String? {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }
}

/** Outcome of turning "Max" / "Mama mobil" / "+49 170..." into exactly one number. */
sealed interface Recipient {
    data class Resolved(val name: String, val number: String, val type: String?) : Recipient
    data class Ambiguous(val options: List<String>) : Recipient
    data object NotFound : Recipient
}

fun resolveRecipient(context: Context, who: String, numberType: String?): Recipient {
    val digits = who.filter { it.isDigit() || it == '+' }
    if (digits.length >= 5 && digits.length >= who.count { !it.isWhitespace() } - 2) {
        return Recipient.Resolved(Contacts.nameForNumber(context, digits) ?: digits, digits, null)
    }
    val matches = Fuzzy.rank(who, Contacts.all(context)) { it.name }
    if (matches.isEmpty()) return Recipient.NotFound
    if (matches.size > 1) return Recipient.Ambiguous(matches.take(5).map { it.item.name })
    val contact = matches.first().item
    val phones = contact.phones
    val chosen = when {
        numberType != null -> phones.firstOrNull { Fuzzy.score(numberType, it.type) > 0.8 }
        phones.size == 1 -> phones.first()
        else -> phones.firstOrNull { it.primary }
            ?: phones.filter { it.type.contains("mobil", true) || it.type.contains("mobile", true) }.singleOrNull()
    } ?: return Recipient.Ambiguous(phones.map { "${contact.name} (${it.type})" })
    return Recipient.Resolved(contact.name, chosen.number, chosen.type)
}

private fun recipientFailure(r: Recipient, who: String): ToolResult = when (r) {
    is Recipient.Ambiguous -> ToolResult.fail(
        "ambiguous",
        "Es gibt mehrere Treffer für „$who“: ${r.options.joinToString()}. Frag nach, wen genau.",
        "Mehrere Treffer für $who",
    )
    Recipient.NotFound -> ToolResult.fail("not_found", "Ich finde keinen Kontakt „$who“.", "Kontakt nicht gefunden")
    is Recipient.Resolved -> error("resolved")
}

class FindContactTool : Tool {
    override val name = "find_contact"
    override val title = "Kontakte suchen"
    override val description = "Sucht Kontakte per Name (unscharf) und nennt ihre Telefonnummern."
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_CONTACTS)
    override val parameters = schema { string("name", "Name oder Teil davon", required = true) }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val q = args.requireString("name")
        val matches = Fuzzy.rank(q, Contacts.all(ctx.app), threshold = 0.75) { it.name }.take(5)
        if (matches.isEmpty()) return ToolResult.fail("not_found", "Kein Kontakt „$q“ gefunden.", "Kein Kontakt „$q“")
        return ToolResult.ok(
            "👤 ${matches.first().item.name}" + if (matches.size > 1) " +${matches.size - 1}" else "",
            "contacts" to matches.map { m -> mapOf("name" to m.item.name, "numbers" to m.item.phones.map { "${it.type}: ${it.number}" }) },
        )
    }
}

class CallTool : Tool {
    override val name = "call_contact"
    override val title = "Anrufen"
    override val description = "Ruft einen Kontakt (Name) oder eine Nummer an. Bei mehreren Treffern nachfragen."
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
    override val confirmable = true
    override val parameters = schema {
        string("who", "Name des Kontakts oder Telefonnummer", required = true)
        string("number_type", "Optional: Mobil, Privat, Arbeit ...")
    }

    override fun confirmation(args: ToolArgs, ctx: ToolContext): String? {
        val r = resolveRecipient(ctx.app, args.requireString("who"), args.string("number_type"))
        return (r as? Recipient.Resolved)?.let { "${it.name} anrufen" + (it.type?.let { t -> " ($t)" } ?: "") }
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val who = args.requireString("who")
        val r = resolveRecipient(ctx.app, who, args.string("number_type"))
        if (r !is Recipient.Resolved) return recipientFailure(r, who)
        ctx.app.launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(r.number)}")))
        return ToolResult.ok("📞 Rufe ${r.name} an", "name" to r.name, "number" to r.number)
    }
}

class SendSmsTool : Tool {
    override val name = "send_sms"
    override val title = "SMS senden"
    override val description = "Sendet eine SMS an einen Kontakt oder eine Nummer."
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
    override val confirmable = true
    override val parameters = schema {
        string("to", "Name des Kontakts oder Nummer", required = true)
        string("text", "Nachrichtentext", required = true)
        string("number_type", "Optional: Mobil, Privat, Arbeit ...")
    }

    override fun confirmation(args: ToolArgs, ctx: ToolContext): String? {
        val r = resolveRecipient(ctx.app, args.requireString("to"), args.string("number_type")) as? Recipient.Resolved ?: return null
        return "SMS an ${r.name}: „${args.requireString("text")}“"
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val to = args.requireString("to")
        val text = args.requireString("text")
        val r = resolveRecipient(ctx.app, to, args.string("number_type"))
        if (r !is Recipient.Resolved) return recipientFailure(r, to)
        val sms = ctx.app.getSystemService(SmsManager::class.java)
        val parts = sms.divideMessage(text)
        sms.sendMultipartTextMessage(r.number, null, parts, null, null)
        return ToolResult.ok("✉️ SMS an ${r.name} gesendet", "to" to r.name)
    }
}

private val msgTime = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)
private fun spokenTime(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "heute ${t.format(msgTime)}"
        today.minusDays(1) -> "gestern ${t.format(msgTime)}"
        else -> t.format(DateTimeFormatter.ofPattern("d. MMMM HH:mm", Locale.GERMAN))
    }
}

class ReadSmsTool : Tool {
    override val name = "read_sms"
    override val title = "SMS lesen"
    override val description = "Liest die letzten empfangenen SMS vor, optional nur von einem Kontakt oder nur ungelesene."
    override val category = ToolCategory.COMMUNICATION
    override val permissions = listOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
    override val parameters = schema {
        integer("count", "Wie viele (Standard 5, max 20)")
        string("from", "Nur von diesem Kontakt")
        boolean("unread_only", "Nur ungelesene")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val count = (args.int("count") ?: 5).coerceIn(1, 20)
        val from = args.string("from")
        val selection = if (args.bool("unread_only") == true) "${Telephony.Sms.READ} = 0" else null
        val out = mutableListOf<Map<String, Any?>>()
        ctx.app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var scanned = 0
            while (c.moveToNext() && out.size < count && scanned++ < 300) {
                val address = c.getString(0) ?: continue
                val name = Contacts.nameForNumber(ctx.app, address) ?: address
                if (from != null && Fuzzy.score(from, name) < 0.78) continue
                out += mapOf("from" to name, "text" to c.getString(1), "time" to spokenTime(c.getLong(2)))
            }
        }
        return ToolResult.ok("✉️ ${out.size} SMS gelesen", "messages" to out)
    }
}

class ReadNotificationsTool : Tool {
    override val name = "read_notifications"
    override val title = "Benachrichtigungen lesen"
    override val description = "Liest aktuelle Benachrichtigungen vor, standardmäßig nur Nachrichten (WhatsApp, Telegram, Signal …)."
    override val category = ToolCategory.COMMUNICATION
    override val specialAccess = listOf(SpecialAccess.NOTIFICATION_LISTENER)
    override val parameters = schema {
        string("app", "Nur von dieser App, z. B. WhatsApp")
        boolean("all", "Auch Nicht-Nachrichten (Standard: false)")
        integer("count", "Wie viele (Standard 8)")
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val app = args.string("app")
        val list = NotificationStore.items.value
            .filter { args.bool("all") == true || it.isMessage }
            .filter { app == null || Fuzzy.score(app, it.appName) >= 0.8 }
            .take((args.int("count") ?: 8).coerceIn(1, 25))
        return ToolResult.ok(
            "🔔 ${list.size} Benachrichtigungen",
            "notifications" to list.map {
                mapOf(
                    "app" to it.appName, "from" to it.sender, "chat" to it.conversation,
                    "text" to it.text, "time" to spokenTime(it.time), "can_reply" to it.canReply,
                )
            },
        )
    }
}

class ReplyToMessageTool : Tool {
    override val name = "reply_to_message"
    override val title = "Auf Nachricht antworten"
    override val description = "Antwortet über die Antwort-Funktion einer Benachrichtigung, z. B. auf WhatsApp. " +
        "Geht nur, solange die Benachrichtigung der Person noch da ist."
    override val category = ToolCategory.COMMUNICATION
    override val specialAccess = listOf(SpecialAccess.NOTIFICATION_LISTENER)
    override val confirmable = true
    override val parameters = schema {
        string("to", "Absender oder Chatname", required = true)
        string("text", "Antworttext", required = true)
        string("app", "Optional: App, z. B. WhatsApp")
    }

    private fun find(args: ToolArgs): List<StoredNotification> {
        val to = args.requireString("to")
        val app = args.string("app")
        val candidates = NotificationStore.items.value
            .filter { it.canReply && (app == null || Fuzzy.score(app, it.appName) >= 0.8) }
        return Fuzzy.rank(to, candidates) { it.conversation ?: it.sender }.map { it.item }
            .ifEmpty { Fuzzy.rank(to, candidates) { it.sender }.map { it.item } }
            .distinctBy { it.packageName to (it.conversation ?: it.sender) }
    }

    override fun confirmation(args: ToolArgs, ctx: ToolContext): String? {
        val target = find(args).singleOrNull() ?: return null
        return "Antwort an ${target.conversation ?: target.sender} (${target.appName}): „${args.requireString("text")}“"
    }

    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
        val found = find(args)
        val target = when (found.size) {
            0 -> return ToolResult.fail(
                "not_found",
                "Ich finde keine Benachrichtigung von „${args.requireString("to")}“, auf die ich antworten kann. " +
                    "Antworten geht nur, solange die Nachricht als Benachrichtigung da ist.",
                "Keine passende Nachricht",
            )
            1 -> found.first()
            else -> return ToolResult.fail("ambiguous", "Mehrere Chats passen: ${found.joinToString { "${it.conversation ?: it.sender} (${it.appName})" }}.")
        }
        if (!NotificationStore.reply(ctx.app, target, args.requireString("text"))) {
            return ToolResult.fail("failed", "Die App hat die Antwort nicht angenommen.")
        }
        return ToolResult.ok("💬 Antwort an ${target.conversation ?: target.sender}", "app" to target.appName)
    }
}
