# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。对话里的高频读取由 bash hook 桥接成卡片与完整文字结果；插件页负责安装、扫码授权与组件更新。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.3.0/Compose，plugin-api 1.2.0、design 0.9.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.3.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（含 `chat`/`app`/`logs`）/entryClass/provides（skill `deepdeep-wecom`、7 个 `chat.message:wecom.*` 槽位、page `main`） |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、版本参数化的解包/安装/回滚/卸载命令、双下载源、版本/授权/二维码解析 |
| `src/main/kotlin/.../WecomCommands.kt` | bash 命令解析：只接受单条受支持 `wecom-cli` 读取命令，拒绝管道/重定向/多命令/分页/输出文件，提取 chat_id、时间范围、关键词等上下文；`WecomCommandKind` 定义槽位 customType 与文案单位 |
| `src/main/kotlin/.../WecomCliOutput.kt` | CLI stdout 解析：去 ANSI/提示行/`[exit: N]`，提取首个完整 JSON（缩进或紧凑），错误 envelope 与未授权判定，超过 256 KiB 直接放弃 |
| `src/main/kotlin/.../WecomParsed.kt` | 受支持命令的响应字段解析（群聊/消息/文档/文档内容/日程/待办/通讯录），缺数组视为空、类型不符视为失败 |
| `src/main/kotlin/.../WecomObservation.kt` | 模型侧完整可读文字结果：名称、id、时间、发送人、状态、数量、链接、分页游标；长文档保留正文截断说明 |
| `src/main/kotlin/.../WecomHooks.kt` | hook 桥：`beforeToolCall` 记录 callId→命令（有界 64、LRU），`afterToolExecution` 关联成功输出并替换文字、投递卡片；未授权映射为可执行文案；`runFinished`/`sessionClosed` 清理 |
| `src/main/kotlin/.../WecomCardPayloads.kt` | 卡片 payload 构建与解析（state/count/context/rows/summary/actionUrl/note） |
| `src/main/kotlin/.../WecomChatCards.kt` | `SlotIds.chatMessage` 卡片：统一 header/count/rows/footer，最多 8 行，加载/空/失败态，文档行与「打开文档」动作 |
| `src/main/kotlin/.../WecomReleases.kt` | 更新检查：npm registry 解析 version/tarball/integrity（sha512）、版本比较、sha512 计算与校验、release 缓存（24 小时节流） |
| `src/main/kotlin/.../WecomStatus.kt` | `host.settings()`：CLI 版本、授权、Bot ID（旧 `wecom-status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / hooks / ui；技能文档内含读取与写操作纪律 |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：标题与用途、状态摘要、扫码优先、单一主操作、账号次要操作、安装/更新/连接/退出分阶段进度与取消、更新提示与 24 小时检查、错误重试、危险操作确认 |
| `src/main/kotlin/.../WecomTexts.kt` | 页面 zh/en 文案与 `host.t`/`host.tf` 扩展 |
| `src/test/kotlin/...` | 命令匹配、stdout 解析、observation 文案、卡片 payload、release/sha512、hook 桥的单测 |
| `src/test/resources/fixtures/` | 真机 CLI 采样与 fixture 说明（`README.md`） |

## 要点

- 安装走 `host.network().download`：优先用更新检查拿到的 npm registry release（`dist.tarball` + `dist.integrity` 的 sha512），下载后本地重算 sha512 校验，通过才解包；registry 不可用时首次安装回退内置 1.3.4 + sha256。安装脚本备份旧二进制，版本不符或中途取消回滚；成功后提交删除备份。更新不退出登录、不碰 `/root/.config/wecom`。
- 更新检查：`https://registry.npmjs.org/@wecom/cli-linux-arm64/latest` → version/tarball/integrity，24 小时节流（`host.settings()` 的 `cli-release`），页面状态为已就绪/待更新（检查中、失败带重试）；缺 sha512 或校验不符一律拒绝更新并保留现有组件。
- 对话读取桥：只处理 `bash` 工具、单条受支持的 `wecom-cli` 读取命令；成功且响应可解析时替换模型结果为完整 observation 并用 `host.chat().postCustomMessage` 投递卡片，否则保持原始结果。未授权错误替换为「去插件页扫码连接」的可执行文案；hook 全程防御式，超时/异常不影响工具结果。
- 卡片：7 个 `chat.message:wecom.*` 槽位，统一用 `DdGroupCard` header/footer 与 `DdListRow`，最多 8 行，文档行可打开链接；payload 自包含，重开会话后可还原；空/加载/失败与无法解析都有兜底视图。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后用 `DdImage` 显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途；连接进行时扫码卡排在视觉首位，二维码框固定 220dp，加载与展示在同一尺寸内切换，主区域不跳动；状态摘要用 `DdStatusRow`（运行环境/组件/授权）组成，值写「已就绪/待更新/未安装/已连接」这类人类可读结果，不含 Bot ID 与版本号，刷新时保留旧值并用行内加载圈标记检查中。
- 安装、更新、连接、退出各有阶段文案与确定进度条 `DdProgressBar`：安装/更新显示下载百分比并映射到解包/安装阶段，等待扫码按 6 分钟窗口推进，退出按阶段推进；都可取消（完成后阶段禁用取消）。
- 失败与超时用 `DdNoticeBar` 就地展示，动作按失败的操作重试；Linux 环境不可用时给重新检查入口。连接中扫码卡置顶，二维码出现不引起布局跳动；页面不播放自定义动效。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让工具/提示词立即按新状态物化；CLI 安装完成发稳定 id 通知，退出登录与重新授权前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
