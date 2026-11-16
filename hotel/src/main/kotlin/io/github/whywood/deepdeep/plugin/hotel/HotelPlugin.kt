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
                slotId = SlotIds.toolCard("hotel_order_detail"),
                content = composeSlot { state -> HotelOrderDetailCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_book"),
                content = composeSlot { state -> HotelBookCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_cancel"),
                content = composeSlot { state -> HotelCancelCard(host, state as ToolCardSlotState) },
            ),
            slotBinding(
                slotId = SlotIds.toolCard("hotel_tags"),
                content = composeSlot { state -> HotelTagsCard(host, state as ToolCardSlotState) },
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
        if (snapshot == null) {
            return "酒店插件已启用；如果查询提示未安装或未登录，请打开插件页处理，不要重复重试。"
        }
        if (snapshot.installedVersion == null) return "酒店服务尚未安装，需要用户先在插件页安装。"
        if (!snapshot.loggedIn) return "酒店服务已安装（v${snapshot.installedVersion}），但尚未登录 RollingGo。"
        val account = snapshot.account?.takeIf { it.isNotBlank() }?.let { "，账号 $it" }.orEmpty()
        return buildString {
            append("酒店插件已登录$account。")
            append("查询酒店用 hotel_search，房型用 hotel_detail，预订用 hotel_book，订单列表用 hotel_orders，单笔订单完整信息用 hotel_order_detail，取消订单用 hotel_cancel，筛标签用 hotel_tags；工具结果正文是上游完整返回，另附使用说明，按使用说明回答，不要复述原始 JSON。结果带卡片 id 时用 present 交付卡片。")
            append("搜索支持预算 max_price（会传给上游筛选，未传时也会从 query 识别）、距离 distance_in_meter、必须标签 required_tags（合法标签名先用 hotel_tags 查）和品牌 preferred_brands；用户说「离地铁近」「要含早」「带娃」时用对应参数，不要自己编标签。")
            append("订单列表没有取消原因；用户问取消原因、确认号、酒店电话、入住人或支付截止时间时用 hotel_order_detail。")
            append("取消必须两步：先调用 hotel_cancel（不带 confirm）只拿到罚金和凭证，把罚金与政策告诉用户，用户明确同意后再带 confirm=true 真正取消；取消不可撤销，不能直接传 confirm=true。")
            append("同一房型可能有多个价格方案：把用户选定方案的价格方案 ID 作为 rate_plan_id 传给 hotel_book；未指定且存在多个方案时插件会弹出原生选择，不要替用户选第一个。")
            append("只有用户明确确认酒店、房型、价格方案、日期和总价，并提供联系人名、姓和邮箱（首次预订后插件会记住联系人，之后可省略）后，才能调用 hotel_book；多房间或多人入住时传 guests，特殊要求传 customer_request；插件只创建待支付订单并返回官方支付链接，不代替用户支付。")
        }
    }

    private companion object {
        const val ACTION_OPEN_PAGE = "open_hotel_page"
    }
}
