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
    - 预订：用户明确确认酒店、房型、日期和总价，并提供联系人名、姓和邮箱后，调用 `hotel_book`；必须先展示房型和总价并取得明确同意，不要在未确认时锁价或下单。
    - 支付：`hotel_book` 会创建待支付订单并返回官方支付链接；把链接交给用户完成支付，不要声称已经付款。插件不代替用户支付。
    - 用户要求取消时，先用订单卡片查看状态，再引导用户到 RollingGo 官方页面处理；不要自行调用取消命令。
    - 用户没登录时，引导到插件页登录，不要在对话里索要账号密码。
""".trimIndent()
