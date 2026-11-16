package io.github.whywood.deepdeep.plugin.hotel

internal const val HOTEL_SKILL_ID = "deepdeep-hotel"
internal const val HOTEL_SECTION_ID = "hotel_prompt"
internal const val HOTEL_PAGE_ID = "main"
internal const val HOTEL_ICON_ASSET = "icon.png"

internal val HOTEL_SKILL_MARKDOWN = """
    ---
    name: deepdeep-hotel
    description: RollingGo 酒店全流程：搜索酒店、按预算/距离/标签/品牌筛选、查房型报价、查订单与单笔详情、取消订单；当用户提到酒店、住宿、订房、房价、房型、取消或退订或 RollingGo 时使用。
    ---

    # 酒店

    DeepDeep 酒店插件连接 RollingGo，搜索、房型、订单、取消都用工具；结果正文是上游完整返回另附使用说明，按说明回答，不要复述原始 JSON；结果带卡片 id 时用 present 交付卡片。

    - 搜索：`hotel_search`，传 `query`（用户原话）、`place`，可选 `place_type`、`check_in`、`nights`、`adults`、`size`（1–10）、`star_ratings`。预算用 `max_price` 交给上游筛选（未传时也会从 query 识别「100 元以内」这类预算）；距离上限用 `distance_in_meter`（米）；必须满足的设施/位置特征用 `required_tags`；品牌偏好用 `preferred_brands`。结果卡可能按起价或距离排序，以卡片和 guidance 为准。
    - 标签：用户明确要某类设施或标签（如「带泳池」「可停车」「亲子」）时，先 `hotel_tags` 查合法标签名，再传 `required_tags`；不要自己发明标签。
    - 房型：`hotel_detail`，优先传 `hotel_id`，可选 `check_in`/`check_out`、`rooms`、`adults`；用户要含早、必须可取消或带儿童时传 `meal_type`、`cancel_policy`、`child_count`/`child_age`。结果按房型分组、组内按总价从低到高，含房型图、床型、全部取消政策与每个价格方案的 rate_plan_id；总价按晚数换算时会标注。
    - 订单列表：`hotel_orders`，可选 `status`（ALL / PENDING / FINISHED）与 `start_date`/`end_date`；列表没有取消原因，用户问取消原因、确认号、酒店电话、入住人、支付截止或免费取消截止时用 `hotel_order_detail`。
    - 取消：先 `hotel_cancel(order_no)` 只做预检，拿到取消罚金、取消政策和凭证有效期，把罚金告诉用户；用户明确同意后再 `hotel_cancel(order_no, confirm=true)` 真正取消。取消不可撤销，不能直接传 confirm=true；预检返回 canCancel=false 时说明不能在线取消并引导联系 RollingGo 客服，不要重试。
    - 预订：用户明确确认酒店、房型、价格方案、日期和总价后调用 `hotel_book`；联系人名/姓/邮箱首次必填，成功后插件会记住，之后可省略；多房间或多人入住传 `guests`（room / first_name / last_name / is_adult），特殊要求传 `customer_request`。同一房型有多个价格方案时传用户选定的 `rate_plan_id`；未传且存在多个方案时会弹原生选择，不要替用户选第一个。插件只创建待支付订单并返回官方支付链接，支付在 RollingGo 完成，不要说已经付款。
    - 不要向用户展示 referenceNo、hotelId、rate_plan_id 等内部字段；取消只报罚金与政策，不把取消凭证念给用户。
    - 用户没登录时引导到插件页登录，不要在对话里索要账号密码。
""".trimIndent()
