# ride · 滴滴打车插件

模块名、插件 id 与安装路径均为 `ride`（`io.github.whywood.deepdeep.plugin.ride`），对外名称「滴滴打车」。
通过滴滴出行 MCP 提供地点搜索、价格预估、发单链接、创建/取消订单、订单状态与司机位置；回答区由 `ToolResultRenderer` 按工具渲染 Compose 卡片，Key 加密保存在本机，支付在滴滴内完成。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.2.0 / versionCode 7；compileOnly `plugin-api 1.3.0` 与 `design 0.10.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.2.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.3.0/capabilities（logs/network/settings/ui/secrets）/entryClass/provides（7 个工具、skill `deepdeep-ride`、page `main`） |
| `src/main/kotlin/.../RidePlugin.kt` | `DeepDeepPlugin` 入口：Key 迁移、工具/技能/页面贡献、7 个插件工具与 6 个 MCP 工具名的结果渲染器注册；技能 markdown 说明各工具的完整返回 |
| `src/main/kotlin/.../DidiClient.kt` | 滴滴 MCP JSON-RPC 客户端：tools/list 探测、tools/call、生产/体验环境、完整响应原文、错误分类与 Key 脱敏 |
| `src/main/kotlin/.../RideKeyStore.kt` | Key 存 `host.secrets()`（旧明文 settings 值迁移后删除） |
| `src/main/kotlin/.../RideTools.kt` | 7 个工具：参数校验、调用 MCP、模型可读文本、details（`deepdeep.raw` 与 `deepdeep.card.title`）、创建/取消的原生确认 |
| `src/main/kotlin/.../RidePayloads.kt` | MCP/原始 JSON 解析：地点（城市/地址/坐标）、估价车型（ID/品类/币种/优惠/券后价/ETA）、订单（状态/时间/司机/评分/位置）、司机位置、取消结果；状态码映射、金额与时间格式化、details 解码、错误脱敏 |
| `src/main/kotlin/.../RideObservations.kt` | 模型可读观察文本纯函数（地点/估价/订单/司机位置），可注入 `RideTranslate` 供单测 |
| `src/main/kotlin/.../RideCards.kt` | 回答区版式：估价车型选择卡（价格大字 + 接驾标签 + 全部车型展开）、地点搜索（序号行 + 全部展开）、订单（状态、时间、司机、车牌、路线）、司机位置（雷达 + 状态/时间）、链接与取消；各自的加载/空/失败态 |
| `src/main/kotlin/.../RideCardStyle.kt` | 出行感基础组件：入卡淡入、路线起终点连线、价格块、车牌、标签、雷达、状态色、骨架与空态 |
| `src/main/kotlin/.../RidePage.kt` | 插件页：Key 配置/验证/更换、正式/体验环境切换、连接状态与错误重试 |
| `src/test/**` | 地点/车型/订单/司机位置解析、全量文本与错误原文、状态码映射、raw/card 挂载、Key 迁移、创建/取消确认流程；网络与存储用 fake |

## 要点

- 模型文本：地点列出接口本次返回的全部地点（名称、完整地址、城市、经纬度）；估价列出全部车型（名称、ID/品类、参考价、券后价、优惠、币种、ETA、距离、时长、手机尾号、traceId）；订单与司机位置列出状态、司机、车牌、电话、评分、距离、ETA、订单时间与更新时间。没有 `take` 上限，也不写「还有 N 条未显示」。
- raw 与卡片：每个工具（含创建/取消、错误结果）都在 `details["deepdeep.raw"]` 携带完整 MCP 响应 JSON 字符串，只脱敏 Key/token/secret；`details["deepdeep.card"].title` 是面向用户的一行标题。宿主负责追加完整返回与卡片 id，插件不自己拼。
- 错误：保留上游原文，只对 Key/token/secret 脱敏；按 `-32001/-32002/-32011/-32021/-32040/-32041` 等补一句可执行建议，限流明确说明不是没有订单。
- 状态：0 匹配中、1 司机已接单、2 司机已到达、4 行程中、5 订单完成、6 系统取消、7 用户取消、11 客服关闭、12 未能完成；3/8/9/10 是终态，优先显示上游 `statusText`，没有名称时显示「订单已结束」并保留状态码。
- 卡片：车型与地点默认预览 4 行，超过用 `DdExpandToggle` 在卡内展开全部；路线用圆点 + 连线，车牌用牌面样式，司机位置用雷达；不使用纵向 `verticalScroll`，展开只让 Composable 变高；颜色/字号/间距/圆角全部取自 design token，深浅色都成立。
- 订单/司机卡补时间与状态：下单、行程开始/结束、状态更新时间都按本地时区格式化；司机卡显示状态、距离、ETA 与更新时间。
- 版本 1.2.0 起不再有模型文本截断；`design 0.10.0` 提供 `DdExpandToggle`。
