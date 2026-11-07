package dev.davidx3d.wisp.tools.system

import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

enum class Backend(val label: String) { SHIZUKU("Shizuku"), ROOT("Root (KernelSU)") }

data class ShellStatus(
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
    val rootGranted: Boolean,
    val rootAllowed: Boolean,
) {
    val backend: Backend? = when {
        shizukuRunning && shizukuGranted -> Backend.SHIZUKU
        rootAllowed && rootGranted -> Backend.ROOT
        else -> null
    }
    val available get() = backend != null
}

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

/**
 * Runs shell commands with elevated rights. Shizuku (ADB-level, no root needed) is preferred;
 * KernelSU/Magisk root via libsu is the fallback when allowed in the settings.
 */
object PrivilegedShell {

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(10))
    }

    fun status(rootAllowed: Boolean): ShellStatus {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = running && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val root = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
        return ShellStatus(running, granted, root, rootAllowed)
    }

    /** Asks Shizuku for permission; resolves with the user's answer. */
    suspend fun requestShizuku(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(REQUEST_CODE)
                cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
            }
        } ?: false
    }

    /** Triggers the KernelSU/Magisk grant prompt. */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    suspend fun run(command: String, rootAllowed: Boolean): ShellResult = withContext(Dispatchers.IO) {
        when (status(rootAllowed).backend) {
            Backend.SHIZUKU -> runShizuku(command)
            Backend.ROOT -> {
                val r = Shell.cmd(command).exec()
                ShellResult(r.code, r.out.joinToString("\n"), r.err.joinToString("\n"))
            }
            null -> ShellResult(-1, "", "no_backend")
        }
    }

    /**
     * Shizuku 13 made `newProcess` private in favour of bound user services. For a few
     * one-line `svc`/`cmd` calls a full AIDL service is overkill, so we call it reflectively.
     */
    private fun runShizuku(command: String): ShellResult {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return ShellResult(code, out.trim(), err.trim())
    }

    private const val REQUEST_CODE = 4711
}
