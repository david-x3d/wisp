package dev.davidx3d.wisp

import dev.davidx3d.wisp.realtime.FailureKind
import dev.davidx3d.wisp.tools.Fuzzy
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolArgumentException
import dev.davidx3d.wisp.tools.impl.WeatherTool
import dev.davidx3d.wisp.tools.impl.formatDuration
import dev.davidx3d.wisp.tools.impl.parseDateTime
import dev.davidx3d.wisp.tools.impl.parseDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Calendar

class ArgsAndFuzzyTest {

    @Test
    fun argsAreForgiving() {
        val a = ToolArgs.parse("""{"min":"10","sec":4.0,"on":"ja","off":"aus","days":"mo, fr","list":["a","b"],"empty":"  "}""")
        assertEquals(10, a.int("min"))
        assertEquals(4, a.int("sec"))
        assertEquals(true, a.bool("on"))
        assertEquals(false, a.bool("off"))
        assertEquals(listOf("mo", "fr"), a.stringList("days"))
        assertEquals(listOf("a", "b"), a.stringList("list"))
        assertNull(a.string("empty"))
        assertNull(a.int("missing"))
    }

    @Test(expected = ToolArgumentException::class)
    fun requireThrowsReadableError() {
        ToolArgs.parse("{}").requireString("name")
    }

    @Test(expected = ToolArgumentException::class)
    fun nonObjectJsonIsRejected() {
        ToolArgs.parse("[1,2]")
    }

    @Test
    fun emptyArgumentsAreAnEmptyObject() {
        assertTrue(ToolArgs.parse("").raw.isEmpty())
    }

    @Test
    fun daysAndDates() {
        assertEquals(listOf(Calendar.MONDAY, Calendar.FRIDAY), parseDays(listOf("Montag", "fr")))
        assertEquals(5, parseDays(listOf("werktags")).size)
        assertEquals(7, parseDays(listOf("täglich")).size)
        assertEquals(LocalDateTime.of(2026, 11, 8, 14, 30), parseDateTime("2026-11-08 14:30"))
        assertEquals(LocalDateTime.of(2026, 11, 8, 0, 0), parseDateTime("2026-11-08"))
        assertEquals("1 h 30 min", formatDuration(5400))
        assertEquals("10 min", formatDuration(600))
    }

    @Test
    fun fuzzyMatchesSpokenNames() {
        assertTrue(Fuzzy.score("maxi", "Max Müller") > 0.78)
        assertTrue(Fuzzy.score("müller", "Max Mueller") > 0.9)
        assertTrue(Fuzzy.score("spotifai", "Spotify") > 0.78)
        assertTrue(Fuzzy.score("oma", "Max Müller") < 0.78)
        assertEquals("mama", Fuzzy.normalize("Mama ❤️"))
    }

    @Test
    fun rankPicksClearWinnerAndKeepsAmbiguity() {
        val names = listOf("Max Müller", "Maximilian Schmidt", "Anna Berg")
        assertEquals(listOf("Max Müller"), Fuzzy.rank("Max Müller", names) { it }.map { it.item })
        val ambiguous = Fuzzy.rank("Max", listOf("Max Müller", "Max Schmidt")) { it }
        assertEquals(2, ambiguous.size)
    }

    @Test
    fun failureKindsMapToActionableMessages() {
        assertEquals(FailureKind.AUTH, FailureKind.fromHttp(401, null))
        assertEquals(FailureKind.QUOTA, FailureKind.fromHttp(429, """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(FailureKind.RATE_LIMIT, FailureKind.fromHttp(429, "slow down"))
        assertEquals(FailureKind.QUOTA, FailureKind.fromHttp(402, null))
        assertEquals(FailureKind.SERVER, FailureKind.fromHttp(503, null))
        assertEquals(FailureKind.NETWORK, FailureKind.fromThrowable(java.net.UnknownHostException()))
        assertEquals(FailureKind.AUTH, FailureKind.fromErrorCode("invalid_api_key", ""))
        assertNull(FailureKind.fromErrorCode("conversation_already_has_active_response", ""))
    }

    @Test
    fun weatherCodes() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("klar", WeatherTool.wmo(0))
            assertEquals("Gewitter", WeatherTool.wmo(95))
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("clear", WeatherTool.wmo(0))
            assertEquals("thunderstorm", WeatherTool.wmo(95))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
