package dev.davidx3d.wisp

import dev.davidx3d.wisp.session.NutritionReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class NutritionReceiptTest {
    private fun receipt(raw: String) = NutritionReceipt.from(Json.parseToJsonElement(raw).jsonObject)
    @Test fun usesSavedNumbersIncludingExceededBudget() {
        val value = receipt("""{"status":"saved","food":"Rice","added_kcal":250,"total_kcal":2100,"target_kcal":2000,"remaining_kcal":-100}""")!!
        assertEquals(250.0, value.addedKcal, 0.0)
        assertEquals(-100.0, value.remainingKcal!!, 0.0)
    }
    @Test fun missingTargetStaysAbsent() {
        val value = receipt("""{"status":"saved","added_kcal":250,"total_kcal":500,"target_kcal":null,"remaining_kcal":null}""")!!
        assertNull(value.targetKcal)
        assertNull(value.remainingKcal)
    }
    @Test fun failuresDraftsAndIncompleteResultsNeverBecomeSuccessCards() {
        assertNull(receipt("""{"status":"draft_opened","added_kcal":250,"total_kcal":500}"""))
        assertNull(receipt("""{"ok":false}"""))
        assertNull(receipt("""{"status":"saved","total_kcal":500}"""))
    }
}
