# ride · 滴滴打车插件

模块名、插件 id 与安装路径均为 `ride`（`io.github.whywood.deepdeep.plugin.ride`），对外名称「滴滴打车」。
通过滴滴出行 MCP 提供地点搜索、价格预估、发单链接、创建/取消订单、订单状态与司机位置；回答区由 `ToolResultRenderer` 按工具渲染 Compose 卡片，Key 加密保存在本机，支付在滴滴内完成。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.3.1 / versionCode 9；compileOnly `plugin-api 1.4.0` 与 `design 0.11.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.3.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.3/capabilities（logs/network/settings/ui/secrets）/entryClass/provides（7 个工具、skill `deepdeep-ride`、page `main`） |
| `src/main/kotlin/.../RidePlugin.kt` | `DeepDeepPlugin` 入口：Key 迁移、工具/技能/页面贡献、7 个插件工具与 6 个 MCP 工具名的结果渲染器注册；技能 markdown 说明吸附点位、单位与限流 |
| `src/main/kotlin/.../DidiClient.kt` | 滴滴 MCP JSON-RPC 客户端：tools/list 探测、tools/call、生产/体验环境、错误分类与 Key 脱敏；`RideResponse.raw` 是拆掉 MCP 信封后的 content 原文，有 structuredContent 时附在其后；错误时是错误对象原文 |
| `src/main/kotlin/.../RideKeyStore.kt` | Key 存 `host.secrets()`（旧明文 settings 值迁移后删除） |
| `src/main/kotlin/.../RideTools.kt` | 7 个工具：参数校验、调用 MCP、1.4.0 返回协议（`deepdeep.raw`/`deepdeep.guidance`/`deepdeep.card`、估价 base 的实际上下车点）、创建/取消的原生确认与错误结果 |
| `src/main/kotlin/.../RidePayloads.kt` | MCP/原始 JSON 解析：地点（城市/地址/坐标）、估价（吸附后的上/下车点、距离/eta、全部车型与 routes 轨迹）、订单（状态/时间/司机/评分/位置）、司机位置、取消结果；状态码映射、金额/公里/分钟/时间格式化、details 与 base 构造、错误分类（限流/无订单/参数/鉴权/网络/上游）、错误脱敏 |
| `src/main/kotlin/.../RideGuidance.kt` | 各工具与错误类型的短 guidance：吸附点位、单位、必须询问车型、下一步与失败原因；不重复 raw 里已有的事实 |
| `src/main/kotlin/.../RideCards.kt` | 回答区版式：估价卡（实际上下车点、吸附提示、全程/接驾、路线示意、参考价/券后价/品类/接驾）、地点卡（点位分布图 + 按城市分组 + 全部行）、订单（状态、时间、司机、车牌、路线与单位）、司机位置（雷达 + 状态/时间）、链接与取消；各自的加载/空/失败态 |
| `src/main/kotlin/.../RideRouteMap.kt` | 纯 Canvas 静态图：轨迹折线、上下车点、地点标记与避让后的编号；高度/样式常量 |
| `src/main/kotlin/.../RideGeo.kt` | 地理纯函数（无 Compose，可单测）：经纬度字符串转 `RideGeoPoint`、轨迹抽样、等距投影、编号避让 |
| `src/main/kotlin/.../RideCardStyle.kt` | 出行感基础组件：入卡淡入、路线起终点连线（可换上车/下车标签）、价格块、车牌、标签、雷达、状态色、骨架与空态 |
| `src/main/kotlin/.../RidePage.kt` | 插件页：Key 配置/验证/更换、正式/体验环境切换、连接状态与错误重试；Key 编辑区提供「去滴滴开放平台获取 Key」跳转 `https://lbs.xiaojukeji.com/docs/ai-dev-tools/mcp-server-key.html` |
| `src/test/**` | 地点/车型/订单/司机位置解析、吸附判定与单位格式化、details 的 raw/guidance/card、轨迹解析/抽样/投影/编号避让、地点城市分组、错误分类、Key 迁移、创建/取消确认流程；网络与存储用 fake |

## 要点

- 返回协议：所有工具返回 `textResult("", details=...)`；`details` 同时带业务字段、`deepdeep.raw`（拆掉 MCP 信封的 content 原文，有 structuredContent 时附在其后）、`deepdeep.guidance`（短指导）与 `deepdeep.card`（仅 title，供宿主提示 present）。raw 存在时宿主会丢弃插件文本，模型看到的是 raw + guidance。
- 估价 details：`from`/`to` 是滴滴实际吸附后的上下车点（name/address/lat/lng）、`distanceKm`、`eta`、`items`（全部车型）、`routes`（全部轨迹点）；卡片顶部显示实际点位，与用户传入名称不同时明确提示「滴滴已吸附到该点位」。
- 单位：上游只有数字（如 `5.9`/`21`），文本与卡片统一格式化为 `5.9 公里`、`21 分钟`；guidance 说明 distanceKm 是公里、eta/duration 是分钟。
- 地图：估价用 `routes` 画轨迹折线；地点搜索画全部点位并给不重叠的编号，列表按城市分组且不截断（8/14 条全显示）。
- 卡片：车型默认预览 4 行，超过用 `DdExpandToggle` 在卡内展开全部；地点卡不截断；不使用纵向 `verticalScroll`，展开只让 Composable 变高；颜色/字号/间距/圆角全部取自 design token，深浅色都成立。
- 错误：保留上游原文 raw，guidance 按限流/无订单/参数/鉴权/网络/上游给可执行建议；限流明确说明「不是没有订单」，额度提示保持；错误卡从 `details.error`（kind/message）取短消息，不把 raw 塞进卡片。
- 状态：0 匹配中、1 司机已接单、2 司机已到达、4 行程中、5 订单完成、6 系统取消、7 用户取消、11 客服关闭、12 未能完成；3/8/9/10 是终态，优先显示上游 `statusText`，没有名称时显示「订单已结束」并保留状态码。
- 订单/司机卡时间：下单、行程开始/结束、状态更新时间都按本地时区格式化；司机卡显示状态、距离、ETA 与更新时间。
