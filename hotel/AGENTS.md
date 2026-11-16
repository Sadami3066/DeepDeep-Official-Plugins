# hotel · RollingGo 酒店插件

外部 `.ddp` 插件模块：管理 RollingGo 官方 `rgh` CLI 的安装、登录与更新，通过技能与工具完成酒店搜索、房型报价、待支付订单、订单列表与单笔订单详情。房型结果在回答区用分组卡片呈现，工具结果文本是完整可读的酒店事实，原始 CLI 输出挂在 `details["deepdeep.raw"]`。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 1.2.0/Compose，plugin-api 1.2.0、design 0.10.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.2.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.3.0/capabilities（clipboard/files/linux/logs/settings/ui）/entryClass/provides（5 个工具、skill `deepdeep-hotel`、5 个工具卡槽位、page `main`） |
| `src/main/kotlin/.../HotelModels.kt` | 搜索/房型/订单/订单详情/预订的 `@Serializable` 结构：搜索含 `nameEn`、全部标签与设施；房型含每晚均价、整段总价、总价是否按晚数折算、全部取消政策、早餐份数、房间信息与房型图片；`HotelRoomGroup` 是卡片分组；`HotelOrderDetail` 对应 `order-detail` 的模块化全字段；`HotelPlanSelection` 是预订方案选择结果 |
| `src/main/kotlin/.../HotelJson.kt` | rgh 输出解析：去提示行后取完整 JSON；搜索 `hotelInformationList`（含 `nextPageToken`/筛选/无匹配标记）、房型 `roomRatePlans`（兼容 `totalPrice` 与只有 `averagePrice` 的实时返回，按入离日期换算并在模型里标记折算）、订单 `orderList` 概要（兼容旧 `orderInfoList`/数组与 `hotelBookingInfo` 形态）、`order-detail` 的 `orderDetail.baseInfo/hotelInfo/stayInfo/priceInfo/policyInfo`、锁价 `priceDetailsInfo`、下单 `bookingResult`；含房型分组、排序与方案选择纯函数；`withHotelRaw` 负责把原始 stdout 去 ANSI 后挂到 details |
| `src/main/kotlin/.../HotelObservations.kt` | 模型侧完整可读文本：搜索酒店事实、逐方案房型事实（每晚/总价/折算说明/早餐份数/全部取消政策/ID/日期/房间信息/图片）、订单概要全字段、订单详情全字段、下单结果、确认弹窗文案与状态码映射 |
| `src/main/kotlin/.../HotelTools.kt` | 5 个工具定义与执行：参数校验、结果文本与 details、`deepdeep.raw` 挂载、`hotel_book` 的方案选择（可选 rate_plan_id，多方案走原生单选 + 原生确认） |
| `src/main/kotlin/.../HotelCards.kt` | 工具卡：搜索（首店 hero + 全部行可展开）、房型分组（全部房型 + 组内全部方案可展开、全部取消政策）、订单概要（全部订单可展开、全字段）、订单详情（全字段可展开）、待支付订单；加载/空/失败态 |
| `src/main/kotlin/.../HotelCli.kt` | `rgh` 命令拼装（`HOME=/root`）、版本/登录/URL 解析；含 `order-detail <orderNo>` |
| `src/main/kotlin/.../HotelService.kt` | 执行 rgh 命令并解析；区分未安装/未登录/查询失败，锁价与下单失败带 RollingGo 返回原因；解析结果与原始输出一起返回 |
| `src/main/kotlin/.../HotelRuntime.kt` | 插件页状态：CLI 版本、登录状态、安装/登录/退出操作与阶段进度 |
| `src/main/kotlin/.../HotelPage.kt` | 插件自绘页：安装/更新、登录（打开授权链接）、退出、状态与错误重试 |
| `src/main/kotlin/.../HotelPlugin.kt` | 入口：availability / onEnable / uninstall / tools / skill / promptSections / ui（5 个工具卡槽位） |
| `src/main/kotlin/.../HotelContent.kt`、`HotelTexts.kt`、`HotelState.kt` | 技能文档、页面 zh/en 文案、快照模型 |
| `src/test/kotlin/...` | 解析（含真机 rgh 实时返回形态、真实 `orderList` 与 `orderDetail`）、分组与最低价、方案选择、文本事实、订单全字段、订单详情全字段、raw 挂载、卡片 payload 全量、CLI 命令、金额/晚数计算 |

## 要点

- RollingGo `rgh orders` 现在返回 `orderList` 概要（订单号、酒店、房型、房间数、入离日期、早餐、取消政策、状态、总价、下单时间、订单详情页、支付链接），旧版本仍可能是 `orderInfoList` + `hotelBookingInfo` 结构；解析同时支持两者，文本与卡片都按可用字段输出。
- 单笔订单完整信息用 `rgh order-detail <orderNo>`：`orderDetail` 按 `baseInfo`/`hotelInfo`/`stayInfo`/`priceInfo`/`policyInfo` 模块返回酒店确认号、电话、坐标、入住人、支付截止、免费取消截止等；`hotel_order_detail` 工具与卡片按这些字段全量呈现。
- 搜索与房型一律输出上游返回的全部行：不截断酒店数、标签、设施、房型、价格方案或取消政策；卡片默认预览、用 `DdExpandToggle` 展开，70 个方案也逐条可达。
- RollingGo `rgh` 实时房型返回只有 `averagePrice`（每晚均价），没有 `totalPrice`：解析时按 `checkIn`/`checkOut` 的晚数换算整段总价，并在文本/卡片/预订结果标注“按 N 晚折算”；兼容旧版 `totalPrice`/`totalSalesRate` 与 `cancellationPolicies`。
- 房型按 `roomName` 归并：卡片一行一个房型，展示最低总价、每晚均价、方案数与最低价方案的取消政策；展开后逐方案列出床型、早餐、每晚/总价、全部取消政策、房间信息与房型图片。文本逐方案列出全部事实（含 ratePlanId）。
- 预订是真实资金操作：先锁价再下单；`hotel_book` 需要用户明确确认酒店、房型、价格方案、日期和总价与联系人信息。同一房型有多个价格方案时必须让用户明确选择，不允许静默取第一个；可选 `rate_plan_id` 指定方案。
- 模型文本不出现“卡片”措辞，不向用户展示 ratePlanId/referenceNo/hotelId；支付在 RollingGo 官方页面完成，插件不代替用户支付。
- 报价与取消政策以 RollingGo 实时返回为准；解析不到价格时显示“价格待确认”，不编造金额。
