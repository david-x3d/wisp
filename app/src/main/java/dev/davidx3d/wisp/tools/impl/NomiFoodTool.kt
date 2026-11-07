package dev.davidx3d.wisp.tools.impl

import android.net.Uri
import android.os.Bundle
import dev.davidx3d.wisp.i18n.tr
import dev.davidx3d.wisp.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bound-provider IPC keeps Nomi alive during research without opening its UI. */
class NomiFoodTool : Tool {
    override val name = "add_nomi_food"
    override val timeoutMillis = 180_000L
    override val title = tr("In Nomi eintragen", "Log in Nomi")
    override val description = tr(
        "Trägt gegessene Lebensmittel direkt im Hintergrund in Nomi ein. Mengen unverändert übernehmen. Nach Erfolg die zurückgegebenen added_kcal und remaining_kcal ansagen, niemals selbst berechnen oder erfinden. Bei negativem Rest das überschrittene Budget nennen. Bei fehlendem Ziel nur die eingetragenen kcal nennen. Bei unknown_result nicht erneut eintragen, sondern auf die Prüfung im Tagebuch hinweisen.",
        "Logs eaten food directly in Nomi in the background. Preserve stated amounts. After success say the returned added_kcal and remaining_kcal, never invent or calculate them. A negative remainder means the budget was exceeded. If no target exists, say only the added calories. On unknown_result do not retry; ask the user to check the diary.",
    )
    override val category = ToolCategory.DEVICE
    override val parameters = schema {
        string("food", "Food or meal in the user's language, including stated amounts and units. Do not invent quantities.", required = true)
    }
    override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val food = args.requireString("food")
        if (food.length > 2000) return@withContext ToolResult.fail("invalid_arguments", tr("Bitte höchstens 2000 Zeichen an Nomi senden.", "Send at most 2000 characters to Nomi."))
        val result = try {
            ctx.app.contentResolver.call(Uri.parse("content://com.nomi.app.assistant"), "log_food", null,
                Bundle().apply { putString("food", food); putString("request_id", ctx.requestId) })
        } catch (_: IllegalArgumentException) {
            return@withContext ToolResult.fail("update_required", tr("Installiere die neue Nomi-Version für Hintergrund-Einträge.", "Install the new Nomi version for background logging."))
        } catch (_: android.os.DeadObjectException) {
            return@withContext ToolResult.fail("unknown_result", tr("Die Verbindung zu Nomi wurde getrennt. Prüfe das Tagebuch vor einem erneuten Eintrag.", "Nomi disconnected. Check the diary before logging again."))
        }
        if (result == null) return@withContext ToolResult.fail("unknown_result", tr("Keine Bestätigung von Nomi. Prüfe das Tagebuch vor einem erneuten Eintrag.", "No confirmation from Nomi. Check the diary before logging again."))
        if (!result.getBoolean("ok")) return@withContext ToolResult.fail(result.getString("error") ?: "failed", result.getString("message") ?: "Nomi could not log that.")
        ToolResult.ok(tr("In Nomi eingetragen", "Logged in Nomi"),
            "status" to "saved", "food" to (result.getString("food") ?: food),
            "added_kcal" to result.getDouble("added_kcal"), "total_kcal" to result.getDouble("total_kcal"),
            "target_kcal" to if (result.containsKey("target_kcal")) result.getDouble("target_kcal") else null,
            "remaining_kcal" to if (result.containsKey("remaining_kcal")) result.getDouble("remaining_kcal") else null,
            "date" to result.getString("date"))
    }
}
