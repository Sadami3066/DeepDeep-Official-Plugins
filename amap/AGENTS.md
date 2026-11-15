# amap · 高德地图插件

模块名、插件 id 与安装路径均为 `amap`（`io.github.whywood.deepdeep.plugin.amap`），对外名称「高德地图」。
通过高德 MCP（Web 服务 Key）查询地点、周边、逆地理、路线与天气；回答区由 `ToolResultRenderer` 按工具渲染四类 Compose 卡片，静态地图走 Web 服务静态图接口并缓存到插件数据目录。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.2.0 / versionCode 4；compileOnly `plugin-api 1.3.0` 与 `design 0.9.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.1.0/capabilities（logs/network/secrets/settings/ui）/entryClass/provides（8 个工具、skill `deepdeep-amap`、page `main`） |
| `src/main/kotlin/.../AmapPlugin.kt` | `DeepDeepPlugin` 入口：availability、Key 迁移、贡献工具/技能/页面、8 个工具结果渲染器注册；技能 markdown 说明各工具返回的完整事实 |
| `src/main/kotlin/.../AmapPage.kt` | 插件页：Key 配置/验证/更换、连接状态与错误重试 |
| `src/main/kotlin/.../AmapClient.kt` | 高德 MCP 客户端：initialize/tools list/call、SSE 与 JSON 信封、会话过期重连、错误分类与 Key 脱敏 |
| `src/main/kotlin/.../AmapKeyStore.kt` | Key 存 `host.secrets()`（旧明文 settings 值迁移后删除） |
| `src/main/kotlin/.../AmapModels.kt` | MCP/原始 JSON 解析：地点/逆地理/路线步骤与公交段/天气、结果失败分类、`AmapUri`、details 解码与纯代码类型名过滤 |
| `src/main/kotlin/.../AmapTools.kt` | 8 个只读工具：参数校验、调用 MCP、模型观察文本、details（含 `map` 静态图规格） |
| `src/main/kotlin/.../AmapObservations.kt` | 模型可读观察文本纯函数（地点/周边/逆地理/路线/天气），可注入 `AmapLocalizer` 供单测 |
| `src/main/kotlin/.../AmapText.kt` | `AmapLocalizer` 与 zh/en 文案、距离/耗时/路线标题格式化；`host.t/tf` 便捷扩展 |
| `src/main/kotlin/.../AmapMap.kt` | `AmapMapSpec`/标记/polyline 采样/自动缩放；静态图 URL 与参数哈希缓存名（不含 Key）；`AmapMapLoader` 通过 `host.network().download` 落到 `dataDir/map-cache`，校验图片 magic 并按失败类型降级 |
| `src/main/kotlin/.../AmapMapPreview.kt` | 16:9 地图块 Compose：加载/失败/权限降级状态、点图开高德、全屏查看；纯状态 `mapPreviewActions` 供单测 |
| `src/main/kotlin/.../AmapCards.kt` | 回答区四类版式：地点/周边（地图主视觉 + 编号地点行）、逆地理（地址大字 + 位置图）、路线（交通时间轴 + 总耗时）、天气（温度大数字 + 未来几天条）；各自的加载/空/失败态与入卡淡入位移 |
| `src/test/**` | MCP 信封、地点/路线/天气解析、details 解码、Key 存储迁移、观察文本、静态图 URL/缓存/轮询、地图加载降级、卡片状态机与地图预览状态测试；网络用 fake |

## 要点

- 地图视觉：地点/周边为整幅 16:9 静态图 hero（卡片裁圆角），地点名与数量压在图上胶囊里；逆地理为地址大字 + 内嵌位置图；路线与天气不显示地图。
- POI 行：序号圆点 + 名称 + 距离 + 可读类型标签 + 地址，点击单品用 `AmapUri.marker` 打开高德；整卡预览最多 3 行（有地图）或 5 行（无地图），余量用「还有 N 条」。
- 路线：模式胶囊 + 总距离，总耗时用大字号；时间轴从起点到终点串起分段（驾车/步行/骑行取步骤，公交取换乘线路与步行距离），最多 4 段并用「还有 N 段」提示；底部动作打开高德导航 URI。
- 天气：首日高温做大数字，右侧白天/夜间天气、温度区间与风力；后面最多 4 天的小条显示日期、天气与温度；底部一句「预报仅供参考」。
- 观察文本与卡片分层：工具文本始终给完整事实（含坐标、距离、耗时、步骤、天气数值），卡片只做版式，不复述开发术语；类型名是纯数字编码时在解析、details 解码与观察文本三处都隐藏。
- 版本 1.2.0 起卡片不再共用「标题行 + 分隔线 + 列表」外壳；颜色/字号/间距/圆角全部取自 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，签名线条用 `Canvas` + token 颜色，浅色深色都成立。
