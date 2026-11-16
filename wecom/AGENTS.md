# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。对话里的高频读取由 bash hook 桥接成卡片与完整文字结果；插件页负责安装、扫码授权与组件更新。命令桥、卡片模型与 CLI 更新/安装流程复用仓库内 `:cli-common`（`implementation(project(":cli-common"))`，类随插件一起进 `.ddp`）。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.6.1、versionCode 14、Compose，plugin-api 1.2.0、design 0.10.0、`:cli-common` 依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.6.1/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.0/capabilities（含 `chat`/`app`/`logs`）/entryClass/provides（skill `deepdeep-wecom`、7 个 `chat.message:wecom.*` 槽位、page `main`） |
| `src/main/kotlin/.../WecomCommands.kt` | bash 命令解析：用 `CliCommandLine` 的 tokenize/envPrefixLength/isBinary/hasShellOperators 替换私有词法，只接受单条受支持 `wecom-cli` 读取命令，额外拒绝 `&`/括号/`--`/分页/输出文件/未闭合引号，提取 chat_id、时间范围、关键词等上下文；`WecomCommandKind` 定义槽位 customType 与文案单位 |
| `src/main/kotlin/.../WecomCliOutput.kt` | CLI stdout 解析：用 `CliJsonEnvelope.LENIENT` 取第一个平衡 JSON（裸结果对象无 `ok`），保留 `[wecom]` 提示行/`[exit: N]`/已知噪声清理、`error` envelope 与未授权判定（`errorMapper` 用 `failure`，`payloadFinder` 用 `payloadElement`）；`exitCode`/`exitMarker` 供桥接层保留退出码，`unwrap` 把顶层 `security_notice`/`errcode`/`errmsg` 合并进结果 |
| `src/main/kotlin/.../WecomParsed.kt` | 受支持命令的响应字段解析（群聊/消息/文档/文档内容/日程/待办/通讯录），缺数组视为空、类型不符视为失败；覆盖 `creator_userid`/`open_time`/`text_highlight`、`errcode`/`errmsg`、`file_path`、`creator_name`/`meeting_room`/`attendees`、`user_status`/`deadline.type`/`followers`、`matched_keywords`/`hint`、顶层 `security_notice` 与各命令分页字段；JSON 取值走 `:cli-common` 的 `CliJson` 扩展 |
| `src/main/kotlin/.../WecomObservation.kt` | `summary` 给卡片标题（与旧版首行一致），`build` 只产出 guidance：空结果说明、分页游标、下一步命令、文档文件路径与 `security_notice` 安全提示；不再拼数据行，全量数据在卡片 payload 与 `deepdeep.raw` |
| `src/main/kotlin/.../WecomHooks.kt` | bash 工具桥：`CliToolBridge` 装配（parser、observation builder、`CliCardCodec` 编码、`CliJsonEnvelope.LENIENT`、`payloadFinder`、未授权 `errorMapper`）；callId 关联/去重/输出上限/卡片与 `deepdeep.raw` details/`resultReplace` 全部由 `:cli-common` 完成；成功观测把卡片 payload 放进 `deepdeep.card`，不再自行追加卡片 id；文档返回 `file_path` 时经 `cat` 或文件服务读取正文写入卡片与 raw；退出码写进 guidance；未授权错误原文进 raw、guidance 追加扫码建议 |
| `src/main/kotlin/.../WecomChatCards.kt` | 槽位注册与分发：`wecomChatCardBindings` 注册 7 个 `chat.message:wecom.*` 槽位，`WecomChatCard` 解码 payload 后按 kind 分发到各自的版式；解析失败给不可展示兜底 |
| `src/main/kotlin/.../WecomCardModels.kt` | 卡片数据：`WecomChatCards.build` 把 7 类解析结果映射成共享 `CliCardModel`（消息正文放 `CliCardItem.body`、日程日期放 `badge`、待办截止放 `badge`，行内文案与补充详情在构建时本地化），`decode` 走 `CliCardCodec` 并兼容旧 2.3.0 payload；全量行与字段，不裁剪 |
| `src/main/kotlin/.../WecomCardFormat.kt` | 纯展示格式化：时间/日期/数量/文件消息/日程/待办字符串的解析与短格式，无 Compose 依赖，供构建与渲染共用；消息正文优先取 `body` 并兼容旧 subtitle |
| `src/main/kotlin/.../WecomCardChrome.kt` | 卡片共用外观：卡面与出现动效、标题行、关键词/类型/版本胶囊、首字母头像、展开控件、分页提示行、打开入口，以及加载/空/失败的统一状态版式；颜色、字号、间距、圆角、透明度全部走 design token |
| `src/main/kotlin/.../WecomMessageCards.kt` | 群聊卡（序号 + 首条强调条 + 最近消息 + 条数）与消息卡（头像轨道 + 发言人/时间 + 完整正文或文件胶囊）；预览 3–4 行，`DdExpandToggle` 展开全部，单条长消息可再展开全文 |
| `src/main/kotlin/.../WecomDocCards.kt` | 文档搜索卡（类型标签 + 标题 + 修改信息 + 外链，展开后显示创建人 ID/打开时间/命中片段）与文档内容卡（大标题 + 版本 + 左侧强调条全文，`DdExpandToggle` 展开整篇文档 + 文档 ID/打开） |
| `src/main/kotlin/.../WecomScheduleCard.kt` | 日程卡：按日期分段，左侧时间列 + 时间轴节点 + 事件标题/地点；展开后显示创建人、会议室、参与人、日历与日程 ID |
| `src/main/kotlin/.../WecomTodoCard.kt` | 待办卡：勾选圈、状态点、完成划线、右侧截止日期与时间；展开后显示描述、截止类型、创建人、我的状态与待办 ID |
| `src/main/kotlin/.../WecomContactCard.kt` | 通讯录卡：首字母头像 + 姓名 + 部门/职位 + 右对齐邮箱；展开后部门/职位/邮箱完整展示，并补充 userid、英文名与命中关键词 |
| `src/main/kotlin/.../WecomInstall.kt` | 安装计划与错误文案：备份/替换/回滚脚本、解包验版本、`wecom-cli` 版本解析；执行走 `:cli-common` 的 `CliInstallRunner`（下载、sha512 校验、进度、取消回滚） |
| `src/main/kotlin/.../WecomReleases.kt` | 更新源与缓存：npm registry `latest` 解析 version/tarball/integrity（sha512）、`WecomRelease` 缓存（24 小时节流）、`CliUpdateSource` 实现、把 registry release 映射成 `CliRelease(sha512Base64)`、pinned 1.3.4（sha256）回退；版本比较委托 `CliVersions` |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、版本参数化的解包/安装/回滚/卸载命令、双下载源、版本/授权/二维码解析 |
| `src/main/kotlin/.../WecomStatus.kt` | `host.settings()`：CLI 版本、授权、Bot ID（旧 `wecom-status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / hooks / ui；技能文档内含读取与写操作纪律 |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：标题与用途、状态摘要、扫码优先、单一主操作、账号次要操作、安装/更新/连接/退出分阶段进度与取消、更新提示与 24 小时检查（`CliUpdateCheckController`）、错误重试、危险操作确认 |
| `src/main/kotlin/.../WecomTexts.kt` | 页面 zh/en 文案与 `host.t`/`host.tf` 扩展 |
| `src/test/kotlin/...` | 命令匹配、stdout 解析、summary/guidance 协议、卡片全量字段、共享卡片模型与展开字段、release/sha512、工具桥行为（含 raw/退出码/文件正文/未授权原文/7 槽位）的单测 |
| `src/test/resources/fixtures/` | 真机 CLI 采样与 fixture 说明（`README.md`） |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :wecom:testDebugUnitTest :wecom:verifyPlugin`，产物 `wecom/build/plugin/wecom.ddp`；`.ddp` 内含 `io.github.whywood.deepdeep.plugin.cli.*` 类。
- 安装走 `host.network().download`：优先用更新检查拿到的 npm registry release（`dist.tarball` + `dist.integrity` 的 sha512），由共享 `CliInstallRunner` 下载后本地重算 sha512 校验，通过才解包；registry 不可用时首次安装回退内置 1.3.4 + sha256。安装脚本备份旧二进制，版本不符或中途取消回滚；成功后删除备份。更新不退出登录、不碰 `/root/.config/wecom`。
- 更新检查：`https://registry.npmjs.org/@wecom/cli-linux-arm64/latest` → version/tarball/integrity，`WecomReleaseSource` 收敛异常为 `failed`，单飞与 24 小时节流由 `CliUpdateCheckController` 负责；页面状态为已就绪/待更新（检查中、失败带重试）；缺 sha512 或校验不符一律拒绝更新并保留现有组件。
- skill/prompt 明确“工具结果带卡片 id 时用 `present` 交付卡片，正文只写结论与下一步”，与读取/文档/日程/待办等槽位一致。
- 对话读取桥：只处理 `bash` 工具、单条受支持的 `wecom-cli` 读取命令；成功且响应可解析时，`summary` 作卡片标题、`text` 只放 guidance（空结果、分页、下一步与退出码），全量数据在卡片 payload，完整 stdout 由共享层挂到 `deepdeep.raw`；宿主统一处理 raw 与卡片 id，插件不再自己拼卡片 id。文档内容只给 `file_path` 时读取文件正文写入卡片，raw 仍保留原始 stdout。未授权错误原文进 raw，guidance 追加「去插件页扫码连接」的建议；hook 全程防御式，超时/异常不影响工具结果。
- 卡片：7 个 `chat.message:wecom.*` 槽位，每类卡按内容单独排版——群聊/通讯录像精简通讯录行、消息像聊天气泡、文档搜索是文档条目、文档内容是预览页、日程是左侧时间轴、待办是勾选清单。每一行都进入 payload，预览 3–4 行后用 design 的 `DdExpandToggle` 展开全部；消息正文存 `CliCardItem.body`，文档正文整篇折叠，日程/待办/通讯录的补充字段在展开后完整可见。浅色/深色共用 design token（`deepDeepColors()` + `DdAlpha` 叠加填充），加载/空/失败态也走同一套卡面；payload 为共享 `CliCardModel` wire 格式，重开会话后可还原，旧 2.3.0 payload 仍可解码渲染。卡片只出现在回答区，工具过程仍显示原始 bash 工具卡。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后用 `DdImage` 显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途；连接进行时扫码卡排在视觉首位，二维码框固定 220dp，加载与展示在同一尺寸内切换，主区域不跳动；状态摘要用 `DdStatusRow`（运行环境/组件/授权）组成，值写「已就绪/待更新/未安装/已连接」这类人类可读结果，不含 Bot ID 与版本号，刷新时保留旧值并用行内加载圈标记检查中。
- 安装、更新、连接、退出各有阶段文案与确定进度条 `DdProgressBar`：安装/更新把共享安装器的 DOWNLOADING/VERIFYING/INSTALLING/FINISHING 事件映射成下载百分比与解包/安装阶段，等待扫码按 6 分钟窗口推进，退出按阶段推进；都可取消（完成后阶段禁用取消，取消时共享安装器回滚未完成的 CLI 安装）。
- 失败与超时用 `DdNoticeBar` 就地展示，动作按失败的操作重试；Linux 环境不可用时给重新检查入口。连接中扫码卡置顶，二维码出现不引起布局跳动；页面不播放自定义动效。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让工具/提示词立即按新状态物化；CLI 安装完成发稳定 id 通知，退出登录与重新授权前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
