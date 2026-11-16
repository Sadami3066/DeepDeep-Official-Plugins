# micloud · 小米云服务外部插件

DeepDeep 官方小米云插件，打成外部 `.ddp`：WebView 登录 i.mi.com 拿 cookie 后加密保存，用 `mi_cloud_*` 工具读取账号、短信、联系人、相册与录音，读写笔记与待办；所有开关与登录操作都在插件自绘页面完成。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `plugin-api 1.3.1` 与 `design 0.11.0`，不引用宿主内部类型（`R.string`、DataStore、`Activity`、`AppGraph` 等一律不用）。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.7.4、versionCode 17、Compose）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.7.4/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.0/capabilities（notifications/secrets/settings/ui/images）/entryClass/icon/provides（7 个工具、skills `deepdeep-micloud`、pages `main`；页面内容直接挂在 `PluginPage.content`，不需要槽位） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的小米图标 |
| `src/main/kotlin/.../MiCloudPlugin.kt` | 入口：availability/uninstall/onEnable/onDisable/skills/promptSections/contributions/ui；`MiCloudContent` 持有各组件与登录态缓存；工具卡渲染器按工具名分发 |
| `.../MiCloudPage.kt` | 插件页面 Compose：标题与用途、登录摘要卡（账号/连接）+ 刷新与退出的次级文字操作、能力开关分组、就地提示与重试、危险操作确认；登录是独立子页，`DdWebView` + `BackHandler`/`canGoBack` 返回处理与淡入滑动过渡 |
| `.../MiCloudStrings.kt` | `PluginHost.t(zh, en)` / `tf(zh, en, vararg args)` 中英文成对取文案 |
| `.../MiCloudPreferences.kt` | 能力开关与 `MiCloudCapabilities`，持久化到 `host.settings()`（旧 `capabilities.json` 首次读入后迁移删除） |
| `.../MiCloudCookieStore.kt` | cookie 加密存取（`host.secrets()`，按插件隔离） |
| `.../MiCloudSession.kt` | 登录态缓存（SIGNED_OUT/VERIFIED/EXPIRED/UNVERIFIED）与 TTL、cookie 指纹失效 |
| `.../MiCloudClient.kt` | 只读 HTTP 客户端：带 cookie 请求 i.mi.com、401/403 判定失效、返回完整响应 body 与 JSON、文件下载 |
| `.../MiCloudWebWriter.kt` | 写操作走隐藏 WebView 的 JS fetch（复刻浏览器环境），表单 POST 与失败判定；登录 cookie 清理 |
| `.../MiCloudParsers.kt` | profile、cookie 与鉴权失败判定 |
| `.../MiCloudToolSupport.kt` | 工具公共设施：参数校验、`textResult("", details=…)` 三键（`deepdeep.raw`/`deepdeep.guidance`/`deepdeep.card`）、错误到明确 guidance 的映射、路径拼接、写响应解析；`cursorValue` 把字符串或对象（如待办 `syncToken`）编成游标字符串 |
| `.../MiCloudTexts.kt` | 模型可见 guidance（纯函数，`MiCloudText` 中英成对）：分页/游标/空结果/重复待办/raw 噪音提示；`notePlainText` 把笔记 XML `<text indent=...>` 转纯文本、`formatBytes`/`formatShortTime`/`formatMonthTime`、能力未开启提示 |
| `.../MiCloudCardModels.kt` | 卡片 DTO 与纯解析：账号/空间（`Gift`/`Buy` → `gift`/`paid` 标签与 `expireTime`）、笔记（标题取 `subject` → `extraInfo.title` → 首行；无标题时首行升为标题、正文去掉该行）、待办（真实 `contentJson.entity` 的 content/plainText/is_finish/remindTime/lastModifiedTime，兼容顶层与字符串形态）、文件/短信/联系人/相册；每行保留 `source` 原始字段，payload 携带游标/syncToken/watermark/offset；相册无名兜底与录音名清洗（去尾部 `_<数字>` 串） |
| `.../MiCloudCards.kt` | 工具结果卡片渲染（`ToolResultRenderer`）：从 `deepdeep.card.payload` 解码；账号/配额、笔记、待办、录音、短信、联系人、相册；待办按内容合并重复项并标 `×N`、联系人副标题用电话/公司、空间卡无已用量时不画空环并显示赠送/实付与到期、相册用封面/数量/最近更新、相册/录音/联系人计数用对应用量词；相册用 `DdRemoteImage` + `host.images()` 加载首图；加载、空、错误三态 |
| `.../MiCloudTools.kt` | 按能力开关组装 `mi_cloud_*` 工具列表（含 account）；账号/空间卡片与 raw |
| `.../MiCloudNotesTools.kt` | `mi_cloud_notes`：列表、正文、新建、修改、删除；raw 保持上游原样，卡片标题回退到 `extraInfo.title`/首行；删除支持 `purge` 参数，语义以服务端为准 |
| `.../MiCloudTodoTools.kt` | `mi_cloud_todo`：列表、新建、修改（含完成态）、删除；列表一次返回全部，重复项合并并保留 `duplicateIds`，guidance 说明增量同步没有下一页 |
| `.../MiCloudMessagesTools.kt` | `mi_cloud_sms`：短信会话与通话记录列表（只读）；解开 `entry` 包装，raw 保持上游原样，卡片展示号码/摘要/时间/未读 |
| `.../MiCloudContactsTools.kt` | `mi_cloud_contacts`：联系人列表与搜索（只读）；解析 `content` map，raw 保持上游全量，search 在本地过滤并给出匹配数，guidance 说明增量同步没有下一页，卡片展示电话与分组 |
| `.../MiCloudMediaTools.kt` | `mi_cloud_gallery` 相册/媒体列表与 `mi_cloud_recordings` 录音列表、下载；相册按项目数倒序、无名字幕兜底，媒体列表走真实端点（albumId/pageNum/pageSize/日期范围）；录音解析 `create_time` 兜底并清洗展示名；下载 guidance 带绝对路径 |
| `src/test/kotlin/**` | 解析、guidance、卡片 DTO 与真实手机响应形态的 fixture：待办 `contentJson.entity`、`syncToken` 对象、笔记 `extraInfo.title`、空间 `Gift`/`Buy` + `expireTime`、录音内部 id、重复待办、联系人分组 |

## 要点

- 版本 2.7.4（`plugin-api 1.3.1`，`minHostVersion 1.4.0`）：所有工具返回 `textResult("", details=…)`，模型正文由宿主用 `deepdeep.raw`（完整响应 body，失败时保留错误输出）替换；`deepdeep.guidance` 只写分页（notes `syncTag`、recordings `offset`；todo/contacts 是增量同步、明确没有下一页）、空结果、重复待办与失败说明，并提示不要把签名链接/拼音索引/内部 id 复述给用户。
- 七类工具都有卡片：账号/空间、笔记、待办、录音，以及短信（号码/摘要/时间/未读）、联系人（姓名/电话/分组）、相册（名称/数量/首图）。`deepdeep.card` 只写 `title` 与 `payload`，不写 `customType`；payload 每行带 `source` 原始字段并保留游标、`syncToken`、watermark，渲染器从 `deepdeep.card.payload` 解码并对旧版扁平 details 兜底。相册首图用 `DdRemoteImage` + `host.images()`，不落盘。列表预览 3–5 行，`DdExpandToggle` 展开全部。
- 待办解析以真机响应为准：记录是 `contentJson.entity`（content/plainText/is_finish/remindTime/lastModifiedTime），不是顶层 contentJson。待办接口是增量同步，不是分页：一次返回本次全部可读记录，`hasMore`/`syncToken` 不能当下一页；工具只接收 `limit`，不再暴露 cursor，guidance 明确“没有下一页”。重复内容按内容+完成态合并，`duplicateIds` 保留原始 id 给写操作，卡片标 `×N` 并显示“合并 M 条重复、共 U 件不同事项”。
- 笔记标题读取顺序：`subject` → `extraInfo` JSON 的 `title` → 摘要首行；用首行当标题时摘要去掉首行再展示，避免标题与摘要重复。
- 便签列表 `deepdeep.raw` 保持上游完整响应（`snippet` 是 `<text indent>` XML），卡片负责清洗与标题回退，回答时用卡片内容而不是贴 raw。
- 便签删除保留上游 `purge` 参数（默认 true）；实际是软删还是物理删由服务端决定，工具不替上游下结论。便签 `tag` 是版本号/ETag，每次写会变，更新/删除前重新读详情取最新 tag。
- 空间配额按真机形态解析：`category` 为 `Gift` 时映射赠送空间、`Buy`/`FirstLevel` 映射实付空间；`expireTime` 显示“有效期至 yyyy-MM”；上游没有已用量时不画用量环，只显示总量与剩余空间。
- 联系人接口也是增量同步（`/contacts/{id}/initdata`），没有下一页：工具只保留 list/search，`syncTag/syncIgnoreTag` 参数不再暴露；search 在本地过滤本次拉到的联系人并报告匹配数，raw 保持上游全量。
- 相册：无名相册（系统相册 id 1/2 没有 name 字段）显示“系统相册”兜底，不再出现“未命名相册”；工具按项目数倒序返回；录音名去掉尾部 `_id` 串，时间从 `create_time` 兜底。
- 媒体列表走真实端点 `/gallery/user/galleries?startDate&endDate&pageNum&pageSize&albumId`（之前只带 ts 会超时）；albumId 默认 1（相机），支持 pageNum/limit 翻页。
- 短信 `syncTag/syncThreadTag` 是增量/翻页共用标记：guidance 给出参数但要求“返回空即停止，不要重复翻页”，不承诺一定有下一页。
- 待办和联系人 guidance 明确“增量同步没有下一页”；待办一次返回全部并合并重复（`duplicateIds` 保留 id），联系人在本地过滤。
- 写操作只支持笔记与待办：新建、修改、删除前必须先向用户确认，并说明会直接写入小米云账号；删除、清空、批量修改不可逆，必须二次确认。
- 读走 `MiCloudClient`（HttpURLConnection + cookie），写（笔记/待办）必须走 `MiCloudWebWriter` 的隐藏 WebView JS fetch——小米云写接口校验浏览器环境，直接 HTTP POST 会失败。
- skill/prompt 明确“工具结果带卡片 id 时用 present 交付卡片，正文只写结论与下一步”，与列表/账号/空间卡的实际槽位一致。
- 工具按登录态与能力开关双重门控：只有登录有效（VERIFIED/UNVERIFIED）才注册 `mi_cloud_*` 工具，未登录/登录失效时不注入；登录、退出、登录失效都会调用 `host.requestContributionsRefresh()` 重新物化。
- 登录 = 插件页面内的独立子页：`DdWebView` 加载 `https://i.mi.com`，登录后点「完成登录」读取 `CookieManager` 的 cookie，经 `MiCloudClient.profile()` 校验后存入 `host.secrets()`；只有这一条完成路径。系统返回与左上角返回先在网页内回退（`canGoBack`），到根页才关闭子页。
- 登录态由 `MiCloudSession` 缓存（验证 60s TTL、失效 10 分钟内不重复打接口）；页面打开与宿主刷新时异步探测，工具收到 401/403 立即标记失效。
- 页面结构：顶部标题 + 一句用途 → 摘要卡（`DdStatusRow` × 账号/连接，行间 `DdHairline`；刷新与退出是次级 `DdTextAction`）→ 未登录/登录失效只在摘要卡下给一个主操作 `DdPrimaryButton` → 失败、离线、失效就地用 `DdNoticeBar` 说明并可重试 → 能力开关分「笔记与待办」「个人数据」两组 → 底部一句说明；不展示 cookie、接口或内部字段。
- 录音下载落到 `host.dataDir/recordings`，工具返回绝对路径；相册只列不下载。
- 短信、联系人、录音、相册只按开关和用户要求读取；401/403 统一标记会话过期并提示到插件页面重新登录，不要重试刷接口。
- 所有用户可见文案走 `host.t`/`host.tf`（中英成对），不引用宿主资源；页面不注册 Activity，登录 WebView 只存在于插件页面。登录成功发稳定 id 通知，退出登录前用 `host.ui().confirm` 确认；失败一律就地提示并可重试，snackbar 只做成功反馈。
