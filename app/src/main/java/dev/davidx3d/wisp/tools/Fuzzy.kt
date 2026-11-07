package dev.davidx3d.wisp.tools

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Forgiving name matching for spoken input: "Maxi" finds "Max Müller", "mama" finds
 * "Mama ❤️", "spotifai" still finds "Spotify".
 */
object Fuzzy {

    fun normalize(s: String): String {
        val folded = s.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        return Normalizer.normalize(folded, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** 0..1 how well [query] matches [candidate]. */
    fun score(query: String, candidate: String): Double {
        val q = normalize(query)
        val c = normalize(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        if (q == c) return 1.0
        val cTokens = c.split(' ')
        val qTokens = q.split(' ')
        if (cTokens.any { it == q }) return 0.95
        if (c.startsWith(q)) return 0.92
        if (cTokens.any { it.startsWith(q) } && q.length >= 3) return 0.88
        if (qTokens.all { qt -> cTokens.any { it.startsWith(qt) } }) return 0.86
        if (c.contains(q) && q.length >= 3) return 0.8
        // Token-wise Jaro-Winkler: each query token against its best candidate token.
        val tokenScore = qTokens.map { qt -> cTokens.maxOf { jaroWinkler(qt, it) } }.average()
        return max(tokenScore * 0.9, jaroWinkler(q, c) * 0.85)
    }

    data class Match<T>(val item: T, val score: Double)

    /**
     * Best matches above [threshold], best first. If one match clearly wins
     * (exact or far ahead of the rest) only that one is returned.
     */
    fun <T> rank(query: String, items: List<T>, threshold: Double = 0.78, name: (T) -> String): List<Match<T>> {
        val ranked = items.map { Match(it, score(query, name(it))) }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
        if (ranked.size >= 2) {
            val (a, b) = ranked[0] to ranked[1]
            if (a.score >= 0.99 && b.score < 0.99) return listOf(a)
            if (a.score - b.score >= 0.12) return listOf(a)
        }
        return ranked
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val range = max(0, max(a.length, b.length) / 2 - 1)
        val aM = BooleanArray(a.length)
        val bM = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = max(0, i - range)
            val hi = min(b.length - 1, i + range)
            for (j in lo..hi) {
                if (!bM[j] && a[i] == b[j]) {
                    aM[i] = true; bM[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!aM[i]) continue
            while (!bM[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < min(4, min(a.length, b.length)) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}
