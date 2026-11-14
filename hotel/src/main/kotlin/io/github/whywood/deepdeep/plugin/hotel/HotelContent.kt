package io.github.whywood.deepdeep.plugin.hotel

internal const val HOTEL_SKILL_ID = "deepdeep-hotel"
internal const val HOTEL_SECTION_ID = "hotel_prompt"
internal const val HOTEL_PAGE_ID = "main"
internal const val HOTEL_ICON_ASSET = "icon.png"

internal val HOTEL_SKILL_MARKDOWN = """
    ---
    name: deepdeep-hotel
    description: RollingGo 酒店搜索与预订 CLI 用法：按城市/景点/机场/地铁站找酒店、查房型报价、锁价、下单、查订单和取消；当用户提到酒店、住宿、订房、查房价、房型、退订或 RollingGo 时使用。
    ---

    # 酒店 CLI

    DeepDeep 酒店插件管理官方 `rgh` CLI。`rgh` 用 OAuth 登录，不需要 API Key；命令输出 JSON，以退出码和字段判断结果。

    - 先检查登录：`rgh status`。未登录时运行 `rgh login`，从输出中取出 `https://...` 授权链接给用户；用户完成授权后继续。
    - 先看参数：`rgh search-hotels --help`、`rgh hotel-detail --help`；按 `--help` 的最新参数拼命令。
    - 搜索示例：`rgh search-hotels --origin-query "杭州西湖附近五星酒店" --place "杭州西湖" --place-type 景点 --check-in-date 2026-06-20 --stay-nights 1 --size 5`
    - 详情示例：`rgh hotel-detail --hotel-id 12345 --check-in-date 2026-06-20 --check-out-date 2026-06-21`
    - 只有已连接时才能查房价和下单；返回登录错误时重新走 `rgh login`。
    - 展示给用户时只保留酒店名、星级、参考价、距离、地址、设施与标签；不要把 JSON、hotelId、ratePlanId、referenceNo 等技术字段原样展示。
    - 搜索结果价格是参考价；实际金额以 `rgh price-confirm` 锁价为准。
    - 预订是真实资金操作：先展示房型和价格，等用户明确选择后执行 `rgh price-confirm`；再确认入住人姓名拼音与邮箱，等用户明确同意后执行 `rgh book`，把返回的支付链接发给用户。
    - 取消是高风险操作：先执行 `rgh pre-cancel` 查询违约金与凭证，向用户说明金额和预计退款，等用户明确确认后再执行 `rgh confirm-cancel`。
    - 支持 3-5 家对比；搜索无结果时按“去星级限制 → 扩大距离 → 硬标签降级为偏好 → 增大 size”的顺序静默放宽再试。
""".trimIndent()
