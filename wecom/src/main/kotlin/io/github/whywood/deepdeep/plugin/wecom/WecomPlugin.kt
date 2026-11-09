package io.github.whywood.deepdeep.plugin.wecom

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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class WecomPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability =
        if (linuxAvailable(host)) {
            PluginAvailability.Available
        } else {
            PluginAvailability.Unavailable(host.text(WecomTexts.unavailableLinux))
        }

    override suspend fun uninstall(host: PluginHost) {
        if (linuxAvailable(host)) {
            withContext(NonCancellable) {
                runCatching { host.linux().exec(WecomCli.uninstallCommand(), timeoutMs = UNINSTALL_TIMEOUT_MS) }
            }
        }
        WecomStatusStore(host).clear()
        runCatching {
            host.files().workspaceDir?.let { host.files().delete(WecomCli.qrFile(it).absolutePath) }
        }
        runCatching { host.files().delete(CLI_STAGING_DIR) }
    }

    override fun skills(host: PluginHost): List<PluginSkill> {
        val workspace = runCatching { host.files().workspaceDir }.getOrNull() ?: host.dataDir.absolutePath
        val markdown = SKILL_MARKDOWN.replace(DOWNLOAD_DIRECTORY_PLACEHOLDER, File(workspace, "tmp").absolutePath)
        return listOf(PluginSkill(SKILL_ID, markdown))
    }

    override fun promptSections(host: PluginHost): List<PromptSection> = listOf(
        PromptSection(SECTION_ID, promptBody(WecomStatusStore(host).read())),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            PluginPage(
                id = PAGE_ID,
                title = WecomTexts.pageTitle,
                icon = IconRef.Asset(ICON_ASSET),
                content = { WecomPage(host) },
            ),
        )
    }

    private fun linuxAvailable(host: PluginHost): Boolean =
        runCatching { host.linux().available }.getOrDefault(false)

    private fun promptBody(status: WecomStatus): String = if (status.authorized) {
        buildString {
            append("企业微信插件已连接")
            status.botId?.let { append("（Bot ID：$it）") }
            appendLine("。命令用法见 deepdeep-wecom 技能；发送、修改、删除前先 --dry-run 预览。")
        }
    } else {
        val label = if (status.installed) "企业微信未连接" else "企业微信 CLI 未安装"
        buildString {
            appendLine("企业微信插件当前不可用或未连接：$label。")
            append("其余情况下如需使用企业微信能力，先向用户说明需要完成插件连接。")
        }
    }

    private companion object {
        const val SKILL_ID = WECOM_SKILL_ID
        const val SECTION_ID = "wecom_prompt"
        const val PAGE_ID = "main"
        const val ICON_ASSET = "icon.png"
        const val CLI_STAGING_DIR = "cli-staging"
        const val DOWNLOAD_DIRECTORY_PLACEHOLDER = "<download-directory>"
        const val UNINSTALL_TIMEOUT_MS = 60_000L

        val SKILL_MARKDOWN = """
            ---
            name: deepdeep-wecom
            description: 企业微信 CLI 用法速查：消息、邮件、文档、在线表格、智能表格、日程、会议、待办、微盘与通讯录；当用户提到企业微信、WeCom、企微、发消息、文档、日程或待办时使用。
            ---

            # 企业微信 CLI

            DeepDeep 已安装官方 `wecom-cli`，在 bash 中直接运行。命令默认输出 compact JSON，以退出码和输出字段判断结果。

            - 先确认命令与参数：`wecom-cli --help`、`wecom-cli <service> --help`；需要文档或 schema 时加 `--doc` / `--schema`。
            - 服务目录在线获取，需要已授权与网络；鉴权失败时提示用户在插件页重新授权，不要自行运行 `auth init`。
            - 已授权范围内直接执行；超出授权的发送、修改、删除等操作先询问；支持时可用 `--dry-run` 预览。
            - 请求体可用命名参数、`--json '<JSON>'` 或 `--set path=value` 组合给出。
            - 发消息前先 `wecom-cli message aibot sessions list`，用本次返回的 `chat_id`；给授权人发消息可用 `wecom-cli identity whoami` 的 ID 作为 chat_id。
            - 发送图片、文件、语音或视频前，先经 `wecom-cli media` 上传拿到 `media_id`。
            - 拉取多页数据用 `--page-count <n>`；下载文件用 `--output-dir <download-directory>` 落到工作区，再按当前运行支持的交付方式返回文件绝对路径。
            - 不要自行创建定时任务或总结流程，除非用户明确要求。
        """.trimIndent()
    }
}

internal const val WECOM_SKILL_ID = "deepdeep-wecom"
