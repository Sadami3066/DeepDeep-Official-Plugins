# amap · 高德地图插件

模块名、插件 id 与安装路径均为 `amap`（`io.github.whywood.deepdeep.plugin.amap`），对外名称「高德地图」。
通过高德 MCP（Web 服务 Key）查询地点、周边、逆地理、路线与天气；回答区由 `ToolResultRenderer` 按工具渲染 Compose 卡片，静态地图走 Web 服务静态图接口并缓存到插件数据目录。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.5.3 / versionCode 11；compileOnly `plugin-api 1.3.1` 与 `design 0.11.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.0/capabilities（logs/network/secrets/settings/ui/images）/entryClass/provides（8 个工具、skill `deepdeep-amap`、page `main`） |
| `src/main/kotlin/.../AmapPlugin.kt` | `DeepDeepPlugin` 入口：availability、Key 迁移、贡献工具/技能/页面、8 个工具结果渲染器注册；技能 markdown 说明各工具的真实边界 |
| `src/main/kotlin/.../AmapPage.kt` | 插件页：Key 配置/验证/更换、连接状态与错误重试；Key 编辑区提供「去高德开放平台获取 Key」跳转 `https://lbs.amap.com/api/webservice/create-project-and-key` |
| `src/main/kotlin/.../AmapClient.kt` | 高德 MCP 客户端：initialize/tools list/call、SSE 与 JSON 信封、会话过期重连、拆信封后的 raw、HTTP/JSON-RPC/上游错误分类、Key 脱敏；插件内共用一个实例并串行调用 |
| `src/main/kotlin/.../AmapKeyStore.kt` | Key 存 `host.secrets()`（旧明文 settings 值迁移后删除） |
| `src/main/kotlin/.../AmapModels.kt` | MCP/原始 JSON 解析：地点、逆地理、全部路线备选与公交线路、天气、失败分类、`AmapUri`、details 解码、球面距离 |
| `src/main/kotlin/.../AmapPlaceTypes.kt` | 高德 POI typecode → 中文类型标签表 |
| `src/main/kotlin/.../AmapTools.kt` | 8 个只读工具：参数校验、调用 MCP、`textResult("", details)`（raw/guidance/card 标题 + 全量卡片 payload）、错误 guidance |
| `src/main/kotlin/.../AmapGuidance.kt` | 成功与失败场景的短 guidance（条数、不能翻页、无坐标、区县精度、无轨迹、错误类型） |
| `src/main/kotlin/.../AmapText.kt` | `AmapLocalizer` 与 zh/en 文案、距离/耗时/路线标题格式化；`host.t/tf` 便捷扩展 |
| `src/main/kotlin/.../AmapMap.kt` | `AmapMapSpec`/标记/polyline 采样/自动缩放；静态图 URL 与参数哈希缓存名（不含 Key）；`AmapMapLoader` 通过 `host.network().download` 落到 `dataDir/map-cache`，校验图片 magic 并按失败类型降级 |
| `src/main/kotlin/.../AmapMapPreview.kt` | 16:9 地图块 Compose：加载/失败/权限降级状态、点图开高德、全屏查看；纯状态 `mapPreviewActions` 供单测 |
| `src/main/kotlin/.../AmapCards.kt` | 地点/周边、逆地理版式，以及共用的加载/失败/打开高德组件 |
| `src/main/kotlin/.../AmapRouteCards.kt` | 路线卡：端点地图 + 备选切换 + 驾车/步行/骑行步骤时间轴 + 公交换乘时间轴 |
| `src/main/kotlin/.../AmapWeatherCards.kt` | 天气卡：白天/夜间分栏、首日突出、后续日期展开 |
| `src/test/**` | MCP 信封与 raw、地点/路线/天气解析、details 解码、工具端到端（fake host/network）、错误分类、Key 存储迁移、静态图 URL/缓存/降级、卡片阶段与路线时间轴 |

## 要点

- 返回协议：成功结果为 `textResult("", details=…)`，`details["deepdeep.raw"]` 是拆掉 MCP 信封后的 `result.content[].text` 原文（有 `structuredContent` 时附在其后），`details["deepdeep.guidance"]` 是短指导，`details["deepdeep.card"]` 只有面向用户的一行标题。错误结果同样带 raw 与 guidance，不出卡片。
- 坐标由调用方给出：周边与路线的中心点/起终点都是经纬度；工具描述只陈述上游能返回什么、不规定调用流程。
- 工具能力与描述一致：地点搜索与周边单次返回数量有限、不支持翻页，结果只有 id/名称/地址/typecode/photo；逆地理只到区县；路线不含轨迹坐标串，驾车可能只有 1 条备选；天气天数由上游决定。
- 地点卡：按上游顺序展示，预览 3/5 条 +「展开全部 N 处」；类型标签只取第一段；整行点击按名称打开高德搜索。
- 周边卡：地图以中心点定位（POI 不带坐标时不画假点）；展示前不做额外上游调用。
- 路线卡：`routeDetails` 自带 `routeMapSpec`，用 `AmapMapPreview` 画端点与上游返回的 polyline，点图/「在高德地图中打开」走 `navigationUrl`。
- 错误分类：按 HTTP 状态、JSON-RPC code 和上游 `info`/`message` 分为 Key、额度、参数、服务、网络、协议、未知；上游原文进 raw，guidance 给可操作动作；只有传输异常才归为网络。
- 卡片 payload 全量：地点含 id/photo/photos/typecode/中文 typeName；路线含全部 paths 和每条 transit 的全部 lines/options/上下车站/步行距离；天气含 date/week/昼夜天气/温度/风向风力/浮点温度。
- 卡片不使用纵向 `verticalScroll`，展开只让 Composable 变高，由页面滚动；颜色/字号/间距/圆角全部取自 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，浅色深色都成立。
