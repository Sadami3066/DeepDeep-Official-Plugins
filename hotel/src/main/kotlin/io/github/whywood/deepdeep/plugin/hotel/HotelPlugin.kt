package io.github.whywood.deepdeep.plugin.hotel

import androidx.compose.runtime.Composable
import io.github.whywood.deepdeep.plugin.api.Contributions
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.NotificationActionEvent
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHooks
import io.github.whywood.deepdeep.plugin.api.PluginHost
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class HotelPlugin : DeepDeepPlugin {
    private val runtime = HotelRuntime()

    override suspend fun availability(host: PluginHost): PluginAvailability =
        if (runCatching { host.linux().available }.getOrDefault(false)) {
            available()
        } else {
            unavailable(host.text(ERROR_LINUX))
        }

    override suspend fun onEnable(host: PluginHost) {
        host.scope.launch { runCatching { runtime.refresh(host) } }
    }

    override suspend fun onUpgrade(host: PluginHost, fromVersion: String) {
        host.scope.launch { runCatching { runtime.refresh(host) } }
    }

    override suspend fun uninstall(host: PluginHost) {
        withContext(NonCancellable) {
            runtime.reset(host)
        }
        runCatching { host.settings().clear() }
    }

    override fun contributions(host: PluginHost): Contributions = contributions(
        hooks = object : PluginHooks {
            override suspend fun notificationAction(event: NotificationActionEvent) {
                if (event.actionId == ACTION_OPEN_PAGE) host.ui().openPage(HOTEL_PAGE_ID)
            }
        },
    )

    override fun skills(host: PluginHost): List<PluginSkill> = listOf(
        pluginSkill(id = HOTEL_SKILL_ID, markdown = HOTEL_SKILL_MARKDOWN),
    )

    override fun promptSections(host: PluginHost): List<PromptSection> = listOf(
        promptSection(HOTEL_SECTION_ID, promptBody(host, runtime.currentSnapshot())),
    )

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = HOTEL_PAGE_ID,
                title = literal("酒店", "Hotels"),
                icon = assetIcon(HOTEL_ICON_ASSET),
                content = { HotelPage(host, runtime) },
            ),
        )
    }

    private fun promptBody(host: PluginHost, snapshot: HotelSnapshot?): String {
        if (!runCatching { host.linux().available }.getOrDefault(false)) {
            return "酒店插件当前不可用：需要 Linux 运行环境。"
        }
        if (snapshot == null) return "酒店插件状态尚未加载完成。"
        val version = snapshot.installedVersion
        if (version == null) return "酒店 CLI 尚未安装。"
        if (!snapshot.loggedIn) return "酒店 CLI 已安装（v$version），但尚未登录。"
        val account = snapshot.account?.takeIf { it.isNotBlank() }?.let { "，账号 $it" }.orEmpty()
        return "酒店插件已登录$account。命令用法见 deepdeep-hotel 技能。"
    }

    private companion object {
        const val ACTION_OPEN_PAGE = "open_hotel_page"
    }
}
