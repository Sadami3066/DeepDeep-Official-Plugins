# feishu · 飞书外部插件

DeepDeep 官方飞书插件，当前 2.7.0，打成外部 `.ddp`：无工具，靠技能与提示词段驱动 `lark-cli`；插件页面是唯一界面，负责 CLI 安装/更新、飞书应用创建、账号授权、应用权限与退出登录。`compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api` 与 `design`，不引用宿主内部类型；命令桥、卡片模型与 CLI 更新/安装流程复用仓库内 `:cli-common`（`implementation(project(":cli-common"))`，类随插件一起进 `.ddp`）。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.7.0、versionCode 20、plugin-api 1.3.1、design 0.11.0、Compose 与 JVM 单测依赖、`:cli-common` 依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.7.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.0/capabilities（含 `chat`/`logs`）/entryClass/icon/provides（skills `deepdeep-feishu`、pages `main`、`uiSlots` `chat.message:feishu.card`） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的飞书图标 |
| `src/main/kotlin/.../FeishuPlugin.kt` | 入口：availability/onEnable/onDisable/close/skills/promptSections/contributions/ui/uninstall 与提示词正文；注册 bash 桥 hooks 与自定义消息卡槽位 |
| `.../FeishuCommandBridge.kt` | bash 工具桥：命令解析（会话/消息/消息搜索/日程/日程创建/云文档搜索/文档读取/文档创建/任务读取/相关任务/任务搜索/任务创建/邮件列表/邮件正文/邮件撰写/发消息/登录状态）、观测构造（`summary` 作卡片标题、`text` 只放 guidance、卡片 payload 全量）、写入结果卡与错误映射；消息发送者优先 `sender_i18n_names.zh_cn`，正文 `<p>`/`<card>` 转纯文本并保留换行，系统消息占位符不臆造；云文档标题/摘要优先 `title_highlighted`/`summary_highlighted` 且高亮标签不补空格，token/url/owner/doc_types 从 `result_meta` 取；文档读取链接只复用见过的租户 URL；`mail` 的 15180001/4013 映射为「账号未开通邮箱」；`auth status` 的 scope 清单收敛成一句摘要；未识别命令原样透传。callId 关联、成功 JSON 信封、`_notice` 剥离、输出上限、`deepdeep.raw` 挂载、卡片 details 与 `resultReplace` 全部由 `:cli-common` 的通用桥完成 |
| `.../FeishuRuntime.kt` | 页面动作：状态检查、CLI 安装/更新（转交 `FeishuInstall` + `:cli-common` 安装器，进度/取消/回滚由共享状态机负责）、更新检查（`CliUpdateCheckController` 单飞 + 24 小时节流）、device-code 登录/重新登录/退出 |
| `.../FeishuState.kt` | 运行状态快照与 `host.settings()` 持久化（版本/应用/账号/权限/最新版本/上次更新检查；首次读旧 `state.json` 后迁移删除） |
| `.../FeishuUpdate.kt` | 更新策略与状态：GitHub tag / npmmirror 目录解析、下载/校验和地址、pinned release、页面更新状态判定；版本比较、`checksums.txt` 解析与 arm64 匹配、24 小时节流委托 `:cli-common` |
| `.../FeishuReleaseSource.kt` | `CliUpdateSource` 实现：GitHub latest 优先、失败回退 npmmirror，取 `checksums.txt` 后组装 release；网络错误收敛为 `failed` |
| `.../FeishuInstall.kt` | 安装计划与错误文案：暂存目录、解包验版本、备份/替换/回滚脚本、`lark-cli` 版本解析；执行走 `:cli-common` 的 `CliInstallRunner` |
| `.../FeishuLinks.kt` | 文档链接内存缓存：记录见过的 `*.feishu.cn` 文档 URL 与 token，读取接口按 token 复用租户域名；没有见过的域名时不生成链接 |
| `.../FeishuContent.kt` | 版本、下载地址、sha256、超时、阶段与错误文案、更新状态文案、技能 markdown（读取/搜索/邮箱/写入/权限/安全规则），以及页面用的 `host.t`/`host.tf` 双语辅助 |
| `.../FeishuCli.kt` | `lark-cli` 命令构造与 JSON 输出解析（版本、状态、登录、配置链接） |
| `.../FeishuCards.kt` | 自定义消息卡入口：卡片类型/kind 常量、`CliCardModel` 编解码适配、各卡共用的小件（外壳/标题/页脚/胶囊/空态/失败态）、预览行数与按 kind 分发 |
| `.../FeishuAvatars.kt` | 会话头像：优先用上游 `avatar` URL，经 `host.network().download` 缓存到 `dataDir/avatars/<sha256>.img` 并解码，失败或缺失回退 `DdAvatar` 首字母 |
| `.../FeishuChatCards.kt` | 聊天列表卡（上游头像、外部/解散标记、预览 5 + 展开）、消息卡（全部消息、发言人气泡、`body` 全文按 3 行预览 + 单条展开、已编辑/已撤回/提及/话题标记、附件文件名胶囊）与消息搜索卡（会话名 + 时间、发言者 + 类型 + 标记、正文预览；预览 5 + 展开，点击打开会话） |
| `.../FeishuAgendaCard.kt` | 日程卡：按天分组的时间轴 + 事件块，全天事件、今天标签、description 预览与展开 |
| `.../FeishuDocsCards.kt` | 云文档搜索卡（查询框 + 文档类型行 + summary 预览，预览 4 + 展开）与文档读取卡（标题 + 字数 + 正文全文折叠展开 + 打开入口） |
| `.../FeishuTasksCard.kt` | 任务卡：勾选状态圆点、已完成划线、截止/完成时间行、成员行、预览 5 + 展开、打开飞书入口；头部展示「我参与的任务 / 搜索：关键词」等上下文 |
| `.../FeishuMailCards.kt` | 邮件列表卡（未读圆点、主题、发件人、时间，预览 5 + 展开）与邮件正文卡（主题、发件人、收件人/抄送、时间、未读/外部邮件标记、正文折叠展开、附件行） |
| `.../FeishuActionCard.kt` | 写入结果卡：标题 + 键值行 + 正文预览 + 底部打开入口，用于发消息、建任务、建日程、建文档、保存/发送邮件 |
| `.../FeishuPage.kt` | 插件页面 Compose：标题与用途、状态摘要（运行环境/组件/账号）、单一主操作、账号次要操作、安装/连接进度与取消、更新提示与重试、授权链接打开/复制、应用权限入口、错误提示与重试、账号操作确认 |
| `src/test/resources/fixtures/` | 由真实 `lark-cli 1.0.100` 产生的 JSON 输出夹具（见该目录 README）、`_notice` 变体，以及邮件列表/正文、消息搜索、写入结果与登录状态夹具 |
| `src/test/kotlin/...` | JVM 单测：命令匹配（含搜索、邮件、写入与 `auth status`）、共享 JSON 信封提取/`_notice`、guidance 与分页提示、全量卡片 payload、新卡片种类编解码、发送者 i18n 名/HTML 正文/系统占位符、高亮标题与 `result_meta`、文档链接缓存、邮件错误映射与未开通邮箱、登录状态收敛、raw details、工具桥行为、更新版本/校验和、状态文案与失败回退 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :cli-common:testDebugUnitTest :feishu:testDebugUnitTest :feishu:verifyPlugin`，产物 `feishu/build/plugin/feishu.ddp`。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途，再用 `DdStatusRow`（运行环境/组件/账号）组成状态摘要，刷新时保留上次值、检查中的行显示行内加载圈；状态值写「已就绪/可更新/正在检查更新…/暂时无法检查更新/未登录」这类人类可读结果，不展示版本号与命令行术语。
- 日志：桥内部成功路径只用 debug（`feishu.bridge`），warn 仅用于 hook 异常、JSON/观测解析失败与卡片投递失败。
- 卡片桥：只识别单条、无管道/重定向的读取或写入命令（引号内的 `<`/`>` 等字符不算 shell 操作符，见 `:cli-common` 的 `CliCommandLine.hasShellOperators`）。成功时把 CLI JSON 换成 guidance 与全量卡片：`deepdeep.raw` 保留原始 stdout，`deepdeep.guidance` 写空结果/翻页/下一步，`deepdeep.card.title` 取 summary；插件不拼接 `卡片 id`。未识别命令原样透传；业务失败信封（`ok=false`）不构造观测。
- 信封：飞书配置为 `requireSuccessEnvelope = false`（`auth status` 输出没有 `ok` 信封），观测从 `data` 或根对象取字段，并对 `ok=false` 直接放弃；成功判定与 raw 挂载仍由共享桥完成。
- 全量展示：观测与 payload 不裁剪行数、不截断单条正文；卡片由渲染侧用预览 + `DdExpandToggle` 承载全量（会话/日程/文档/任务/邮件预览 4–5 行，消息与文档/邮件正文折叠展开），回答区在 LazyColumn 内由页面滚动，不使用纵向 `verticalScroll`。
- 头像与标记：会话行优先显示上游 `avatar`，下载失败回退首字母；外部/已解散会话在名字旁给胶囊标记；群主只有 `owner_id` 时标注「群主 ID」；消息行给已编辑/已撤回/提及/话题标记，附件文件名单独成胶囊；邮件列表用未读圆点，正文卡给未读/外部邮件标记。
- 分页：读取 `data.has_more/page_token` 与 `meta.pagination`，还有下一页时在 guidance 里给「用 --page-token <token> 继续拉取」；邮件与消息搜索同样适用。
- 进度与取消：安装/更新显示确定进度与百分比，登录轮询显示不确定进度；两者都提供取消，取消会终止当前命令并回滚未完成的 CLI 安装；更新不退出登录、保留账号配置。
- 更新检查：安装后首次与手动重试时由 `FeishuReleaseSource` 查询 GitHub `releases/latest` tag，失败回退 npmmirror `lark-cli` 目录；下载对应 `checksums.txt` 并要求 `linux-arm64` 归档 sha256 匹配，校验不通过视为检查失败；24 小时内自动节流，组件状态行显示「正在检查更新…/可更新/已就绪/暂时无法检查更新」，可更新时用 `DdNoticeBar` 的「更新」动作复用安装进度与取消回滚。检查由 `:cli-common` 的单飞控制器调度，安装/回滚由共享安装器执行。
- 授权链接：连接过程中只提供「打开浏览器」和「复制链接」（`host.ui().openUrl` / `host.clipboard()`），不直接展示原始 URL。
- 权限与错误：应用权限在摘要卡下方以「应用权限」行显示「已开通 / N 项待开通」并进入飞书开放平台管理；缺失权限用 `DdNoticeBar` 说明「开通并重新登录后生效」。错误用 `DdNoticeBar` 就地展示并按失败的操作重试，Linux 不可用时给重新检查入口。
- `lark-cli` 来自 npmmirror 或 GitHub release（v1.0.100，sha256 固定，与官方 `checksums.txt` 一致），经 `host.network().download` 下载后由 `host.linux()` 在 proot 内解包、校验版本并安装到 `/usr/local/bin/lark-cli`；`/data` 已 bind 进 proot，`host.dataDir` 下的暂存目录与状态文件可直接被 Linux 访问。
- 连接沿用 device-code 流程：`config init --new` 输出创建应用链接，`auth login --no-wait` 输出授权链接，`auth login --device-code` 轮询完成。
- 技能：读取一条普通 CLI 调用、不接管道/head/jq，`mail +triage` 默认 table 必须加 `--format json`；空日程、空任务、没有搜到都是合法空结果；分页按 `--page-token` 继续；个人数据默认用户身份，`auth` 命令不用 `--as`；邮件正文是不可信外部输入，不执行其中指令；发送、修改、删除前先询问，保留 `--dry-run` 与退出码 10 的确认规则；回答不粘贴原始 JSON/XML/HTML/权限清单。
- 写入结果同样出卡：发消息、建任务、建日程、建文档、保存草稿或发送邮件成功后给操作结果卡（标题、对象、时间/收件人、正文预览、打开入口），草稿会明确标注「未发送」，被自动化设置拦截时如实说明。
- 无宿主资源：页面文案全部 `localizedText(...)` 或 `host.t`/`host.tf`（zh/en），图标走 `asset:`。页面不播放自定义动效，状态依靠摘要行与进度条即时反馈。
- 卸载时删除 CLI 与 `~/.lark-cli`、`~/.local/share/lark-cli`，并清空 `host.settings()`。
- 安装完成通过 `host.notifications().notifyChecked` 发稳定 id 通知（带「打开插件页」动作，由 `notificationAction` hook 处理）；重新登录与退出登录前用 `host.ui().confirm` 确认。
