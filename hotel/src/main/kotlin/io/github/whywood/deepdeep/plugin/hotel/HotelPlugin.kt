package io.github.whywood.deepdeep.plugin.hotel

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
import io.github.whywood.deepdeep.plugin.api.SlotIds
import io.github.whywood.deepdeep.plugin.api.ToolCardSlotState
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.composeSlot
import io.github.whywood.deepdeep.plugin.api.contributions
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.promptSection
import io.github.whywood.deepdeep.plugin.api.slotBinding
import io.github.whywood.deepdeep.plugin.api.unavailable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotelPlugin : DeepDeepPlugin {
    private val runtime = HotelRuntime()
    private val toolState = HotelToolState()

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
        tools = hotelTools(host, toolState),
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
        override val bindings: List<SlotBinding> = listOf(
            slotBinding(
                slotId = SlotIds.toolCard("hotel_search"),
                content = composeSlot { state -> HotelSearchCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_detail"),
                content = composeSlot { state -> HotelDetailCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_orders"),
                content = composeSlot { state -> HotelOrdersCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_book"),
                content = composeSlot { state -> HotelBookCard(host, state as ToolCardSlotState) },
            ),
        )

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = HOTEL_PAGE_ID,
                title = literal("RollingGo酒店", "RollingGo Hotels"),
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
        if (snapshot.installedVersion == null) return "酒店服务尚未安装，需要用户先在插件页安装。"
        if (!snapshot.loggedIn) return "酒店服务已安装（v${snapshot.installedVersion}），但尚未登录 RollingGo。"
        val account = snapshot.account?.takeIf { it.isNotBlank() }?.let { "，账号 $it" }.orEmpty()
        return buildString {
            append("酒店插件已登录$account。")
            append("查询酒店用 hotel_search，房型用 hotel_detail，预订用 hotel_book，订单用 hotel_orders；工具返回的文字已经包含完整事实，直接引用即可，不要复述原始 JSON。")
            append("同一房型可能有多个价格方案：把用户选定方案的价格方案 ID 作为 rate_plan_id 传给 hotel_book；未指定且存在多个方案时插件会弹出原生选择，不要替用户选第一个。")
            append("只有用户明确确认酒店、房型、价格方案、日期和总价，并提供联系人名、姓和邮箱后，才能调用 hotel_book；插件只创建待支付订单并返回官方支付链接，不代替用户支付。")
        }
    }

    private companion object {
        const val ACTION_OPEN_PAGE = "open_hotel_page"
    }
}
