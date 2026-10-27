package com.turbocal.core.update

/**
 * Compares `1.2.10` > `1.2.9` numerically, ignores a leading `v`, and sorts
 * a pre-release (`1.3.0-beta1`) below its release (`1.3.0`). Never compare
 * version strings directly (AGENTS.md §9).
 */
object VersionCompare {

    fun compare(a: String, b: String): Int {
        val (an, ap) = split(a)
        val (bn, bp) = split(b)
        for (i in 0 until maxOf(an.size, bn.size)) {
            val c = an.getOrElse(i) { 0 }.compareTo(bn.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            ap == bp -> 0
            ap == null -> 1
            bp == null -> -1
            else -> ap.compareTo(bp)
        }
    }

    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = v.trim().removePrefix("v").removePrefix("V")
        val dash = s.indexOfFirst { it == '-' || it == '+' }
        val core = if (dash >= 0) s.substring(0, dash) else s
        val pre = if (dash >= 0 && s[dash] == '-') s.substring(dash + 1) else null
        return core.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 } to pre
    }
}
