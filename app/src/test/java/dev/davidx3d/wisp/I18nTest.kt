package dev.davidx3d.wisp

import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.data.settings.defaultLanguage
import dev.davidx3d.wisp.data.settings.defaultSystemPrompt
import dev.davidx3d.wisp.i18n.tr
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nTest {
    private val original = Locale.getDefault()

    @After fun restore() = Locale.setDefault(original)

    @Test
    fun followsSystemLocale() {
        Locale.setDefault(Locale.GERMANY)
        assertEquals("Hallo", tr("Hallo", "Hello"))
        assertEquals("de", defaultLanguage())
        assertTrue(defaultSystemPrompt().startsWith("Du bist Wisp"))

        Locale.setDefault(Locale.US)
        assertEquals("Hello", tr("Hallo", "Hello"))
        assertEquals("en", defaultLanguage())
        assertTrue(defaultSystemPrompt().startsWith("You are Wisp"))

        // Unsupported system languages fall back to English, supported ones are kept.
        Locale.setDefault(Locale.JAPAN)
        assertEquals("en", defaultLanguage())
        Locale.setDefault(Locale.FRANCE)
        assertEquals("fr", AppSettings().language)
    }
}
