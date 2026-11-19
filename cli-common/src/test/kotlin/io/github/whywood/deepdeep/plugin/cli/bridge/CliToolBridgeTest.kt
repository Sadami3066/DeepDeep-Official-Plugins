package io.github.whywood.deepdeep.plugin.cli.bridge

import android.content.Context
import io.github.whywood.deepdeep.plugin.api.AccessibilityServicePlugin
import io.github.whywood.deepdeep.plugin.api.AppInfoService
import io.github.whywood.deepdeep.plugin.api.BackgroundService
import io.github.whywood.deepdeep.plugin.api.CalendarService
import io.github.whywood.deepdeep.plugin.api.ChatService
import io.github.whywood.deepdeep.plugin.api.ClipboardService
import io.github.whywood.deepdeep.plugin.api.ComponentService
import io.github.whywood.deepdeep.plugin.api.ContactsService
import io.github.whywood.deepdeep.plugin.api.CredentialService
import io.github.whywood.deepdeep.plugin.api.DeviceService
import io.github.whywood.deepdeep.plugin.api.FileService
import io.github.whywood.deepdeep.plugin.api.GoalService
import io.github.whywood.deepdeep.plugin.api.IntentsService
import io.github.whywood.deepdeep.plugin.api.JobService
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.LocationService
import io.github.whywood.deepdeep.plugin.api.LogService
import io.github.whywood.deepdeep.plugin.api.McpService
import io.github.whywood.deepdeep.plugin.api.MediaService
import io.github.whywood.deepdeep.plugin.api.MiniappService
import io.github.whywood.deepdeep.plugin.api.NetworkService
import io.github.whywood.deepdeep.plugin.api.NotificationService
import io.github.whywood.deepdeep.plugin.api.PackageService
import io.github.whywood.deepdeep.plugin.api.PermissionService
import io.github.whywood.deepdeep.plugin.api.PluginBus
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginServiceUnavailable
import io.github.whywood.deepdeep.plugin.api.PluginManifest
import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.PrivilegeService
import io.github.whywood.deepdeep.plugin.api.ScreenService
import io.github.whywood.deepdeep.plugin.api.SecretService
import io.github.whywood.deepdeep.plugin.api.SessionService
import io.github.whywood.deepdeep.plugin.api.SmsService
import io.github.whywood.deepdeep.plugin.api.SpeechService
import io.github.whywood.deepdeep.plugin.api.TimerService
import io.github.whywood.deepdeep.plugin.api.ToolCallAction
import io.github.whywood.deepdeep.plugin.api.ToolCallRequest
import io.github.whywood.deepdeep.plugin.api.ToolExecutionResult
import io.github.whywood.deepdeep.plugin.api.ToolResultAction
import io.github.whywood.deepdeep.plugin.api.ToolResultDecision
import io.github.whywood.deepdeep.plugin.api.UiService
import io.github.whywood.deepdeep.plugin.api.WebService
import io.github.whywood.deepdeep.plugin.api.resultReplace
import io.github.whywood.deepdeep.plugin.api.textResult
import io.github.whywood.deepdeep.plugin.cli.CliJsonEnvelope
import io.github.whywood.deepdeep.plugin.cli.objectList
import io.github.whywood.deepdeep.plugin.cli.stringValue
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliToolBridgeTest {
    private val sessionId = "session-1"

    private class FakeHost : PluginHost {
        override val manifest: PluginManifest get() = unavailable("manifest")
        override val context: Context get() = unavailable("context")
        override val dataDir: File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        override val assetsDir: File = dataDir
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        override fun text(source: LocalizedText, vararg args: Any): String {
            val resolved = source.resolve("zh").orEmpty()
            return if (args.isEmpty()) resolved else String.format(Locale.getDefault(), resolved, *args)
        }

        override fun chat(): ChatService = unavailable("chat")
        override fun linux(): LinuxService = unavailable("linux")
        override fun screen(): ScreenService = unavailable("screen")
        override fun notifications(): NotificationService = unavailable("notifications")
        override fun secrets(): SecretService = unavailable("secrets")
        override fun network(): NetworkService = unavailable("network")
        override fun files(): FileService = unavailable("files")
        override fun sessions(): SessionService = unavailable("sessions")
        override fun jobs(): JobService = unavailable("jobs")
        override fun timers(): TimerService = unavailable("timers")
        override fun goals(): GoalService = unavailable("goals")
        override fun miniapp(): MiniappService = unavailable("miniapp")
        override fun mcp(): McpService = unavailable("mcp")
        override fun ui(): UiService = unavailable("ui")
        override fun app(): AppInfoService = unavailable("app")
        override fun bus(): PluginBus = unavailable("bus")
        override fun logs(): LogService = unavailable("logs")
        override fun settings(): PluginSettingsService = unavailable("settings")
        override fun device(): DeviceService = unavailable("device")
        override fun clipboard(): ClipboardService = unavailable("clipboard")
        override fun permissions(): PermissionService = unavailable("permissions")
        override fun packages(): PackageService = unavailable("packages")
        override fun privilege(): PrivilegeService = unavailable("privilege")
        override fun intents(): IntentsService = unavailable("intents")
        override fun components(): ComponentService = unavailable("components")
        override fun background(): BackgroundService = unavailable("background")
        override fun location(): LocationService = unavailable("location")
        override fun contacts(): ContactsService = unavailable("contacts")
        override fun sms(): SmsService = unavailable("sms")
        override fun calendar(): CalendarService = unavailable("calendar")
        override fun media(): MediaService = unavailable("media")
        override fun speech(): SpeechService = unavailable("speech")
        override fun web(): WebService = unavailable("web")
        override fun credentials(): CredentialService = unavailable("credentials")
        override fun accessibility(): AccessibilityServicePlugin = unavailable("accessibility")

        override fun requestContributionsRefresh() = Unit

        private fun <T> unavailable(name: String): T = throw PluginServiceUnavailable(name)
    }

    private val config: CliBridgeConfig<String, String> = CliBridgeConfig(
        binaryName = "fake-cli",
        customType = "fake.card",
        logTag = "fake.bridge",
        parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
        observer = CliObservationBuilder { _, command, _ ->
            if (command == "read") {
                CliObservation(summary = "摘要", text = "第一行\n第二行", card = "payload")
            } else {
                null
            }
        },
        cardEncoder = { card -> buildJsonObject { put("value", card) } },
    )

    private val textOnlyConfig: CliBridgeConfig<String, String> = CliBridgeConfig(
        binaryName = "fake-cli",
        customType = "fake.card",
        logTag = "fake.bridge",
        parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
        observer = CliObservationBuilder { _, _, _ ->
            CliObservation(summary = "摘要", text = "第一行\n第二行")
        },
        cardEncoder = { card -> buildJsonObject { put("value", card) } },
    )

    private fun call(callId: String) = ToolCallRequest(
        sessionId = sessionId,
        callId = callId,
        toolName = "bash",
        args = buildJsonObject { put("command", "fake-cli read") },
    )

    private fun result(callId: String) = result(callId, """{"ok":true,"data":[]}""")

    private fun result(callId: String, output: String) = ToolExecutionResult(
        sessionId = sessionId,
        callId = callId,
        toolName = "bash",
        result = textResult(output),
    )

    private fun ToolResultDecision?.text(): String = this?.result?.text.orEmpty()

    private fun ToolResultDecision?.presentInstruction(callId: String): Boolean =
        text().contains("present({\"items\":[{\"type\":\"card\",\"id\":\"$callId\"}]})")

    @Test
    fun cardBecomesDetailsWithRawPayload() = runTest {
        val hooks = CliToolBridge(config).hooks(FakeHost())
        assertEquals(ToolCallAction.CONTINUE, hooks.beforeToolCall(call("call-1")).action)
        val decision = hooks.afterToolExecution(result("call-1"))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.contains("完整返回（上游原样）：\n{\"ok\":true,\"data\":[]}"))
        assertTrue(text.contains("使用说明：\n第一行\n第二行"))
        assertTrue(decision.presentInstruction("call-1"))
        val details = decision.result?.details as JsonObject
        assertEquals(null, details["deepdeep.raw"])
        assertEquals(null, details["deepdeep.guidance"])
        assertEquals("摘要", details["deepdeep.display"]?.jsonPrimitive?.content)
        val card = details["deepdeep.card"] as JsonObject
        assertEquals("fake.card", card.getValue("customType").jsonPrimitive.content)
        assertEquals("摘要", card.getValue("title").jsonPrimitive.content)
        assertEquals("payload", card.getValue("payload").jsonObject.getValue("value").jsonPrimitive.content)
    }

    @Test
    fun errorObservationKeepsCardWithoutIdHint() = runTest {
        val errorConfig = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, _ ->
                CliObservation(summary = "摘要", text = "未登录，请重新授权", card = "payload", isError = true)
            },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
        )
        val hooks = CliToolBridge(errorConfig).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(result("call-1"))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertEquals(true, decision.result?.isError)
        val text = decision.text()
        assertTrue(text.contains("未登录，请重新授权"))
        assertTrue(text.contains("""{"ok":true,"data":[]}"""))
        assertEquals(false, decision.presentInstruction("call-1"))
        val details = decision.result?.details as JsonObject
        assertEquals(null, details["deepdeep.card"])
        assertEquals(null, details["deepdeep.raw"])
    }

    @Test
    fun textOnlyObservationCarriesRawWithoutCard() = runTest {
        val hooks = CliToolBridge(textOnlyConfig).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(result("call-1"))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.contains("第一行\n第二行"))
        assertTrue(text.contains("""{"ok":true,"data":[]}"""))
        val details = decision.result?.details as JsonObject
        assertEquals(null, details["deepdeep.card"])
        assertEquals("第一行\n第二行", details["deepdeep.display"]?.jsonPrimitive?.content)
        assertEquals(false, decision.presentInstruction("call-1"))
    }

    @Test
    fun errorMappedResultIsKeptWithoutCard() = runTest {
        val mapped = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, _ -> CliObservation(summary = "摘要", text = "结果", card = "payload") },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
            errorMapper = CliErrorMapper { _, _, _ -> resultReplace(textResult("已处理错误")) },
        )
        val hooks = CliToolBridge(mapped).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val errorResult = ToolExecutionResult(sessionId, "call-1", "bash", textResult("boom", isError = true))
        val decision = hooks.afterToolExecution(errorResult)
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.startsWith("已处理错误"))
        assertTrue(text.contains("boom"))
        assertEquals(false, decision.presentInstruction("call-1"))
        assertEquals(null, (decision.result?.details as JsonObject)["deepdeep.card"])
    }

    @Test
    fun lenientEnvelopeAcceptsBareObjectWithoutSuccessField() = runTest {
        val lenient = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, payload ->
                CliObservation(
                    summary = "摘要",
                    text = "条目数 ${payload.objectList("items").size}",
                    card = "payload",
                )
            },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
            envelope = CliJsonEnvelope.LENIENT,
        )
        val hooks = CliToolBridge(lenient).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(result("call-1", """{"items":[{"id":1}]}"""))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.contains("使用说明：\n条目数 1"))
        assertTrue(text.contains("""{"items":[{"id":1}]}"""))
        assertTrue(decision.presentInstruction("call-1"))
    }

    @Test
    fun elementObserverHandlesTopLevelArrayPayload() = runTest {
        val arrayConfig = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = object : CliObservationBuilder<String, String> {
                override fun build(host: PluginHost, command: String, envelope: JsonObject): CliObservation<String>? =
                    null

                override fun build(host: PluginHost, command: String, payload: JsonElement): CliObservation<String> =
                    CliObservation(
                        summary = "摘要",
                        text = "数组条目 ${(payload as JsonArray).size}",
                        card = "payload",
                    )
            },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
            requireSuccessEnvelope = false,
        )
        val hooks = CliToolBridge(arrayConfig).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(
            result("call-1", "日志行\n[{\"id\":1},{\"id\":2}]\n[exit: 0]"),
        )
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.text().contains("数组条目 2"))
        assertTrue(decision.presentInstruction("call-1"))
    }

    @Test
    fun recoversCompleteOutputFromFullOutputPath() = runTest {
        val file = File.createTempFile("fake-cli-output", ".log")
        file.writeText("""{"ok":true,"data":[1,2,3]}""")
        val hooks = CliToolBridge(config).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val truncated = ToolExecutionResult(
            sessionId,
            "call-1",
            "bash",
            textResult(
                "…tail-only",
                details = buildJsonObject {
                    put("truncation", buildJsonObject { put("truncated", true) })
                    put("fullOutputPath", file.absolutePath)
                },
            ),
        )
        val decision = hooks.afterToolExecution(truncated)
        assertEquals(ToolResultAction.REPLACE, decision.action)
        val text = decision.text()
        assertTrue(text.contains("""{"ok":true,"data":[1,2,3]}"""))
        assertTrue(text.contains("第一行\n第二行"))
        assertTrue(decision.presentInstruction("call-1"))
        file.delete()
    }

    @Test
    fun setsTruncationHintWhenOutputCannotBeRecovered() = runTest {
        val hooks = CliToolBridge(config).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val truncated = ToolExecutionResult(
            sessionId,
            "call-1",
            "bash",
            textResult(
                "partial output",
                details = buildJsonObject {
                    put("truncation", buildJsonObject { put("truncated", true) })
                },
            ),
        )
        val decision = hooks.afterToolExecution(truncated)
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.result?.text.orEmpty().startsWith("partial output"))
        assertTrue(decision.result?.text.orEmpty().contains("已被截断"))
    }

    @Test
    fun redactRawAppliesToAttachedPayload() = runTest {
        val redacted = CliBridgeConfig<String, String>(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, _ -> CliObservation(summary = "摘要", text = "结果") },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
            redactRaw = { _, raw -> raw.replace("secret", "***") },
        )
        val hooks = CliToolBridge(redacted).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(result("call-1", """{"ok":true,"token":"secret"}"""))
        assertTrue(decision.text().contains("""{"ok":true,"token":"***"}"""))
    }

    @Test
    fun customPayloadFinderIsUsed() = runTest {
        val custom = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, payload ->
                CliObservation(
                    summary = "摘要",
                    text = "custom=${payload.stringValue("custom")}",
                    card = "payload",
                )
            },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
            payloadFinder = { buildJsonObject { put("custom", "found") } },
            requireSuccessEnvelope = false,
        )
        val hooks = CliToolBridge(custom).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val decision = hooks.afterToolExecution(result("call-1", "not json at all"))
        assertEquals(ToolResultAction.REPLACE, decision.action)
        assertTrue(decision.text().contains("custom=found"))
        assertTrue(decision.presentInstruction("call-1"))
    }

    @Test
    fun observationCustomTypeOverridesConfigType() = runTest {
        val override = CliBridgeConfig(
            binaryName = "fake-cli",
            customType = "fake.card",
            logTag = "fake.bridge",
            parser = CliCommandParser { raw -> if (raw.trim() == "fake-cli read") "read" else null },
            observer = CliObservationBuilder { _, _, _ ->
                CliObservation(summary = "摘要", text = "结果", card = "payload", customType = "fake.other")
            },
            cardEncoder = { card -> buildJsonObject { put("value", card) } },
        )
        val hooks = CliToolBridge(override).hooks(FakeHost())
        hooks.beforeToolCall(call("call-1"))
        val card = hooks.afterToolExecution(result("call-1")).result?.details
            ?.get("deepdeep.card") as JsonObject
        assertEquals("fake.other", card.getValue("customType").jsonPrimitive.content)
    }
}
