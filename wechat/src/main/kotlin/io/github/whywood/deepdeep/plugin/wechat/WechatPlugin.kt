package io.github.whywood.deepdeep.plugin.wechat

import android.os.Build
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.PromptSection
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.promptSection
import io.github.whywood.deepdeep.plugin.api.unavailable
import java.io.File

class WechatPlugin : DeepDeepPlugin {
    @Volatile
    private var lastStatus: WechatStatus? = null

    override suspend fun availability(host: PluginHost): PluginAvailability {
        if (SUPPORTED_ABI !in Build.SUPPORTED_ABIS) {
            return unavailable(host.text(TEXT_UNAVAILABLE_DEVICE))
        }
        val linux = linuxOrNull(host)
        if (linux == null || !linux.available) {
            return unavailable(host.text(TEXT_UNAVAILABLE_LINUX))
        }
        val sourceReadable = runCatching {
            linux.exec(WechatCli.sourceReadableCommand(), timeoutMs = SOURCE_CHECK_TIMEOUT_MS).exitCode == 0
        }.getOrDefault(false)
        if (!sourceReadable) {
            return unavailable(host.text(TEXT_UNAVAILABLE_SOURCE))
        }
        return available()
    }

    override suspend fun onEnable(host: PluginHost) {
        runCatching { loadStatus(host) }
    }

    override suspend fun uninstall(host: PluginHost) {
        val linux = linuxOrNull(host) ?: return
        if (!linux.available) return
        runCatching { linux.exec(WechatCli.cleanupCommand(), timeoutMs = WRITE_TIMEOUT_MS) }
    }

    override fun skills(host: PluginHost): List<PluginSkill> =
        listOf(pluginSkill(WechatCli.SKILL_ID, WechatContent.skillMarkdown()))

    override fun promptSections(host: PluginHost): List<PromptSection> {
        val status = lastStatus
        return listOf(promptSection(SECTION_ID, WechatContent.promptBody(status), PROMPT_PRIORITY))
    }

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = PAGE_ID,
                title = TEXT_PAGE_TITLE,
                content = { WechatPage(this@WechatPlugin, host) },
            ),
        )
    }

    internal suspend fun loadStatus(host: PluginHost): WechatStatus {
        if (!linuxAvailable(host)) return WechatStatus(linuxAvailable = false).also { lastStatus = it }
        val linux = linuxOrNull(host) ?: return WechatStatus(linuxAvailable = false).also { lastStatus = it }
        val output = runCatching {
            linux.exec(WechatCli.statusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
        }.getOrNull()?.stdout.orEmpty()
        val raw = WechatCli.parseStatus(output)
        return WechatStatus(
            linuxAvailable = true,
            nodeVersion = WechatCli.parseNodeVersion(raw.node),
            cliVersion = WechatCli.parseVersion(raw.cli),
            hasSnapshot = raw.snapshot,
            hasNormalized = raw.normalized,
            exports = raw.exports,
        ).also { lastStatus = it }
    }

    internal suspend fun ensureInstalled(host: PluginHost, onProgress: (String) -> Unit) {
        val linux = requireLinux(host)
        onProgress(host.text(TEXT_STAGE_CHECKING))
        val nodeOutput = linux.exec(WechatCli.nodeVersionCommand(), timeoutMs = NODE_TIMEOUT_MS).stdout
        val nodeVersion = WechatCli.parseNodeVersion(nodeOutput)
            ?: throw WechatException(host.text(TEXT_ERROR_NODE_MISSING))
        val major = WechatCli.parseMajor(nodeVersion) ?: 0
        if (major < WechatCli.MIN_NODE_MAJOR) {
            throw WechatException(host.text(TEXT_ERROR_NODE_OLD, nodeVersion))
        }

        val installed = runCatching {
            linux.exec(WechatCli.cliVersionCommand(), timeoutMs = NODE_TIMEOUT_MS)
        }.getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.let { WechatCli.parseVersion(it) }
        if (installed == WechatCli.VERSION) return

        val assetDir = File(host.assetsDir, WechatCli.ASSET_ROOT)
        if (!File(assetDir, WechatCli.ENTRY_RELATIVE).isFile) {
            throw WechatException(host.text(TEXT_ERROR_ASSET, WechatCli.ENTRY_RELATIVE))
        }
        onProgress(host.text(TEXT_STAGE_INSTALLING))
        val result = linux.exec(WechatCli.installCommand(assetDir.absolutePath), timeoutMs = INSTALL_TIMEOUT_MS)
        val version = WechatCli.parseVersion(result.stdout)
        if (result.exitCode != 0 || version != WechatCli.VERSION) {
            throw WechatException(host.text(TEXT_ERROR_INSTALL, WechatCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)))
        }
    }

    internal suspend fun syncAndIngest(host: PluginHost, onProgress: (String) -> Unit) {
        ensureInstalled(host, onProgress)
        val linux = requireLinux(host)
        onProgress(host.text(TEXT_STAGE_SYNC))
        val result = linux.exec(WechatCli.syncCommand(), timeoutMs = SYNC_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw WechatException(host.text(TEXT_ERROR_SYNC, WechatCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)))
        }
    }

    internal suspend fun exportRange(host: PluginHost, from: String, to: String, onProgress: (String) -> Unit): String {
        if (!WechatCli.DATE_PATTERN.matches(from) || !WechatCli.DATE_PATTERN.matches(to)) {
            throw WechatException(host.text(TEXT_ERROR_DATE))
        }
        ensureInstalled(host, onProgress)
        val linux = requireLinux(host)
        onProgress(host.text(TEXT_STAGE_EXPORT))
        val result = linux.exec(WechatCli.exportCommand(from, to), timeoutMs = EXPORT_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw WechatException(host.text(TEXT_ERROR_EXPORT, WechatCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)))
        }
        val output = WechatCli.cleanOutput(result.stdout)
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("文件：") }
            ?.removePrefix("文件：")
            ?.trim()
            ?: host.text(TEXT_EXPORT_DONE)
    }

    private fun linuxAvailable(host: PluginHost): Boolean =
        runCatching { host.linux().available }.getOrDefault(false)

    private fun linuxOrNull(host: PluginHost): LinuxService? =
        runCatching { host.linux() }.getOrNull()

    private fun requireLinux(host: PluginHost): LinuxService {
        val linux = linuxOrNull(host)
        if (linux == null || !linux.available) throw WechatException(host.text(TEXT_UNAVAILABLE_LINUX))
        return linux
    }

    private companion object {
        const val PAGE_ID = "main"
        const val SECTION_ID = "wechat_prompt"
        const val SUPPORTED_ABI = "arm64-v8a"
        const val PROMPT_PRIORITY = 10

        const val STATUS_TIMEOUT_MS = 30_000L
        const val SOURCE_CHECK_TIMEOUT_MS = 10_000L
        const val NODE_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 180_000L
        const val SYNC_TIMEOUT_MS = 300_000L
        const val EXPORT_TIMEOUT_MS = 180_000L
        const val ERROR_TAIL = 800

        val TEXT_PAGE_TITLE = literal("微信导出", "WeChat Export")
        val TEXT_UNAVAILABLE_DEVICE = literal("当前设备不支持 Linux 环境", "Linux is not supported on this device")
        val TEXT_UNAVAILABLE_LINUX = literal(
            "请先在 设置 → 运行环境 安装 Linux 环境",
            "Install the Linux runtime in Settings → Runtime first",
        )
        val TEXT_UNAVAILABLE_SOURCE = literal(
            "微信数据不可读：请把 系统访问 切到 Root",
            "WeChat data is not readable. Set system access to Root.",
        )
        val TEXT_STAGE_CHECKING = literal("正在检查 Node.js…", "Checking Node.js…")
        val TEXT_STAGE_INSTALLING = literal("正在安装微信导出 CLI…", "Installing the WeChat export CLI…")
        val TEXT_STAGE_SYNC = literal("正在同步微信数据库…", "Syncing the WeChat database…")
        val TEXT_STAGE_EXPORT = literal("正在导出…", "Exporting…")
        val TEXT_ERROR_NODE_MISSING = literal(
            "没有找到 Node.js：请到 设置 → 运行环境 安装 Node.js 24+",
            "Node.js not found. Install Node.js 24+ in Settings → Runtime.",
        )
        val TEXT_ERROR_NODE_OLD = literal(
            "Node.js 版本过低（%1\$s）：需要 24 或更高",
            "Node.js is too old (%1\$s); version 24 or newer is required.",
        )
        val TEXT_ERROR_ASSET = literal("插件资源缺失：%1\$s", "Plugin asset is missing: %1\$s")
        val TEXT_ERROR_INSTALL = literal("安装失败：%1\$s", "Install failed: %1\$s")
        val TEXT_ERROR_SYNC = literal("同步失败：%1\$s", "Sync failed: %1\$s")
        val TEXT_ERROR_EXPORT = literal("导出失败：%1\$s", "Export failed: %1\$s")
        val TEXT_ERROR_DATE = literal("日期格式必须是 YYYY-MM-DD", "Date must be YYYY-MM-DD")
        val TEXT_EXPORT_DONE = literal("导出完成", "Export complete")
    }
}
