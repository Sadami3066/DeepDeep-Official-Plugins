# mail · 邮箱外部插件

DeepDeep 官方邮箱插件，当前 1.6.2，打成外部 `.ddp`：不依赖 CLI，用 JVM 网络栈直连 IMAP/SMTP；提供 `mail_search`、`mail_read`、`mail_save_attachment`、`mail_draft`、`mail_send`、`mail_mark`、`mail_archive` 七个工具与插件自绘页面。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api`（1.2.0 与 1.3.0）与 `design` 0.10.0，不引用宿主内部类型；唯一 `implementation` 依赖是 `org.apache.james:apache-mime4j-core:0.8.15`。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 1.6.2、versionCode 12、plugin-api、design 0.10.0、Compose、mime4j 与 JVM 单测依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.6.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.3.0/capabilities（含 `chat`、`media`、`background`）/entryClass/provides（pages `mail`、uiSlots `chat.message:mail.search`、`chat.message:mail.read`、`chat.message:mail.draft`） |
| `src/main/kotlin/.../MailPlugin.kt` | 入口：availability / uninstall / skills / promptSections / contributions（工具与 hooks）/ ui；提示词正文为同文件的 `mailPromptBody` |
| `.../MailRuntime.kt` | 插件页与工具共用的运行状态：账号、文件夹、消息、草稿、同步、发送、附件与状态快照；搜索按账号、文件夹与关键词（主题/发件人/收件人/预览）过滤 |
| `.../MailModels.kt`、`.../MailRepository.kt`、`.../store/`、`.../sync/` | 邮箱数据模型（含头字段与正文截断标记）、仓库读接口、`host.settings()` 下的账号索引/文件夹索引/草稿存储、后台增量同步 |
| `.../transport/` | IMAP/SMTP 客户端、TLS、编码解析、错误本地化 |
| `.../mime/` | MIME 解析/组装、RFC 2047、HTML 转纯文本（`HtmlText.preview`）；解析保留全部 `text/plain` 与 `text/html` part，分别拼接 |
| `src/main/kotlin/.../tools/MailTools.kt` | 七个工具：搜索（folder/offset）、读信（完整头字段、纯文本正文、HTML 原文、全部附件）、保存附件、起草、发送、标记、归档；工具结果文本列出完整事实；卡片数据写进结果 `details` |
| `.../tools/MailCardPayloads.kt` | 卡片 payload（`MailSearchCardPayload`/`MailReadCardPayload`/`MailDraftCardPayload` 与行模型）与 `encodeCardPayload()`；新字段都有默认值，旧会话 payload 仍可解码 |
| `.../tools/MailHooks.kt` | 后台同步与通知动作 hooks |
| `.../ui/MailChatCardChrome.kt` | 回答区卡片的共享外观件：卡片表面（入场淡入+上移 200ms，尊重 `LocalDdMotionEnabled`）、虚线分隔、信封线稿、上下文/收件人 chip、附件胶囊、空信封插画、可点行、打开邮箱/邮件/草稿的 host 动作、payload 解码 |
| `.../ui/MailChatCards.kt` | `MailSearchChatCard`（信封列表：全部行 + `DdExpandToggle`，已发送样式的收件人与标记）与 `MailReadChatCard`（信纸：完整正文折叠展开、全部附件、已发送样式）：各自空/失败态 |
| `.../ui/MailDraftChatCard.kt` | `MailDraftChatCard`（写信预览）：草稿状态条、全部收件人/抄送 chip、主题、正文面板、全部附件、编辑/发送与发送中/失败/已发送/草稿缺失态 |
| `.../ui/MailPage.kt`、`.../ui/MailThreadPage.kt`、`.../ui/MailInboxList.kt` 等 | 插件页（收件箱/已发送切换）、会话页、邮件列表与写信/添加账号 sheet |
| `src/test/kotlin/...` | IMAP/SMTP 协议、MIME、同步逻辑、工具文本与卡片 details、prompt 文案、HTML 清洗单测；`FakePluginHost` 为宿主替身 |

## 工具返回

- `mail_search`：`query` 匹配主题、发件人、收件人与预览；`folder` 取 `all`/`inbox`/`sent`/`drafts`/`archive`，默认 `all`；`offset` 继续翻页，`limit` 上限 50。文本逐封列出 id、主题、文件夹、账户、发件人、收件人、时间、已读/星标/附件/大小、会话与完整预览；返回总数与当前页，还有更多时写「匹配 N 封，返回 M 封，可用 offset 继续（offset=…）」。
- `mail_read`：完整纯文本正文（多种 text part 按序拼接）、`HTML 原文`（没有纯文本或 HTML 更完整时单独成段）、全部已解析头字段（Message-ID/Reply-To/In-Reply-To/References/List-*）、全部附件（名称、类型、大小、id、内嵌标记与 Content-ID）。正文超过单段 1 MiB 或总计 2 MiB 时写明已截断；附件只给元数据，二进制不内联，保存走 `mail_save_attachment`。
- 卡片 id 由宿主统一追加，插件不写「卡片 id」。

## 回答区卡片

- 卡片投递：`mailPromptBody` 明确要求“工具结果带卡片 id 时用 `present` 交付卡片，正文只写结论与下一步，不复述卡片内容”；真机暴露过 prompt 未提示导致卡片不上屏的问题，1.6.1 已修。
- 投递：`mail_search`/`mail_read`/`mail_draft` 把卡片写进工具结果 `details` 的 `deepdeep.card`：`{"customType": "mail.search|mail.read|mail.draft", "title": 摘要, "payload": MailCardPayloads 的 JSON}`；**不调用** `host.chat().postCustomMessage`，也不自己拼卡片 id。槽位与 payload 兼容旧会话。
- 搜索是信封列表：未读圆点 + 信封线稿 + 发件人（未读加粗）+ 主题 + 日期右对齐，行间虚线；收起显示 5 封，`DdExpandToggle` 展开全部，底部「打开邮箱」与总数。已发送的行改为显示收件人、箭头线稿与「已发送」chip。
- 读信是信纸：发件人头像/名字/地址与时间、双细线信头、主题、完整正文（默认 4 行，`DdExpandToggle` 展开全文）、全部附件胶囊；已发送的信显示收件人与「已发送」；未读显示圆点，底部「打开邮件」。
- 草稿是写信预览：`草稿 · 未发送` 状态条 + 账户、全部收件人/抄送 chip、主题、正文面板、全部附件胶囊，底部「编辑」与「发送」；发送中/失败/已发送/草稿已删除都有对应状态。
- 空搜索显示空信封插画 + 一句提示 + 筛选 chip + 打开邮箱；读信/草稿不可用给一句人话与打开邮箱出口。
- 颜色全部来自 `deepDeepColors()`，字号优先 `DdType`，间距走 `DdSpacing`；浅色/深色两套都成立；卡片整体入场淡入+上移 200ms，行内不做逐行动画。

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :mail:testDebugUnitTest :mail:verifyPlugin`，产物 `mail/build/plugin/mail.ddp`。
- 插件页顶部在「收件箱 / 已发送」之间切换；SENT 由同步映射，SMTP 发送成功后把邮件 append 到 Sent。已发送列表同样显示收件人。
- 用户可见文案中英成对（`host.t`/`host.tf`），卡片不出现 App 名、插件名、「卡片」「结果」这类开发词；卡片 id 行由宿主追加。
- IMAP 登录后按 RFC 2971 发 `ID` 声明客户端（163/126 要求，否则 `SELECT` 返回 `Unsafe Login`）；`ID` 失败不影响连接，错误文本对 `UNSAFE LOGIN` 给专门提示。
- 工具结果文本始终列出完整事实（id/主题/收件人/发件人/时间/附件/正文/头字段），卡片只是另一种呈现；发送前需要用户明确要求，`mail_send` 不重复确认。
