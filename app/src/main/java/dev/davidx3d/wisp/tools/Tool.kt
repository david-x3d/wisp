package dev.davidx3d.wisp.tools

import android.content.Context
import dev.davidx3d.wisp.data.settings.AppSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import dev.davidx3d.wisp.i18n.tr

/** Groups tools in the settings screen. */
enum class ToolCategory(val title: String) {
    TIME(tr("Zeit & Wecker", "Time & alarms")),
    CALENDAR(tr("Kalender", "Calendar")),
    COMMUNICATION(tr("Anrufe & Nachrichten", "Calls & messages")),
    DEVICE(tr("Gerät", "Device")),
    MEDIA(tr("Medien", "Media")),
    INFO(tr("Infos & Ort", "Info & location")),
    SCREEN(tr("Bildschirm", "Screen")),
    SYSTEM(tr("Systemsteuerung (Shizuku/Root)", "System control (Shizuku/root)")),
}

/** Access that is not a normal runtime permission and needs a trip to system settings. */
enum class SpecialAccess(val title: String) {
    NOTIFICATION_LISTENER(tr("Benachrichtigungszugriff", "Notification access")),
    ACCESSIBILITY(tr("Bedienungshilfe", "Accessibility service")),
    WRITE_SETTINGS(tr("Systemeinstellungen ändern", "Modify system settings")),
    DND_POLICY(tr("Zugriff auf „Bitte nicht stören“", "Do Not Disturb access")),
    PRIVILEGED(tr("Shizuku oder Root", "Shizuku or root")),
}

/** Result of a tool run. Serialised as JSON and sent back as `function_call_output`. */
sealed interface ToolResult {
    /** Short text for the chip in the transcript, e.g. "Timer 10 min gestellt". */
    val chip: String

    data class Success(val data: JsonObject, override val chip: String) : ToolResult
    data class Failure(val code: String, val message: String, override val chip: String = message) : ToolResult

    fun toOutput(): String = when (this) {
        is Success -> buildJsonObject {
            put("ok", true)
            data.forEach { (k, v) -> put(k, v) }
        }.toString()
        is Failure -> buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
        }.toString()
    }

    companion object {
        fun ok(chip: String, vararg pairs: Pair<String, Any?>) = Success(jsonOf(*pairs), chip)
        fun fail(code: String, message: String, chip: String = message) = Failure(code, message, chip)
        fun missingPermission(what: String) = Failure(
            "permission_missing",
            tr("Mir fehlt die Berechtigung „$what“. Erteile sie in Wisp unter Einstellungen → Werkzeuge.", "I'm missing the \"$what\" permission. Grant it in Wisp under Settings → Tools."),
            tr("Berechtigung fehlt: $what", "Permission missing: $what"),
        )
    }
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, toJson(v)) }
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> kotlinx.serialization.json.JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k.toString(), toJson(x)) } }
    is Iterable<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}

class ToolArgumentException(message: String) : Exception(message)

/** Typed, forgiving access to the model's JSON arguments ("10" works for an int, too). */
class ToolArgs(val raw: JsonObject) {
    private fun prim(name: String) = raw[name] as? JsonPrimitive

    fun string(name: String): String? = prim(name)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    fun int(name: String): Int? = prim(name)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.contentOrNull?.trim()?.toIntOrNull() }
    fun double(name: String): Double? = prim(name)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }
    fun bool(name: String): Boolean? = prim(name)?.let {
        it.booleanOrNull ?: when (it.contentOrNull?.lowercase()) {
            "ja", "an", "on", "yes", "1", "true" -> true
            "nein", "aus", "off", "no", "0", "false" -> false
            else -> null
        }
    }

    fun stringList(name: String): List<String> = when (val e = raw[name]) {
        is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> e.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        else -> emptyList()
    }

    fun requireString(name: String) = string(name) ?: throw ToolArgumentException(tr("Parameter '$name' fehlt.", "Parameter '$name' is missing."))
    fun requireInt(name: String) = int(name) ?: throw ToolArgumentException(tr("Parameter '$name' fehlt oder ist keine Zahl.", "Parameter '$name' is missing or not a number."))
    fun requireBool(name: String) = bool(name) ?: throw ToolArgumentException(tr("Parameter '$name' muss true oder false sein.", "Parameter '$name' must be true or false."))

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        fun parse(arguments: String): ToolArgs {
            val el = runCatching { json.parseToJsonElement(arguments.ifBlank { "{}" }) }
                .getOrElse { throw ToolArgumentException(tr("Die Argumente sind kein gültiges JSON.", "The arguments are not valid JSON.")) }
            return ToolArgs(el as? JsonObject ?: throw ToolArgumentException(tr("Die Argumente müssen ein JSON-Objekt sein.", "The arguments must be a JSON object.")))
        }
    }
}

class ToolContext(val app: Context, val settings: AppSettings)

/**
 * A capability the assistant can use. Implementations are stateless and registered in
 * [ToolsModule]; the [ToolRegistry] turns them into session tool definitions.
 */
interface Tool {
    /** Function name the model calls (snake_case). */
    val name: String
    /** German label for settings and chips. */
    val title: String
    /** What it does, for the model. */
    val description: String
    val category: ToolCategory
    /** JSON schema of the arguments. */
    val parameters: JsonObject get() = schema()
    /** Android runtime permissions (all must be granted). */
    val permissions: List<String> get() = emptyList()
    val specialAccess: List<SpecialAccess> get() = emptyList()
    /** Access that makes the tool work better but is not strictly required (shown in settings). */
    val optionalAccess: List<SpecialAccess> get() = emptyList()

    /** True if [confirmation] can ask for a yes/no (adds `confirm_action` to the session). */
    val confirmable: Boolean get() = false

    /** Non-null if this call must be confirmed first; the text describes what will happen. */
    fun confirmation(args: ToolArgs, ctx: ToolContext): String? = null

    suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult
}

// ---- Tiny DSL for JSON schemas ----

class SchemaBuilder {
    internal val props = LinkedHashMap<String, JsonObject>()
    internal val required = mutableListOf<String>()

    fun string(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
        })

    fun integer(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "integer"); put("description", description) })

    fun number(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "number"); put("description", description) })

    fun boolean(name: String, description: String, required: Boolean = false) =
        add(name, required, buildJsonObject { put("type", "boolean"); put("description", description) })

    fun stringArray(name: String, description: String, required: Boolean = false, enum: List<String>? = null) =
        add(name, required, buildJsonObject {
            put("type", "array")
            put("description", description)
            put("items", buildJsonObject {
                put("type", "string")
                if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
            })
        })

    private fun add(name: String, isRequired: Boolean, o: JsonObject) {
        props[name] = o
        if (isRequired) required += name
    }
}

fun schema(block: SchemaBuilder.() -> Unit = {}): JsonObject {
    val b = SchemaBuilder().apply(block)
    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(b.props))
        put("required", JsonArray(b.required.map { JsonPrimitive(it) }))
    }
}
