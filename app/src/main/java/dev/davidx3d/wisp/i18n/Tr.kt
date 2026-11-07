package dev.davidx3d.wisp.i18n

import java.util.Locale

/**
 * Wisp speaks German on German devices and English everywhere else.
 *
 * Texts live next to the code as `tr(de, en)` pairs instead of strings.xml, because most of
 * them are built in tools, services and the session manager, far away from any Context.
 * Android restarts the process when the system language changes, so reading the locale at
 * call time is enough.
 */
object Lang {
    val isGerman: Boolean get() = Locale.getDefault().language == "de"

    /** Locale for dates and numbers in user-facing text. */
    val locale: Locale get() = if (isGerman) Locale.GERMAN else Locale.ENGLISH
}

fun tr(de: String, en: String): String = if (Lang.isGerman) de else en
