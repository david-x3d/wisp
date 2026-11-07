package dev.davidx3d.wisp.tools

import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.realtime.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** An action waiting for a yes/no from the user. */
data class PendingAction(
    val id: String,
    val tool: Tool,
    val args: ToolArgs,
    val summary: String,
)

/**
 * Holds all tools and turns the enabled ones into session definitions. Also implements
 * the confirmation protocol: tools that need confirmation first return
 * `needs_confirmation` with an id; the model asks the user and calls `confirm_action`.
 * The UI can resolve the same pending action with a button instead.
 */
class ToolRegistry(
    tools: Collection<Tool>,
    private val access: (Tool) -> List<String> = { emptyList() },
) {
    val all: List<Tool> = tools.sortedWith(compareBy({ it.category.ordinal }, { it.title }))
    private val byName = all.associateBy { it.name }
    private val pending = LinkedHashMap<String, PendingAction>()

    fun get(name: String): Tool? = byName[name]

    fun isEnabled(tool: Tool, settings: AppSettings): Boolean =
        tool.name !in settings.disabledTools && (tool.category != ToolCategory.SYSTEM || settings.systemControlEnabled)

    fun enabled(settings: AppSettings): List<Tool> = all.filter { isEnabled(it, settings) }

    /** Definitions for `session.update`; includes `confirm_action` when it is needed. */
    fun specs(settings: AppSettings): List<ToolSpec> {
        val tools = enabled(settings)
        val list = tools.map { ToolSpec(it.name, it.description, it.parameters) }.toMutableList()
        if (settings.confirmActions && tools.any { it.confirmable }) {
            list += ToolSpec(CONFIRM_TOOL, CONFIRM_DESCRIPTION, confirmSchema)
        }
        return list
    }

    val pendingActions: List<PendingAction> get() = synchronized(pending) { pending.values.toList() }

    /**
     * Runs one function call. Never throws: every failure becomes a [ToolResult.Failure]
     * that tells the model what went wrong in plain words.
     */
    suspend fun run(name: String, arguments: String, ctx: ToolContext, onPending: (PendingAction) -> Unit = {}): ToolResult {
        val args = try {
            ToolArgs.parse(arguments)
        } catch (e: ToolArgumentException) {
            return ToolResult.fail("invalid_arguments", e.message ?: "Ungültige Argumente")
        }
        if (name == CONFIRM_TOOL) return confirm(args, ctx)

        val tool = byName[name] ?: return ToolResult.fail("unknown_tool", "Das Werkzeug '$name' gibt es nicht.")
        if (!isEnabled(tool, ctx.settings)) {
            return ToolResult.fail("tool_disabled", "Das Werkzeug „${tool.title}“ ist in den Einstellungen ausgeschaltet.", "${tool.title} ist aus")
        }
        access(tool).firstOrNull()?.let { return ToolResult.missingPermission(it) }

        if (ctx.settings.confirmActions) {
            val summary = runCatching { tool.confirmation(args, ctx) }.getOrNull()
            if (summary != null) {
                val action = PendingAction(UUID.randomUUID().toString().take(8), tool, args, summary)
                synchronized(pending) { pending[action.id] = action }
                onPending(action)
                return ToolResult.ok(
                    "Wartet auf Bestätigung",
                    "status" to "needs_confirmation",
                    "confirmation_id" to action.id,
                    "summary" to summary,
                    "instruction" to "Frag den Nutzer kurz, ob du das wirklich tun sollst: \"$summary\". " +
                        "Antwortet er mit ja, rufe confirm_action mit confirmation_id und approved=true auf, sonst approved=false.",
                )
            }
        }
        return execute(tool, args, ctx)
    }

    /** Resolves a pending action (from the model or from a button). */
    suspend fun resolve(id: String, approved: Boolean, ctx: ToolContext): ToolResult {
        val action = synchronized(pending) { pending.remove(id) }
            ?: return ToolResult.fail("unknown_confirmation", "Zu dieser Bestätigung gibt es keine offene Aktion mehr.")
        if (!approved) return ToolResult.ok("Abgebrochen", "status" to "cancelled", "summary" to action.summary)
        return execute(action.tool, action.args, ctx)
    }

    fun clearPending() = synchronized(pending) { pending.clear() }

    private suspend fun confirm(args: ToolArgs, ctx: ToolContext): ToolResult {
        val id = args.string("confirmation_id")
            ?: pendingActions.lastOrNull()?.id
            ?: return ToolResult.fail("unknown_confirmation", "Es gibt keine Aktion, die bestätigt werden muss.")
        val approved = args.bool("approved") ?: return ToolResult.fail("invalid_arguments", "approved muss true oder false sein.")
        return resolve(id, approved, ctx)
    }

    private suspend fun execute(tool: Tool, args: ToolArgs, ctx: ToolContext): ToolResult = try {
        withTimeout(TOOL_TIMEOUT_MS) { tool.execute(args, ctx) }
    } catch (e: ToolArgumentException) {
        ToolResult.fail("invalid_arguments", e.message ?: "Ungültige Argumente")
    } catch (e: SecurityException) {
        ToolResult.fail("permission_missing", "Android hat den Zugriff verweigert: ${e.message ?: "fehlende Berechtigung"}.", "Zugriff verweigert")
    } catch (e: TimeoutCancellationException) {
        ToolResult.fail("timeout", "Das hat zu lange gedauert.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.fail("failed", "Das hat nicht geklappt: ${e.message ?: e.javaClass.simpleName}.", "${tool.title} fehlgeschlagen")
    }

    companion object {
        const val CONFIRM_TOOL = "confirm_action"
        private const val TOOL_TIMEOUT_MS = 20_000L
        private const val CONFIRM_DESCRIPTION =
            "Bestätigt oder verwirft eine Aktion, die vorher needs_confirmation gemeldet hat " +
                "(z. B. Anruf oder Nachricht). Nur aufrufen, nachdem der Nutzer klar ja oder nein gesagt hat."
        private val confirmSchema = schema {
            string("confirmation_id", "Die confirmation_id aus der needs_confirmation-Antwort", required = true)
            boolean("approved", "true, wenn der Nutzer zugestimmt hat", required = true)
        }
    }
}
