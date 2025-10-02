package com.example.fishfreshness

object ShelfLifePredictor {

    // Mapping freshness categories to minutes
    private val shelfLifeMap = mapOf(
        "very_fresh" to 120,   // 2 hrs
        "fresh" to 60,         // 1 hr
        "less_fresh" to 45,    // 45 mins
        "spoiled" to 0         // don't eat
    )

    private val expectedParts = listOf("caudal_fin", "eye", "pectoral_fin", "skin_texture")

    /**
     * Predict overall shelf-life based on all detected labels from YOLOv8
     * Missing parts default to 'spoiled'
     */
    fun predictShelfLife(detectedLabels: List<String>): String {
        val cleanLabels = detectedLabels.map { it.split(" ")[0] } // remove confidence %
        val partMap = mutableMapOf<String, Int>()

        for (part in expectedParts) {
            val lbl = cleanLabels.find { it.endsWith("_$part") }
            val category = lbl?.split("_")?.firstOrNull() ?: "spoiled"
            partMap[part] = shelfLifeMap[category] ?: 0
        }

        val overallMins = partMap.values.minOrNull() ?: 0
        return formatShelfLife(overallMins)
    }

    private fun formatShelfLife(minutes: Int): String {
        return when {
            minutes == 0 -> "Don't eat"
            minutes < 60 -> "$minutes mins"
            else -> "${minutes / 60} hr${if (minutes / 60 > 1) "s" else ""}"
        }
    }
}
