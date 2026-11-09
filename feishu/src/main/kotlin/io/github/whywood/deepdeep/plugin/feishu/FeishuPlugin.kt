package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.IconRef
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.PromptSection
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import java.io.File
import kotlinx.coroutines.launch

class FeishuPlugin : DeepDeepPlugin {
    private val runtime = FeishuRuntime()

    override suspend fun availability(host: PluginHost): PluginAvailability {
        val linux = runCatching { host.linux() }.getOrNull()
        return if (linux != null && linux.available) {
            PluginAvailability.Available
        } else {
            PluginAvailability.Unavailable(host.text(UNAVAILABLE_LINUX))
        }
    }

    override suspend fun onEnable(host: PluginHost) {
        host.scope.launch { runtime.refresh(host) }
    }

    override suspend fun onUpgrade(host: PluginHost, fromVersion: String) {
        host.scope.launch { runtime.refresh(host) }
    }

    override suspend fun uninstall(host: PluginHost) {
        runCatching {
            val linux = host.linux()
            if (linux.available) linux.exec(FeishuCli.uninstallCommand(), timeoutMs = UNINSTALL_TIMEOUT_MS)
        }
        runtime.reset(host)
    }

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        PluginSkill(
            id = FEISHU_SKILL_ID,
            markdown = SKILL_MARKDOWN.replace("<download-directory>", downloadDirectory(host)),
        ),
    )

    override fun promptSections(host: PluginHost): List<PromptSection> = listOf(
        PromptSection(FEISHU_SECTION_ID, promptBody(host)),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            PluginPage(
                id = FEISHU_PAGE_ID,
                title = literal("飞书", "Feishu"),
                icon = IconRef.Asset(FEISHU_ICON_ASSET),
                content = { FeishuPage(host = host, runtime = runtime) },
            ),
        )
    }

    private fun promptBody(host: PluginHost): String {
        val linuxAvailable = runCatching { host.linux().available }.getOrDefault(false)
        if (!linuxAvailable) {
            return buildString {
                appendLine("飞书插件当前不可用：需要 arm64 设备并已安装、启用 Linux 运行环境。")
                append("如需使用飞书能力，请先让用户在 设置 → 运行环境 安装并启用 Linux 环境，之后在插件页的飞书页面完成 CLI 安装与账号连接。")
            }
        }
        val snapshot = runtime.snapshot(host)
        val version = snapshot?.installedVersion
        if (version == null) {
            return buildString {
                appendLine("飞书 CLI 尚未安装。")
                appendLine("请让用户打开插件页的飞书页面点「安装/更新 CLI」，不要自行下载或安装 `lark-cli`。")
                append("安装完成后，再让用户在同一页面完成飞书应用创建与账号授权。")
            }
        }
        if (!snapshot.connected) {
            return buildString {
                appendLine("飞书 CLI 已安装（v$version），但尚未连接。")
                appendLine("请让用户在插件页面点「连接飞书」完成账号授权；不要自行执行 `lark-cli config init` 或 `lark-cli auth login`。")
                append("其余情况下如需使用飞书能力，先向用户说明需要完成插件连接。")
            }
        }
        val account = snapshot.account?.takeIf { it.isNotBlank() } ?: "当前账号"
        return "飞书插件已连接为 $account。命令用法见 deepdeep-feishu 技能；发送、修改、删除前先 --dry-run。"
    }

    private fun downloadDirectory(host: PluginHost): String {
        val workspace = runCatching { host.files().workspaceDir }.getOrNull()
        val base = workspace?.takeIf { it.isNotBlank() }?.let(::File) ?: host.dataDir
        return File(base, "tmp").absolutePath
    }
}
