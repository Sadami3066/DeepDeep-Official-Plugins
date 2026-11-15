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

    DeepDeep 酒店插件连接 RollingGo。搜索、房型和订单优先使用工具。

    - 搜索：`hotel_search`，传用户原话 `query`、`place`，可选日期、晚数、人数、星级。结果文本里有酒店名、星级、参考价、距离、地址、标签、hotelId 和官方预订链接，直接引用这些事实回答。
    - 房型：用户选定酒店后调用 `hotel_detail`，优先传 hotelId。文本按房型列出每个价格方案的床型、总价、均价、取消政策、价格方案 ID 和入离日期。
    - 订单：`hotel_orders`，结果文本包含订单号、房型、日期、状态、支付状态和总价；没有订单时直接说没有订单。
    - 不要向用户展示 ratePlanId、referenceNo、hotelId 等内部字段，也不要复述原始 JSON。
    - 搜索结果价格是参考价，实际金额以 RollingGo 价格确认为准。
    - 预订：用户明确确认酒店、房型、价格方案、日期和总价，并提供联系人名、姓和邮箱后，调用 `hotel_book`；同一房型有多个价格方案时必须让用户明确选择，并把所选方案的价格方案 ID 作为 `rate_plan_id` 传入，不要替用户选第一个。未传 rate_plan_id 且方案不唯一时会弹出原生选择。
    - 支付：`hotel_book` 会创建待支付订单并返回官方支付链接；把链接交给用户完成支付，不要声称已经付款。插件不代替用户支付。
    - 用户要求取消时，先用 `hotel_orders` 查看订单状态，再引导用户到 RollingGo 官方页面处理；不要自行调用取消命令。
    - 用户没登录时，引导到插件页登录，不要在对话里索要账号密码。
""".trimIndent()
