package dev.davidx3d.wisp.tools

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.davidx3d.wisp.services.WispAccessibilityService
import dev.davidx3d.wisp.tools.system.PrivilegedShell
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Checks and requests everything a tool needs. */
@Singleton
class PermissionChecker @Inject constructor(@ApplicationContext private val context: Context) {

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun hasSpecial(a: SpecialAccess): Boolean = when (a) {
        SpecialAccess.NOTIFICATION_LISTENER ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        SpecialAccess.ACCESSIBILITY -> {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = ComponentName(context, WispAccessibilityService::class.java)
            enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
        SpecialAccess.WRITE_SETTINGS -> Settings.System.canWrite(context)
        SpecialAccess.DND_POLICY -> context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        SpecialAccess.PRIVILEGED -> PrivilegedShell.status(rootAllowed = true).available
    }

    /** Human-readable names of everything still missing for [tool]. */
    fun missing(tool: Tool): List<String> =
        tool.permissions.filterNot(::hasPermission).map(::label) + tool.specialAccess.filterNot(::hasSpecial).map { it.title }

    fun isReady(tool: Tool) = missing(tool).isEmpty()

    /** Intent that opens the right system screen for a special access. */
    fun intentFor(a: SpecialAccess): Intent? = when (a) {
        SpecialAccess.NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SpecialAccess.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SpecialAccess.WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        SpecialAccess.DND_POLICY -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        SpecialAccess.PRIVILEGED -> null
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        fun label(p: String): String = when (p) {
            Manifest.permission.RECORD_AUDIO -> "Mikrofon"
            Manifest.permission.READ_CONTACTS -> "Kontakte"
            Manifest.permission.CALL_PHONE -> "Telefon"
            Manifest.permission.SEND_SMS -> "SMS senden"
            Manifest.permission.READ_SMS -> "SMS lesen"
            Manifest.permission.READ_CALENDAR -> "Kalender lesen"
            Manifest.permission.WRITE_CALENDAR -> "Kalender schreiben"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Genauer Standort"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Ungefährer Standort"
            Manifest.permission.POST_NOTIFICATIONS -> "Benachrichtigungen"
            Manifest.permission.CAMERA -> "Kamera"
            else -> p.substringAfterLast('.')
        }
    }
}
