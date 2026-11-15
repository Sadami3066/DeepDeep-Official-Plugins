# hotel · RollingGo 酒店插件

外部 `.ddp` 插件模块：管理 RollingGo 官方 `rgh` CLI 的安装、登录与更新，通过技能与工具完成酒店搜索、房型报价、待支付订单与订单查询。房型结果在回答区用分组卡片呈现，工具结果文本是完整可读的酒店事实。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 1.0.10/Compose，plugin-api 1.2.0、design 0.9.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.0.10/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.1.0/capabilities（clipboard/files/linux/logs/settings/ui）/entryClass/provides（4 个工具、skill `deepdeep-hotel`、4 个工具卡槽位、page `main`） |
| `src/main/kotlin/.../HotelModels.kt` | 搜索/房型/订单/预订的 `@Serializable` 结构：房型含每晚均价 `averagePrice`、整段总价 `totalPrice`、早餐、取消政策、ratePlanId、房间信息；`HotelRoomGroup` 是卡片分组；`HotelPlanSelection` 是预订方案选择结果 |
| `src/main/kotlin/.../HotelJson.kt` | rgh 输出解析：去提示行后取完整 JSON；搜索 `hotelInformationList`、房型 `roomRatePlans`（兼容 `totalPrice` 与只有 `averagePrice` 的实时返回，按入离日期换算总价）、订单 `orderInfoList`/旧数组、锁价 `priceDetailsInfo`、下单 `bookingResult`；含房型分组、排序与方案选择纯函数 |
| `src/main/kotlin/.../HotelObservations.kt` | 模型侧完整可读文本：搜索酒店事实、逐方案房型事实（每晚/总价/早餐/取消/ID/日期/房间信息）、订单全字段、下单结果、确认弹窗文案与状态码映射 |
| `src/main/kotlin/.../HotelTools.kt` | 4 个工具定义与执行：参数校验、结果文本与 details、`hotel_book` 的方案选择（可选 rate_plan_id，多方案走原生单选 + 原生确认） |
| `src/main/kotlin/.../HotelCards.kt` | 工具卡：搜索列表、房型分组（最低总价 + 每晚均价 + 方案数 + 取消政策）、订单全字段、待支付订单；加载/空/失败态 |
| `src/main/kotlin/.../HotelCli.kt` | `rgh` 命令拼装（`HOME=/root`）、版本/登录/URL 解析 |
| `src/main/kotlin/.../HotelService.kt` | 执行 rgh 命令并解析；区分未安装/未登录/查询失败，锁价与下单失败带 RollingGo 返回原因 |
| `src/main/kotlin/.../HotelRuntime.kt` | 插件页状态：CLI 版本、登录状态、安装/登录/退出操作与阶段进度 |
| `src/main/kotlin/.../HotelPage.kt` | 插件自绘页：安装/更新、登录（打开授权链接）、退出、状态与错误重试 |
| `src/main/kotlin/.../HotelPlugin.kt` | 入口：availability / onEnable / uninstall / tools / skill / promptSections / ui |
| `src/main/kotlin/.../HotelContent.kt`、`HotelTexts.kt`、`HotelState.kt` | 技能文档、页面 zh/en 文案、快照模型 |
| `src/test/kotlin/...` | 解析（含真机 rgh 实时返回形态）、分组与最低价、方案选择、文本事实、订单全字段、CLI 命令、金额/晚数计算 |

## 要点

- RollingGo `rgh` 实时房型返回只有 `averagePrice`（每晚均价），没有 `totalPrice`：解析时按 `checkIn`/`checkOut` 的晚数换算整段总价，卡片与文本同时给每晚均价和总价；兼容旧版 `totalPrice`/`totalSalesRate` 与 `cancellationPolicies`。
- 房型按 `roomName` 归并：卡片一行一个房型，展示最低总价、每晚均价、方案数与最低价方案的取消政策；文本逐方案列出床型、每晚/总价、早餐、取消政策、ratePlanId、入离日期与房间信息。
- 预订是真实资金操作：先锁价再下单；`hotel_book` 需要用户明确确认酒店、房型、价格方案、日期和总价与联系人信息。同一房型有多个价格方案时必须让用户明确选择，不允许静默取第一个；可选 `rate_plan_id` 指定方案。
- 模型文本不出现“卡片”措辞，不展示 ratePlanId/referenceNo/hotelId 给用户；支付在 RollingGo 官方页面完成，插件不代替用户支付。
- 报价与取消政策以 RollingGo 实时返回为准；解析不到价格时显示“价格待确认”，不编造金额。
