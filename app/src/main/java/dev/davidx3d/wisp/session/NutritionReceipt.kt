package dev.davidx3d.wisp.session

import kotlinx.serialization.json.*

data class NutritionReceipt(val food: String, val addedKcal: Double, val totalKcal: Double, val targetKcal: Double?, val remainingKcal: Double?) {
    companion object {
        fun from(data: JsonObject): NutritionReceipt? {
            fun number(key: String) = (data[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
            if ((data["status"] as? JsonPrimitive)?.contentOrNull != "saved") return null
            return NutritionReceipt((data["food"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                number("added_kcal") ?: return null, number("total_kcal") ?: return null,
                number("target_kcal"), number("remaining_kcal"))
        }
    }
}
