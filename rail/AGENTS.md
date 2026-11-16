# rail · 12306 铁路插件

模块 `rail`（`io.github.whywood.deepdeep.plugin.rail`），对外名称「12306铁路」。通过 12306 余票与票价接口查询车次时刻、历时、经停站与席别余票，回答区用一张车票式卡片承载全部车次，购票在 12306 完成。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.2.0 / versionCode 10101；compileOnly `plugin-api 1.2.0`、`design 0.10.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：version 1.2.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.3.0/capabilities（logs/network/settings/ui）/entryClass/provides（`train_search`、`train_price`、工具卡槽位 `tool.card.content:train_search`、page `main`） |
| `src/main/assets/station_name.js` | 内置车站表（电报码、站名、城市、拼音、简拼） |
| `StationIndex.kt` | 车站解析：站名/去「站」/全拼/简拼/城市；同名车站返回候选列表，城市名优先匹配同名车站 |
| `TrainModels.kt` | `@Serializable` 车次、席别余票、票价、最近路线；`SeatStatus` 区分有票/无票/未开售/未返回/无此席别，`SeatAvailability.code` 保留 12306 席别代码 |
| `TrainService.kt` | 12306 会话：`leftTicket/init` 取 cookie，`queryG`→`query` 兜底、`c_url` 跟随、`queryTicketPrice` 票价；`RailRawLog` 记录每次数据请求原文；`parseTrainRows` 保留全部变体；`pickTrainForPrice` 按到达站优先选票价车次 |
| `TrainText.kt` | 模型侧完整文本：全部车次、全部席别状态、票价、数据时间与预订链接；中英文案；错误原文与建议 |
| `RailPlugin.kt` | 入口：availability / 两个工具 / 工具卡槽位 / 页面；工具结果把原始响应写入 `details["deepdeep.raw"]`（成功与失败都挂），搜索成功另写 `deepdeep.card.title` |
| `RailUi.kt` | 插件页（查询、最近路线、日期选择）与工具卡（车票式版式、跨站标签、全部席别、展开全部车次、加载/空/失败态） |
| `src/test/kotlin/**` | 响应解析、车站索引、席别状态、全部变体保留、票价车次选择、raw 记录与挂载、旧 payload 兼容 |

## 要点

- 工具文本不截断：`train_search` 列出当天全部车次，`train_price` 列出接口返回的全部席别票价；不再有「前 N 趟」「还有 N 条」这类截断文案。
- 同一车次在按城市查询时可能返回多个到达站变体，全部保留，不做静默合并；每行用自己的到达站与标签呈现。`train_price` 需要选一个区间时，优先取请求城市对应的到达站，其次取最晚到达的变体。
- 席别状态来自 12306 原始字段：数字或「有」为可售，「无」为无票，「*」为未开售，空值结合该车 `seat_types` 判断是「未返回」还是「无此席别」，「--」为无此席别。文本把无此席别归并到一行，卡片单独列出，余票与状态全部可见。
- 原始返回：搜索路径记录 `queryG`、`query` 兜底与 `c_url` 跟随的每个响应 body；票价路径在搜索原文后追加票价响应 body。每次响应带 `GET <url> [HTTP <status>]` 行，body 原样保留，不截断。成功结果的 details 带 `deepdeep.raw`，错误结果也带同一键；初始化会话页不进入原文。
- 卡片 id 由宿主追加，插件不自己拼「卡片 id」；`deepdeep.card.title` 用一行路线与趟数，例如「北京 → 上海 · 20 趟」。
- 卡片：车票式行（出发/到达时间大字、中间时长线、车次号），出发/到达站与查询城市不同时打站名标签；每行用 `FlowRow` 展示全部席别与余票状态，无此席别单独一行；默认预览 3 趟，超过时用 `DdExpandToggle` 展开全部车次；保留底部预订入口与加载/空/失败态。
- 颜色、字号、间距、圆角只取 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，深浅色都成立；文案走 `host.t(zh, en)`。
- 票价只来自 12306 票价接口；接口没有票价时明确说明票价暂不可查，不编造价格。
