# mail · 邮箱外部插件

DeepDeep 官方邮箱插件，当前 1.5.0，打成外部 `.ddp`：不依赖 CLI，用 JVM 网络栈直连 IMAP/SMTP；提供 `mail_search`、`mail_read`、`mail_save_attachment`、`mail_draft`、`mail_send`、`mail_mark`、`mail_archive` 七个工具与插件自绘页面。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api`（1.2.0 与 1.3.0）与 `design` 0.9.0，不引用宿主内部类型；唯一 `implementation` 依赖是 `org.apache.james:apache-mime4j-core:0.8.15`。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 1.5.0、versionCode 9、plugin-api、design、Compose、mime4j 与 JVM 单测依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 1.5.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（含 `chat`、`media`、`background`）/entryClass/provides（pages `mail`、uiSlots `chat.message:mail.search`、`chat.message:mail.read`、`chat.message:mail.draft`） |
| `src/main/kotlin/.../MailPlugin.kt` | 入口：availability / uninstall / skills / promptSections / contributions（工具与 hooks）/ ui；提示词正文在 `MailPrompt.kt` 同级（`mailPromptBody`） |
| `.../MailRuntime.kt` | 插件页与工具共用的运行状态：账号、文件夹、消息、草稿、同步、发送、附件与状态快照 |
| `.../MailModels.kt`、`.../MailRepository.kt`、`.../store/`、`.../sync/` | 邮箱数据模型、仓库读接口、`host.settings()` 下的账号索引/文件夹索引/草稿存储、后台增量同步 |
| `.../transport/` | IMAP/SMTP 客户端、TLS、编码解析、错误本地化 |
| `.../mime/` | MIME 解析/组装、RFC 2047、HTML 转纯文本（`HtmlText.preview`） |
| `src/main/kotlin/.../tools/MailTools.kt` | 七个工具：搜索/读信/保存附件/起草/发送/标记/归档；工具结果文本列出完整事实；卡片数据写进结果 `details` |
| `.../tools/MailCardPayloads.kt` | 卡片 payload（`MailSearchCardPayload`/`MailReadCardPayload`/`MailDraftCardPayload` 与行模型）与 `encodeCardPayload()`；wire 结构本轮不变 |
| `.../tools/MailHooks.kt` | 后台同步与通知动作 hooks |
| `.../ui/MailChatCardChrome.kt` | 回答区卡片的共享外观件：卡片表面（入场淡入+上移 200ms，尊重 `LocalDdMotionEnabled`）、虚线分隔、信封线稿、上下文/收件人 chip、附件胶囊、空信封插画、可点行、打开邮箱/邮件/草稿的 host 动作、payload 解码 |
| `.../ui/MailChatCards.kt` | `MailSearchChatCard`（信封列表）与 `MailReadChatCard`（信纸）：未读圆点/发件人加粗/主题/日期右对齐；读信双细线信头、主题、正文摘要、附件胶囊；各自空/失败态 |
| `.../ui/MailDraftChatCard.kt` | `MailDraftChatCard`（写信预览）：草稿状态条、收件人/抄送 chip、主题、正文面板、附件、编辑/发送与发送中/失败/已发送/草稿缺失态 |
| `.../ui/MailPage.kt`、`.../ui/MailThreadPage.kt`、`.../ui/MailInboxList.kt` 等 | 插件页、会话页、收件箱与写信/添加账号 sheet |
| `src/test/kotlin/...` | IMAP/SMTP 协议、MIME、同步逻辑、工具文本与卡片 details、prompt 文案、HTML 清洗单测；`FakePluginHost` 为宿主替身 |

## 回答区卡片

- 投递：`mail_search`/`mail_read`/`mail_draft` 把卡片写进工具结果 `details` 的 `deepdeep.card`：`{"customType": "mail.search|mail.read|mail.draft", "title": 摘要, "payload": MailCardPayloads 的 JSON}`，并在模型可见文本末尾追加一行 `卡片 id：<callId>`；**不再调用** `host.chat().postCustomMessage`。槽位与 payload 不变，旧会话里的自定义消息仍按同一槽位渲染。
- 搜索是信封列表：未读圆点 + 信封线稿 + 发件人（未读加粗）+ 主题 + 日期右对齐，行间虚线；顶部查询词与 `仅未读/账户` chip，底部「打开邮箱」，超过 4 封显示「还有 N 封未显示」。
- 读信是信纸：发件人头像/名字/地址与时间、双细线信头、主题、正文摘要按信纸行距排版、附件胶囊；未读显示圆点，底部「打开邮件」。
- 草稿是写信预览：`草稿 · 未发送` 状态条 + 账户、收件人/抄送 chip、主题、正文面板、附件胶囊，底部「编辑」与「发送」；发送中/失败/已发送/草稿已删除都有对应状态。
- 空搜索显示空信封插画 + 一句提示 + 筛选 chip + 打开邮箱；读信/草稿不可用给一句人话与打开邮箱出口。
- 颜色全部来自 `deepDeepColors()`，字号优先 `DdType`，间距走 `DdSpacing`；浅色/深色两套都成立；卡片整体入场淡入+上移 200ms，行内不做逐行动画。

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :mail:testDebugUnitTest :mail:verifyPlugin`，产物 `mail/build/plugin/mail.ddp`。
- 卡片自检设计稿：`/tmp/card-mockups/mail/light.html`、`dark.html`（390px 宽，含搜索/读信/草稿与空/失败态）与对应 `light.png`、`dark.png`。
- 用户可见文案中英成对（`host.t`/`host.tf`），卡片不出现 App 名、插件名、「卡片」「结果」这类开发词；卡片 id 行只出现在工具结果文本里，供模型决定是否 `present` 展示。
- 工具结果文本始终列出完整事实（id/主题/发件人/时间/附件/正文），卡片只是另一种呈现；发送前需要用户明确要求，`mail_send` 不重复确认。
