package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.NotificationActionEvent
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.PromptSection
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.promptSection
import io.github.whywood.deepdeep.plugin.api.unavailable
import java.io.File
import kotlinx.coroutines.launch

class FeishuPlugin : DeepDeepPlugin {
    private val runtime = FeishuRuntime()

    override suspend fun availability(host: PluginHost): PluginAvailability {
        val linux = runCatching { host.linux() }.getOrNull()
        return if (linux != null && linux.available) {
            available()
        } else {
            unavailable(host.text(UNAVAILABLE_LINUX))
        }
    }

    override suspend fun onEnable(host: PluginHost) {
        host.scope.launch { runtime.refresh(host) }
    }

    override suspend fun onDisable(host: PluginHost) = Unit

    override suspend fun onUpgrade(host: PluginHost, fromVersion: String) {
        host.scope.launch { runtime.refresh(host) }
    }

    override suspend fun uninstall(host: PluginHost) {
        runCatching {
            val linux = host.linux()
            if (linux.available) linux.exec(FeishuCli.uninstallCommand(), timeoutMs = UNINSTALL_TIMEOUT_MS)
        }
        runtime.reset(host)
        runCatching { host.settings().clear() }
    }

    override suspend fun close() = Unit

    override fun contributions(host: PluginHost): Contributions = contributions(
        hooks = object : PluginHooks {
            override suspend fun notificationAction(event: NotificationActionEvent) {
                if (event.actionId == ACTION_OPEN_PAGE) host.ui().openPage(FEISHU_PAGE_ID)
            }
        },
    )

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(
            id = FEISHU_SKILL_ID,
            markdown = SKILL_MARKDOWN.replace("<download-directory>", downloadDirectory(host)),
        ),
    )

    override fun promptSections(host: PluginHost): List<PromptSection> = listOf(
        promptSection(FEISHU_SECTION_ID, promptBody(host)),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = FEISHU_PAGE_ID,
                title = literal("飞书", "Feishu"),
                icon = assetIcon(FEISHU_ICON_ASSET),
                content = { FeishuPage(host = host, runtime = runtime) },
            ),
        )
    }

    private fun promptBody(host: PluginHost): String {
        val linuxAvailable = runCatching { host.linux().available }.getOrDefault(false)
        if (!linuxAvailable) {
            return "飞书插件当前不可用：需要 arm64 设备并已安装、启用 Linux 运行环境。"
        }
        val snapshot = runtime.currentSnapshot()
        if (snapshot == null) {
            return "飞书插件状态尚未加载完成。"
        }
        val version = snapshot.installedVersion
        if (version == null) {
            return "飞书 CLI 尚未安装。"
        }
        if (!snapshot.connected) {
            return "飞书 CLI 已安装（v$version），但尚未连接。"
        }
        val account = snapshot.account?.takeIf { it.isNotBlank() } ?: "当前账号"
        return "飞书插件已连接为 $account。命令用法见 deepdeep-feishu 技能；读取类命令直接执行，" +
            "命令输出 JSON，自己读字段回答，不要把原始 JSON 贴给用户；发送、修改、删除前先确认。"
    }

    private fun downloadDirectory(host: PluginHost): String {
        val workspace = runCatching { host.files().workspaceDir }.getOrNull()
        val base = workspace?.takeIf { it.isNotBlank() }?.let(::File) ?: host.dataDir
        return File(base, "tmp").absolutePath
    }
}
