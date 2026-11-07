package dev.davidx3d.wisp

import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle

/** Read-only IPC smoke test plus rejection of an invalid write, under Wisp's real UID. */
class NomiIntegrationProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        Thread {
            try {
                val resolver = targetContext.contentResolver
                val uri = Uri.parse("content://com.nomi.app.assistant")
                val before = checkNotNull(resolver.call(uri, "status", null, null))
                check(before.getBoolean("ok"))
                val invalid = checkNotNull(resolver.call(uri, "log_food", null, Bundle().apply {
                    putString("food", ""); putString("request_id", "probe-invalid")
                }))
                check(!invalid.getBoolean("ok") && invalid.getString("error") == "invalid_arguments")
                val after = checkNotNull(resolver.call(uri, "status", null, null))
                check(before.getDouble("total_kcal") == after.getDouble("total_kcal"))
                finish(0, Bundle().apply { putString("stream", "PASS: authenticated Nomi IPC, invalid write rejected, diary unchanged\n") })
            } catch (t: Throwable) {
                finish(1, Bundle().apply { putString("stream", "FAIL: ${t.javaClass.simpleName}: ${t.message}\n") })
            }
        }.start()
    }
}
