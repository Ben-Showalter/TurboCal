package com.turbocal.core.feeds

/**
 * The fixed calendar palette (AGENTS.md §7): eight colours that stay readable
 * on the black background. Feeds pick from these; no free-form hex entry.
 */
object FeedColors {
    val PALETTE: List<Int> = listOf(
        0xFF4DB6AC.toInt(), // teal (the app accent)
        0xFF64B5F6.toInt(), // blue
        0xFFFFD54F.toInt(), // amber
        0xFFE57373.toInt(), // red
        0xFF81C784.toInt(), // green
        0xFFBA68C8.toInt(), // purple
        0xFFFF8A65.toInt(), // orange
        0xFFF06292.toInt(), // pink
    )

    val NAMES: List<String> = listOf("Teal", "Blue", "Amber", "Red", "Green", "Purple", "Orange", "Pink")

    fun forIndex(i: Int): Int = PALETTE[Math.floorMod(i, PALETTE.size)]

    fun nameOf(color: Int): String = PALETTE.indexOf(color).let { if (it >= 0) NAMES[it] else "Custom" }

    /** The next (or previous, for [step] = -1) palette colour after [color]. */
    fun step(color: Int, step: Int): Int {
        val i = PALETTE.indexOf(color)
        return if (i < 0) PALETTE[0] else forIndex(i + step)
    }

    /** `#RRGGBB` or `#AARRGGBB` → ARGB; null if malformed. */
    fun parse(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        val v = h.toLongOrNull(16) ?: return null
        return when (h.length) {
            6 -> (0xFF000000L or v).toInt()
            8 -> v.toInt()
            else -> null
        }
    }
}
