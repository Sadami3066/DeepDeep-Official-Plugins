package io.github.whywood.deepdeep.plugin.zanao

import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.HostAccessLevel
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.pluginSkillFile
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.pluginPage
import org.json.JSONObject
import kotlinx.coroutines.flow.StateFlow
import java.io.File

internal data class ZanaoStatus(
    val access: HostAccessLevel,
    val adbConnected: Boolean,
    val configured: Boolean,
    val alias: String,
    val tokenLength: Int,
    val helperInstalled: Boolean,
)

class ZanaoPlugin : DeepDeepPlugin {
    @Volatile
    private var capture: ZanaoCapture? = null

    override suspend fun availability(host: PluginHost): PluginAvailability = available()

    override suspend fun onEnable(host: PluginHost) {
        runCatching { controller(host).cleanupStale(host) }
        runCatching { loadStatus(host) }
    }

    override suspend fun onDisable(host: PluginHost) {
        capture?.stop()
        capture = null
    }

    override suspend fun uninstall(host: PluginHost) {
        capture?.stop()
        capture = null
        runCatching {
            host.privilege().exec("settings delete global global_http_proxy_host", io.github.whywood.deepdeep.plugin.api.PrivilegeLevel.AUTO)
            host.privilege().exec("settings delete global global_http_proxy_port", io.github.whywood.deepdeep.plugin.api.PrivilegeLevel.AUTO)
            host.privilege().exec("settings delete global http_proxy", io.github.whywood.deepdeep.plugin.api.PrivilegeLevel.AUTO)
        }
        runCatching { host.secrets().clear() }
        runCatching { host.settings().clear() }
    }

    override fun contributions(host: PluginHost): Contributions = contributions()

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(
            id = "zanao-cli",
            markdown = ZANAO_CLI_SKILL.trimStart(),
            files = listOf(
                pluginSkillFile("zanao", assetPath = "zanao"),
                pluginSkillFile("zanao.py", assetPath = "zanao.py"),
            ),
        ),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = PAGE_ID,
                title = PAGE_TITLE,
                icon = assetIcon(ICON_ASSET),
                content = { ZanaoPage(this@ZanaoPlugin, host) },
            ),
        )
    }

    internal fun captureState(host: PluginHost): StateFlow<CaptureState> = controller(host).state

    internal fun startCapture(host: PluginHost) {
        controller(host).start(host)
    }

    internal fun stopCapture(host: PluginHost) {
        controller(host).stop()
    }

    internal suspend fun cleanup(host: PluginHost) {
        controller(host).cleanupStale(host)
    }

    internal suspend fun loadStatus(host: PluginHost): ZanaoStatus {
        val token = runCatching { host.secrets().get(SECRET_TOKEN) }.getOrNull().orEmpty()
        val alias = runCatching { host.settings().get(SETTING_ALIAS) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_ALIAS
        val access = runCatching { host.privilege().access }.getOrDefault(HostAccessLevel.LOCAL)
        val adb = runCatching { host.privilege().adb.connected }.getOrDefault(false)
        val helperInstalled = runCatching { host.packages().get(HELPER_PACKAGE) != null }.getOrDefault(false)
        return ZanaoStatus(
            access = access,
            adbConnected = adb,
            configured = token.isNotBlank(),
            alias = alias,
            tokenLength = token.length,
            helperInstalled = helperInstalled,
        )
    }

    internal suspend fun installHelper(host: PluginHost): String {
        val source = File(host.assetsDir, HELPER_ASSET)
        if (!source.isFile) throw ZanaoException("插件内置的捕捉器 APK 不存在")
        val target = File(host.dataDir, "zanao-token-catcher.apk")
        source.copyTo(target, overwrite = true)
        val result = host.packages().install(target.absolutePath, silent = false)
        if (!result.accepted) throw ZanaoException(result.error ?: "安装未开始")
        return if (result.pendingUserAction) "请在系统安装器里确认安装" else "捕捉器已安装"
    }

    internal fun launchHelper(host: PluginHost): Boolean {
        return runCatching { host.packages().launch(HELPER_PACKAGE) }.getOrDefault(false)
    }

    internal suspend fun helperInstalled(host: PluginHost): Boolean =
        runCatching { host.packages().get(HELPER_PACKAGE) != null }.getOrDefault(false)

    internal suspend fun importAccount(host: PluginHost, raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) throw ZanaoException("剪贴板是空的")
        var token = text
        var device = ""
        var alias = DEFAULT_ALIAS
        if (text.startsWith("{")) {
            val obj = runCatching { JSONObject(text) }.getOrElse {
                throw ZanaoException("剪贴板 JSON 无法解析")
            }
            token = obj.optString("token").trim()
            device = obj.optString("device").trim()
            alias = obj.optString("alias").trim().ifBlank { DEFAULT_ALIAS }
        } else {
            device = runCatching { host.secrets().get(SECRET_DEVICE) }.getOrNull().orEmpty()
        }
        if (token.isEmpty()) throw ZanaoException("没有 token")
        if (device.isEmpty()) throw ZanaoException("没有 device，请复制外部 App 的 token JSON")
        host.secrets().put(SECRET_TOKEN, token)
        host.secrets().put(SECRET_DEVICE, device)
        host.settings().put(SETTING_ALIAS, alias)
    }

    internal suspend fun saveAlias(host: PluginHost, alias: String) {
        host.settings().put(SETTING_ALIAS, alias.trim().ifBlank { DEFAULT_ALIAS })
    }

    internal suspend fun clearAccount(host: PluginHost) {
        host.secrets().remove(SECRET_TOKEN)
        host.secrets().remove(SECRET_DEVICE)
    }

    internal suspend fun feed(host: PluginHost, limit: Int = 20): List<ZanaoThread> =
        ZanaoClient.feed(host, limit)

    internal suspend fun detail(host: PluginHost, threadId: String): ZanaoThread =
        ZanaoClient.detail(host, threadId)

    internal suspend fun categories(host: PluginHost): List<ZanaoCategory> =
        ZanaoClient.categories(host)

    internal suspend fun post(
        host: PluginHost,
        title: String,
        content: String,
        anonymous: Boolean,
        cateId: String,
    ): String = ZanaoClient.post(host, title, content, anonymous, cateId)

    private fun controller(host: PluginHost): ZanaoCapture {
        capture?.let { return it }
        return synchronized(this) {
            capture ?: ZanaoCapture(host.scope).also { capture = it }
        }
    }
}
