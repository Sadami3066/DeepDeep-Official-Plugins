package io.github.whywood.deepdeep.plugin.hotel

internal const val HOTEL_SKILL_ID = "deepdeep-hotel"
internal const val HOTEL_SECTION_ID = "hotel_prompt"
internal const val HOTEL_PAGE_ID = "main"
internal const val HOTEL_ICON_ASSET = "icon.png"

internal val HOTEL_SKILL_MARKDOWN = """
    ---
    name: deepdeep-hotel
    description: RollingGo 酒店搜索与预订：按城市/景点/机场/地铁站找酒店、查房型报价、查订单；当用户提到酒店、住宿、订房、查房价、房型、退订或 RollingGo 时使用。
    ---

    # 酒店

    DeepDeep 酒店插件连接 RollingGo。搜索、房型和订单优先使用工具，不要在回复里重复卡片或展示原始 JSON。

    - 搜索：`hotel_search`，传用户原话 `query`、`place`，可选日期、晚数、人数、星级。
    - 房型：用户选定酒店后调用 `hotel_detail`，优先用酒店名称，不要向用户展示 hotelId。
    - 订单：`hotel_orders`。
    - 卡片已经展示酒店名、星级、参考价、地址、房型和预订链接时，回复只补充建议或下一步，不要再列一遍。不要展示 hotelId、ratePlanId、referenceNo 等内部字段。
    - 搜索结果价格是参考价，实际金额以 RollingGo 页面为准。
    - 正常预订路径：引导用户打开卡片里的 RollingGo 链接，在官方页面确认房型、入住人和支付。
    - 用户明确要求你代为锁价或下单时，才使用 `rgh price-confirm` / `rgh book`；必须先展示房型和总价并取得明确同意，再确认入住人姓名拼音与邮箱，最后把支付链接交给用户。不要在未确认时锁价、下单或支付。
    - 用户要求取消时，先用 `rgh pre-cancel` 展示违约金与退款说明，明确确认后才执行 `rgh confirm-cancel`。
    - 用户没登录时，引导到插件页登录，不要在对话里索要账号密码。
""".trimIndent()
