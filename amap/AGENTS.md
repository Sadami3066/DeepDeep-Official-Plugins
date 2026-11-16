# amap · 高德地图插件

模块名、插件 id 与安装路径均为 `amap`（`io.github.whywood.deepdeep.plugin.amap`），对外名称「高德地图」。
通过高德 MCP（Web 服务 Key）查询地点、周边、逆地理、路线与天气；回答区由 `ToolResultRenderer` 按工具渲染四类 Compose 卡片，静态地图走 Web 服务静态图接口并缓存到插件数据目录。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.3.0 / versionCode 5；compileOnly `plugin-api 1.3.0` 与 `design 0.10.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.3.0/capabilities（logs/network/secrets/settings/ui）/entryClass/provides（8 个工具、skill `deepdeep-amap`、page `main`） |
| `src/main/kotlin/.../AmapPlugin.kt` | `DeepDeepPlugin` 入口：availability、Key 迁移、贡献工具/技能/页面、8 个工具结果渲染器注册；技能 markdown 说明各工具返回的完整事实 |
| `src/main/kotlin/.../AmapPage.kt` | 插件页：Key 配置/验证/更换、连接状态与错误重试 |
| `src/main/kotlin/.../AmapClient.kt` | 高德 MCP 客户端：initialize/tools list/call、SSE 与 JSON 信封、会话过期重连、按 inputSchema 探测分页参数、返回完整响应原文、错误分类与 Key 脱敏 |
| `src/main/kotlin/.../AmapKeyStore.kt` | Key 存 `host.secrets()`（旧明文 settings 值迁移后删除） |
| `src/main/kotlin/.../AmapModels.kt` | MCP/原始 JSON 解析：地点（电话/评分/商圈/照片/城市）、逆地理区县街道、全部路线备选与公交分段、全部天气预报、结果失败分类、`AmapUri`、details 解码与纯代码类型名过滤 |
| `src/main/kotlin/.../AmapTools.kt` | 8 个只读工具：参数校验、调用 MCP、模型观察文本、details（含全部行、全部路线、`map` 静态图规格、`deepdeep.raw` 与 `deepdeep.card.title`） |
| `src/main/kotlin/.../AmapObservations.kt` | 模型可读观察文本纯函数（地点/周边/逆地理/路线/天气）与分页说明，可注入 `AmapLocalizer` 供单测 |
| `src/main/kotlin/.../AmapText.kt` | `AmapLocalizer` 与 zh/en 文案、距离/耗时/路线标题格式化；`host.t/tf` 便捷扩展 |
| `src/main/kotlin/.../AmapMap.kt` | `AmapMapSpec`/标记/polyline 采样/自动缩放；静态图 URL 与参数哈希缓存名（不含 Key）；`AmapMapLoader` 通过 `host.network().download` 落到 `dataDir/map-cache`，校验图片 magic 并按失败类型降级 |
| `src/main/kotlin/.../AmapMapPreview.kt` | 16:9 地图块 Compose：加载/失败/权限降级状态、点图开高德、全屏查看；纯状态 `mapPreviewActions` 供单测 |
| `src/main/kotlin/.../AmapCards.kt` | 回答区四类版式：地点/周边（地图主视觉 + 全部地点行）、逆地理（地址大字 + 区县街道 + 位置图）、路线（最优/备选切换 + 全部时间轴分段）、天气（温度大数字 + 全部预报天）；各自的加载/空/失败态与入卡淡入位移 |
| `src/test/**` | MCP 信封与 schema 探测、地点/路线/天气解析、details 解码与 raw 挂载、工具端到端（fake host/network）、Key 存储迁移、观察文本与分页、静态图 URL/缓存/轮询、地图加载降级、卡片状态机与地图预览状态测试 |

## 要点

- 模型文本：地点给名称、类型、评分、商圈、城市、电话、地址、坐标、距离与照片 URL；路线给最优摘要、全部备选摘要和最优路线全部分段（含步骤耗时、公交步行距离/出入口/停靠站/首末班）；天气给全部预报天。纯数字 typecode 在解析、details 解码与观察文本三处都隐藏，raw 保留原值。
- 分页：搜索读上游 `count`；MCP `inputSchema` 有 `page_size`/`page_num` 时透传，否则文本说明返回条数与接口不支持翻页。
- raw 与卡片：读类工具的 `details["deepdeep.raw"]` 携带完整 MCP 响应 JSON 字符串，错误结果同样携带；`details["deepdeep.card"].title` 是面向用户的一行标题，宿主负责在文本末尾追加完整返回与卡片 id，插件不自己拼接。
- 错误：保留上游原文，只对 Key 做脱敏；文案保留分类建议。
- 地图视觉：地点/周边为整幅 16:9 静态图 hero（卡片裁圆角），地点名与数量压在图上胶囊里；逆地理为地址大字 + 内嵌位置图；路线与天气不显示地图。
- POI 行：序号圆点 + 名称 + 距离 + 类型标签 + 评分/商圈/城市 + 地址 + 电话；默认预览 3 行（有地图）或 5 行（无地图），更多用 `DdExpandToggle` 在卡内展开全部。
- 路线：模式胶囊 + 所选路线总距离，总耗时用大字号；多条路线用可横向滑动的“最优/备选”胶囊切换；时间轴串起起点、全部分段和终点，默认预览 3 段，展开后每段完整显示道路/距离/耗时或公交停靠站、出入口与首末班；底部动作打开高德导航 URI。
- 天气：首日高温做大数字，右侧白天/夜间天气、温度区间与风力；后续日期默认 4 天小条，超过 4 天用 `DdExpandToggle` 展开为逐日列表，每天含日期、天气、温度与风力；底部一句「预报仅供参考」。
- 卡片不使用纵向 `verticalScroll`，展开只让 Composable 变高，由页面滚动；颜色/字号/间距/圆角全部取自 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，浅色深色都成立。
