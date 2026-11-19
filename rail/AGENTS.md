# rail · 12306 铁路插件

模块 `rail`（`io.github.whywood.deepdeep.plugin.rail`），对外名称「12306铁路」，当前 1.4.0。通过 12306 余票与票价接口查询车次时刻、历时、经停站与席别余票，回答区用一张按车次分组的车票卡承载全部到站组合与席别，购票在 12306 完成。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | 版本 1.4.0 / versionCode 10104；compileOnly `plugin-api 1.4.0`、`design 0.11.0`；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：version 1.4.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.3/capabilities（logs/network/settings/ui）/entryClass/provides（`train_search`、`train_price`、工具卡槽位 `tool.card.content:train_search` 与 `tool.card.content:train_price`、page `main`） |
| `src/main/assets/station_name.js` | 内置车站表（电报码、站名、城市、拼音、简拼） |
| `StationIndex.kt` | 车站解析：站名/去「站」/全拼/简拼/城市；同名车站返回候选列表，城市名优先匹配同名车站 |
| `TrainModels.kt` | `@Serializable` 车次、席别余票、票价、最近路线与车次分组；`SeatStatus` 区分有票/无票/未开售/未返回/无此席别，`SeatAvailability.code` 保留 12306 席别代码；`TrainOption.date` 原样保存上游行日期，`TrainPriceDetails` 带实际到站与站序号 |
| `TrainService.kt` | 12306 会话：`leftTicket/init` 取 cookie，`queryG`→`query` 兜底、`c_url` 跟随、`queryTicketPrice` 票价；`RailRawLog` 记录每次数据请求原文（只进插件日志）；`parseTrainRows` 保留全部变体与行日期；`RailTrainCache` 存最近一次搜索的 trains（key=出发/到达/日期，10 分钟）；`pickTrainForPrice` 按 train_no/站序号/到达站/最晚到达依次选车次 |
| `TrainText.kt` | 生产者完整文本：按车次分组，每行实质站对（跨站标「XX出发」「到XX」）、到达时刻与历时、全部席别（无此席别按车次汇总一次）、上游日期异常、数据时间与预订链接；票价固定席别顺序；中英文案、错误说明与下一步 guidance |
| `RailPlugin.kt` | 入口：availability / 两个工具 / 工具卡槽位 / 页面；结果统一 `textResult("", details)`，details 带 `deepdeep.raw`（生产者文本）、`deepdeep.guidance`、`deepdeep.card`（title + 全量 payload）；错误结果带 `deepdeep.raw`、`deepdeep.guidance` 与 `message`，不挂卡 |
| `RailUi.kt` | 插件页（查询、最近路线、日期选择）与两张工具卡：车次卡每条车次三行式（发车时刻/站名、车次·类型·历时居中、到达时刻/站名、席别文本行；跨日标「次日」、同车次其他变体与上游发车日期给提示，默认 4 组 + 展开）、票价卡（固定顺序席别票价行带分隔线、日期、更新时间与预订入口），含加载/空/失败态 |
| `src/test/kotlin/**` | 响应解析、车站索引、席别状态与措辞、车次分组与文本、上游日期、票价席别顺序、票价缓存与车次选择、raw/guidance/card payload、旧 payload 兼容、卡片格式（历时/跨日/日期） |

## 要点

- 模型文本不截断：`train_search` 按车次分组列出当天全部到站组合与席别，`train_price` 列出接口返回的全部席别票价；没有「前 N 趟」「还有 N 条」这类截断文案。
- 同一车次在按城市查询时可能返回多个到达站变体，全部保留，不做静默合并；每一行标注实际出发/到达站（与查询站不同时写「河源东出发」「到增城」）。`train_price` 需要选一个区间时，优先取请求城市对应的到达站，其次取最晚到达的变体；显式 `train_no`/`from_station_no`/`to_station_no` 可指定到站组合，匹配不到就报错而不是改价别的区间。
- 席别状态来自 12306 原始字段：数字或「有」为可售，「无」为无票，「*」为未开售，空值结合该车 `seat_types` 判断是「未返回」还是「无此席别」，「--」为无此席别。余票措辞统一为「有票 / N 张 / 无票 / 未开售」；无此席别在每个车次汇总一次，payload 仍全量。席别顺序固定：二等→一等→商务→优选一等→特等→软卧→硬卧→软座→硬座→高级软卧→动卧→无座。
- 上游行日期原样进 payload；与查询日期不一致时文本和卡片标注「上游日期 YYYY-MM-DD」，不当作错误修正。
- `deepdeep.raw` 是生产者生成的完整文本，不是 12306 原始 JSON；请求原文只进插件日志用于排错。成功结果的 details 另有 `deepdeep.card`（title 一行路线与趟数，payload 全量到站组合、席别、票价、日期与预订链接）。
- 卡片 id 与 `present` 指令由插件 SDK 生成；两张卡都需要非空 title。
- 卡片：车次卡每趟三行——第一行发车时刻与站名（左）、车次·类型·历时（中）、到达时刻与站名（右，跨日加「次日」），第二行席别用「席别 有/19 张/无」紧凑文本（有票取主色、无票弱化、未开售提示色），同车次其他到站/发车变体列「另有 …」、上游行日期与查询日不一致时用提示色标「发车日期 11-16」；默认 4 组、超过时用 `DdExpandToggle` 展开全部。票价卡按固定席别顺序列票价（行间分隔线、价格加粗），带日期、更新时间与底部预订入口。颜色、字号、间距、圆角只取 `deepDeepColors()`/`DdType`/`DdSpacing`/`DdShapes`，深浅色都成立；文案走 `host.t(zh, en)`。
- 同一路线 10 分钟内的 `train_price` 复用最近一次 `train_search` 的 trains 拿 `train_no`/站序号/`seat_types`，不再重复查询 `queryG`；缓存没有匹配才回退搜索。
- 票价只来自 12306 票价接口；接口没有票价时明确说明票价暂不可查，不编造价格。
