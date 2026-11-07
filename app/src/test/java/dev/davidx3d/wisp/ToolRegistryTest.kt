package dev.davidx3d.wisp

import android.content.ContextWrapper
import dev.davidx3d.wisp.data.settings.AppSettings
import dev.davidx3d.wisp.tools.Tool
import dev.davidx3d.wisp.tools.ToolArgs
import dev.davidx3d.wisp.tools.ToolCategory
import dev.davidx3d.wisp.tools.ToolContext
import dev.davidx3d.wisp.tools.ToolRegistry
import dev.davidx3d.wisp.tools.ToolResult
import dev.davidx3d.wisp.tools.schema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    private var executed = 0

    private inner class EchoTool : Tool {
        override val name = "echo"
        override val title = "Echo"
        override val description = "Gibt Text zurück"
        override val category = ToolCategory.DEVICE
        override val parameters = schema { string("text", "Text", required = true) }
        override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
            executed++
            return ToolResult.ok("Echo", "text" to args.requireString("text"))
        }
    }

    private inner class CallLikeTool : Tool {
        override val name = "call_like"
        override val title = "Anruf"
        override val description = "Braucht Bestätigung"
        override val category = ToolCategory.COMMUNICATION
        override val confirmable = true
        override fun confirmation(args: ToolArgs, ctx: ToolContext) = "Max anrufen"
        override suspend fun execute(args: ToolArgs, ctx: ToolContext): ToolResult {
            executed++
            return ToolResult.ok("Anruf")
        }
    }

    private class SystemTool : Tool {
        override val name = "wifi"
        override val title = "WLAN"
        override val description = "WLAN"
        override val category = ToolCategory.SYSTEM
        override suspend fun execute(args: ToolArgs, ctx: ToolContext) = ToolResult.ok("ok")
    }

    private val ctxApp = ContextWrapper(null)
    private fun ctx(s: AppSettings = AppSettings()) = ToolContext(ctxApp, s)
    private fun registry(missing: List<String> = emptyList()) =
        ToolRegistry(listOf(EchoTool(), CallLikeTool(), SystemTool()), access = { if (it.name == "echo") missing else emptyList() })

    private fun parse(out: String) = Json.parseToJsonElement(out).jsonObject

    @Test
    fun specsContainEnabledToolsAndConfirmAction() {
        val names = registry().specs(AppSettings()).map { it.name }
        assertTrue("echo" in names)
        assertTrue("call_like" in names)
        assertTrue(ToolRegistry.CONFIRM_TOOL in names)
        assertFalse("system tools are off by default", "wifi" in names)
    }

    @Test
    fun disabledAndSystemToolsFollowSettings() {
        val s = AppSettings(disabledTools = setOf("echo"), systemControlEnabled = true, confirmActions = false)
        val names = registry().specs(s).map { it.name }
        assertFalse("echo" in names)
        assertTrue("wifi" in names)
        assertFalse(ToolRegistry.CONFIRM_TOOL in names)
    }

    @Test
    fun runsToolAndSerialisesResult() = runTest {
        val r = registry().run("echo", """{"text":"hallo"}""", ctx())
        val o = parse(r.toOutput())
        assertEquals("true", o["ok"]!!.jsonPrimitive.content)
        assertEquals("hallo", o["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun badInputBecomesReadableErrors() = runTest {
        val reg = registry()
        assertEquals("unknown_tool", (reg.run("nope", "{}", ctx()) as ToolResult.Failure).code)
        assertEquals("invalid_arguments", (reg.run("echo", "{kaputt", ctx()) as ToolResult.Failure).code)
        assertEquals("invalid_arguments", (reg.run("echo", "{}", ctx()) as ToolResult.Failure).code)
        assertEquals("tool_disabled", (reg.run("wifi", "{}", ctx()) as ToolResult.Failure).code)
    }

    @Test
    fun missingPermissionIsReportedNotExecuted() = runTest {
        val r = registry(missing = listOf("Kontakte")).run("echo", """{"text":"x"}""", ctx())
        assertEquals("permission_missing", (r as ToolResult.Failure).code)
        assertTrue(r.message.contains("Kontakte"))
        assertEquals(0, executed)
    }

    @Test
    fun confirmationFlowByVoice() = runTest {
        val reg = registry()
        var pendingSeen = false
        val first = reg.run("call_like", "{}", ctx()) { pendingSeen = true }
        val o = parse(first.toOutput())
        assertEquals("needs_confirmation", o["status"]!!.jsonPrimitive.content)
        assertTrue(pendingSeen)
        assertEquals(0, executed)

        val id = o["confirmation_id"]!!.jsonPrimitive.content
        val second = reg.run(ToolRegistry.CONFIRM_TOOL, """{"confirmation_id":"$id","approved":true}""", ctx())
        assertTrue(second is ToolResult.Success)
        assertEquals(1, executed)
        assertTrue(reg.pendingActions.isEmpty())
    }

    @Test
    fun confirmationCanBeDeclinedAndIsSkippedWhenOff() = runTest {
        val reg = registry()
        val id = parse(reg.run("call_like", "{}", ctx()).toOutput())["confirmation_id"]!!.jsonPrimitive.content
        val declined = reg.resolve(id, approved = false, ctx())
        assertEquals("cancelled", parse(declined.toOutput())["status"]!!.jsonPrimitive.content)
        assertEquals(0, executed)

        reg.run("call_like", "{}", ctx(AppSettings(confirmActions = false)))
        assertEquals(1, executed)
    }
}
