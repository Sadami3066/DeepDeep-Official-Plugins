# hotel · RollingGo 酒店插件

外部 `.ddp` 插件模块，当前 1.4.1：管理 RollingGo 官方 `rgh` CLI 的安装、更新、登录与更新检查，通过 7 个工具完成酒店搜索（预算/距离/标签/品牌筛选）、房型报价、待支付订单、订单列表、单笔订单详情、取消订单与筛选标签；房型与结果在回答区用卡片呈现，工具结果正文是完整可读的酒店事实，原始 CLI 输出挂在 `details["deepdeep.raw"]`。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 1.4.1、versionCode 22、Compose，plugin-api 1.3.1、design 0.11.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.4.1/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.0/capabilities（clipboard/files/images/linux/logs/settings/ui）/entryClass/provides（7 个工具、skill `deepdeep-hotel`、7 个工具卡槽位、page `main`） |
| `HotelModels.kt` | 搜索/房型/订单/订单详情/预订/取消/标签的 `@Serializable` 结构：搜索带筛选与 `sortedBy`、预算标注；房型含价格方案、取消政策、房间信息与图片；订单列表与订单详情全字段；`HotelCancelQuote`/`HotelCancelResult`/`HotelSearchTags`/`HotelGuest` |
| `HotelJson.kt` | rgh 输出解析：搜索 `hotelInformationList`、房型 `roomRatePlans`、订单 `orderList`（兼容旧形态）、`order-detail` 的模块化字段、锁价 `priceDetailsInfo`、下单 `bookingResult`、取消预检/确认、`hotel-tags`；房型按去掉括号营销后缀的名称归组、组内按总价排序；预算/距离排序与差异日期、金额、距离、标签等纯函数 |
| `HotelObservations.kt` | 模型侧 guidance：预算/筛选/排序说明、房型折算与需确认说明、订单列表与详情下一步、标签用法、取消预检与取消结果说明；订单/支付状态映射为中文/英文短语（不把内部状态码当用户文案） |
| `HotelPayloads.kt` | details 装配：`deepdeep.raw`、`deepdeep.guidance`、`deepdeep.card`；原始文本去 ANSI/提示行；预算标注；查询语句中的预算识别 |
| `HotelTools.kt` | 7 个工具定义与执行：搜索（query/place/place_type/check_in/nights/adults/size/star_ratings/max_price/distance_in_meter/required_tags/preferred_brands）、房型（hotel_id/name/日期/rooms/adults/cancel_policy/meal_type/child_count/child_age）、预订（多住客 guests、customer_request、联系人记忆）、订单（status/start_date/end_date）、订单详情、取消（两次调用：预检 + 确认，取消前原生二次确认）、标签；联系人首次预订后写入 `host.settings()`，下次省略时复用；`HotelReadyCache` 60 秒内复用 version/status 检查 |
| `HotelService.kt` | 执行 rgh 命令并解析；区分未安装/未登录/查询失败；锁价与下单失败带 RollingGo 返回原因；取消预检/确认、标签查询；`HotelReadyCache` 缓存就绪检查 |
| `HotelCli.kt` | `rgh` 命令拼装（`HOME=/root`、静默 dotenv）：搜索/房型/锁价/下单（含 `--guest`、`--customer-request`）/订单/订单详情/预取消/确认取消/标签/自定义更新；版本、状态、登录链接解析；版本比较与 npm 最新版本命令 |
| `HotelRuntime.kt` | 插件页状态与操作：安装、更新（`rgh update`）、登录/退出、检查；`HotelSnapshot` 与更新检查（npm view 最新版本，24 小时节流存 `host.settings()`）；安装/更新/登录后重置就绪缓存并重新检查 |
| `HotelPage.kt` | 插件自绘页：运行环境/酒店服务/账号状态摘要、可更新提示与更新动作、检查更新、安装/登录/退出、阶段进度与取消、错误重试 |
| `HotelPlugin.kt` | 入口：availability / onEnable / uninstall / tools / skill / promptSections / ui（7 个工具卡槽位） |
| `HotelCards.kt` | 工具卡：搜索（首店 hero + 全部行可展开，预算条、标签只在展开时显示、按起价/距离排序提示）、房型分组（营销后缀合并、全部方案可展开、全部取消政策、方案不展示 ID）、订单概要（全部订单可展开）、订单详情（全字段可展开）、待支付订单、取消预检/取消成功、筛选标签 |
| `src/test/kotlin/**` | 解析（含真机 rgh 实时返回形态）、筛选命令、标签、取消预检/确认、排序、房型合组、分组与最低价、方案选择、文本事实、订单全字段、订单详情全字段、raw 挂载、卡片 payload 全量、CLI 命令、版本比较 |
| `src/test/resources/fixtures/hotel-detail-live.json` | 真机 `rgh hotel-detail` 实时返回夹具 |

## 要点

- 搜索：预算 `max_price`（未传时从 query 识别「100 元以内」）作为上游筛选传给 rgh，同时保留超预算/价格待确认标注；`distance_in_meter`、`required_tags`、`preferred_brands` 走 rgh 的 `--distance-in-meter` / `--required-tag` / `--preferred-brand`。结果在预算存在时按起价、否则全部有距离时按距离排序，`sortedBy` 写入 payload 并由 guidance/卡片页脚说明；上游无翻页参数，`size` 上限 10。
- 标签：`hotel_tags` 按分类返回 rgh 支持的标签；模型只在用户明确要设施/品牌时查标签并传 `required_tags`/`preferred_brands`，不自己发明标签。
- 房型：按去掉括号营销后缀的 roomName 归组，组内按总价从低到高；同名房型的多个价格方案在同一组；方案 ID 只给模型（raw/guidance），卡片不展示；总价由均价按晚数换算时标注「按 N 晚折算」。
- 订单列表：`hotel_orders` 支持 `status` 与 `start_date`/`end_date`；列表无取消原因，确认号/电话/入住人/支付截止/免费取消截止用 `hotel_order_detail`。状态与支付状态映射为「待支付/已完成或已关闭/已取消」等人话，卡片不显示原始状态码。
- 预算 `max_price` 直接作为上游筛选条件；工具在卡片/guidance 明确“已按预算筛选，无超预算结果”，并说明 size 是返回上限、筛选后可能少于 size。上游若忽略预算返回超预算结果，仍会标注数量与最低价。
- 取消预检遇到 `canCancel=false` 时先查订单详情：订单已取消时返回“已取消、无需操作”，不再让用户联系客服或重试。
- 房型方案在卡片里按床型+餐食+价格合并为“类”，同类不同取消政策/需确认状态合并展示并标“N 个方案”；每类仍保留全部 rate_plan_id 在 raw/payload，预订时用原始 id。脏 bedType（双床房配“1 单人床”）会被丢弃，带 `|` 的 bed 描述会清洗成“大床 1.8米”。
- 标签 guidance 明确字段只有 `name/category`，不存在 `preferredTags/excludedTags`，避免模型照上游文档传错参数。
- 状态快照尚未加载时提示“插件已启用，如失败去插件页处理”，不再误报“尚未加载完成”。

- 取消是真实且不可撤销的写入：`hotel_cancel` 第一次只做预取消（查罚金、取消政策、bookingId/confirmId 凭据与有效期），把罚金告诉用户；用户明确同意后第二次带 `confirm=true`，插件再弹原生确认并调用 `confirm-cancel`。预检返回 `canCancel=false` 时引导联系 RollingGo 客服，不重试。
- 预订是真实资金操作：先锁价再下单；`hotel_book` 需要用户明确确认酒店、房型、价格方案、日期和总价；联系人名/姓/邮箱首次必填，成功后写入 `host.settings()` 供下次复用；多房间/多人传 `guests`（room/first_name/last_name/is_adult），特殊要求传 `customer_request`。同一房型多方案时优先 `rate_plan_id`，否则弹原生选择，不允许静默取第一个；插件不代替用户支付。
- 卡片：搜索卡首店 hero（图片、起价、星级/距离/地址），其余行紧凑列表；预算条只在有预算时出现；标签与设施只在展开时显示（减少营销噪音）；页脚显示日期、筛选与排序方式、打开 RollingGo。房型/订单/取消/标签卡同样只用 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，深浅色成立，文案走 `host.t`。
- 更新检查：插件页刷新时若距上次检查超过 24 小时，用 `npm view @rollinggo/hotel version` 查最新版（结果存 `host.settings()`）；有更新显示版本与「更新」动作，更新走 `rgh update`（会清 rgh 版本缓存），不退出登录。手动「检查更新」同样可用。
- 工具调用前就绪检查做 60 秒缓存（`HotelReadyCache`），避免每次调用都执行 version+status；登录/退出/安装/更新/卸载后失效。
- `deepdeep.raw` 是 rgh 完整 stdout（去 ANSI 与 dotenv 提示），请求细节只进插件日志；原始数据只用于模型与排错，卡片只展示用户需要的信息。
