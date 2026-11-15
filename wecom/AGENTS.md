# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。对话里的高频读取由 bash hook 桥接成卡片与完整文字结果；插件页负责安装、扫码授权与组件更新。命令桥、卡片模型与 CLI 更新/安装流程复用仓库内 `:cli-common`（`implementation(project(":cli-common"))`，类随插件一起进 `.ddp`）。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.3.1、versionCode 10、Compose，plugin-api 1.2.0、design 0.9.0、`:cli-common` 依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.3.1/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（含 `chat`/`app`/`logs`）/entryClass/provides（skill `deepdeep-wecom`、7 个 `chat.message:wecom.*` 槽位、page `main`） |
| `src/main/kotlin/.../WecomCommands.kt` | bash 命令解析：用 `CliCommandLine` 的 tokenize/envPrefixLength/isBinary/hasShellOperators 替换私有词法，只接受单条受支持 `wecom-cli` 读取命令，额外拒绝 `&`/括号/`--`/分页/输出文件/未闭合引号，提取 chat_id、时间范围、关键词等上下文；`WecomCommandKind` 定义槽位 customType 与文案单位 |
| `src/main/kotlin/.../WecomCliOutput.kt` | CLI stdout 解析：用 `CliJsonEnvelope.LENIENT` 取第一个平衡 JSON（裸结果对象无 `ok`），保留 `[wecom]` 提示行/`[exit: N]`/已知噪声清理、`error` envelope 与未授权判定（`errorMapper` 用 `failure`，`payloadFinder` 用 `payloadElement`） |
| `src/main/kotlin/.../WecomParsed.kt` | 受支持命令的响应字段解析（群聊/消息/文档/文档内容/日程/待办/通讯录），缺数组视为空、类型不符视为失败；JSON 取值走 `:cli-common` 的 `CliJson` 扩展 |
| `src/main/kotlin/.../WecomObservation.kt` | 模型侧完整可读文字结果：名称、id、时间、发送人、状态、数量、链接、分页游标；长文档保留正文截断说明 |
| `src/main/kotlin/.../WecomHooks.kt` | bash 工具桥：`CliToolBridge` 装配（parser、observation builder、`CliCardCodec` 编码、`CliJsonEnvelope.LENIENT`、`payloadFinder`、未授权 `errorMapper`）；callId 关联/去重/输出上限/卡片投递/`resultReplace` 全部由 `:cli-common` 完成；7 类 observation 按 command kind 覆盖 customType |
| `src/main/kotlin/.../WecomChatCards.kt` | 卡片构建与渲染：`WecomChatCards.build` 把 7 类解析结果映射成 `CliCardModel`（文档卡用 `title`/`trailing`/`summary`/`openUrl`，行右侧值在构建时本地化后放 `meta`），渲染仍用 `DdGroupCard`/`DdListRow` 统一 header/rows/footer、最多 8 行；`decode` 走 `CliCardCodec`，旧 2.3.0 payload 有兼容解码 |
| `src/main/kotlin/.../WecomInstall.kt` | 安装计划与错误文案：备份/替换/回滚脚本、解包验版本、`wecom-cli` 版本解析；执行走 `:cli-common` 的 `CliInstallRunner`（下载、sha512 校验、进度、取消回滚） |
| `src/main/kotlin/.../WecomReleases.kt` | 更新源与缓存：npm registry `latest` 解析 version/tarball/integrity（sha512）、`WecomRelease` 缓存（24 小时节流）、`CliUpdateSource` 实现、把 registry release 映射成 `CliRelease(sha512Base64)`、pinned 1.3.4（sha256）回退；版本比较委托 `CliVersions` |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、版本参数化的解包/安装/回滚/卸载命令、双下载源、版本/授权/二维码解析 |
| `src/main/kotlin/.../WecomStatus.kt` | `host.settings()`：CLI 版本、授权、Bot ID（旧 `wecom-status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / hooks / ui；技能文档内含读取与写操作纪律 |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：标题与用途、状态摘要、扫码优先、单一主操作、账号次要操作、安装/更新/连接/退出分阶段进度与取消、更新提示与 24 小时检查（`CliUpdateCheckController`）、错误重试、危险操作确认 |
| `src/main/kotlin/.../WecomTexts.kt` | 页面 zh/en 文案与 `host.t`/`host.tf` 扩展 |
| `src/test/kotlin/...` | 命令匹配、stdout 解析、observation 文案、共享卡片模型、release/sha512、工具桥行为（含卡片提示/未授权映射/7 槽位）的单测（64 例） |
| `src/test/resources/fixtures/` | 真机 CLI 采样与 fixture 说明（`README.md`） |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :wecom:testDebugUnitTest :wecom:verifyPlugin`，产物 `wecom/build/plugin/wecom.ddp`；`.ddp` 内含 `io.github.whywood.deepdeep.plugin.cli.*` 类。
- 安装走 `host.network().download`：优先用更新检查拿到的 npm registry release（`dist.tarball` + `dist.integrity` 的 sha512），由共享 `CliInstallRunner` 下载后本地重算 sha512 校验，通过才解包；registry 不可用时首次安装回退内置 1.3.4 + sha256。安装脚本备份旧二进制，版本不符或中途取消回滚；成功后删除备份。更新不退出登录、不碰 `/root/.config/wecom`。
- 更新检查：`https://registry.npmjs.org/@wecom/cli-linux-arm64/latest` → version/tarball/integrity，`WecomReleaseSource` 收敛异常为 `failed`，单飞与 24 小时节流由 `CliUpdateCheckController` 负责；页面状态为已就绪/待更新（检查中、失败带重试）；缺 sha512 或校验不符一律拒绝更新并保留现有组件。
- 对话读取桥：只处理 `bash` 工具、单条受支持的 `wecom-cli` 读取命令；成功且响应可解析时把 CLI JSON 换成完整 observation，并用 `host.chat().postCustomMessage` 投递对应槽位卡片；卡片投递失败仍替换为可读 observation（不追加提示、不暴露原始 JSON）。未授权错误经 `errorMapper` 换成「去插件页扫码连接」的可执行文案；hook 全程防御式，超时/异常不影响工具结果。
- 卡片：7 个 `chat.message:wecom.*` 槽位，统一用 `DdGroupCard` header/footer 与 `DdListRow`，最多 8 行，文档行可打开链接；payload 为共享 `CliCardModel` wire 格式，重开会话后可还原，旧 2.3.0 payload 也可还原。模型观测末尾由共享桥追加一句「结果已同时以卡片展示」的提示。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后用 `DdImage` 显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途；连接进行时扫码卡排在视觉首位，二维码框固定 220dp，加载与展示在同一尺寸内切换，主区域不跳动；状态摘要用 `DdStatusRow`（运行环境/组件/授权）组成，值写「已就绪/待更新/未安装/已连接」这类人类可读结果，不含 Bot ID 与版本号，刷新时保留旧值并用行内加载圈标记检查中。
- 安装、更新、连接、退出各有阶段文案与确定进度条 `DdProgressBar`：安装/更新把共享安装器的 DOWNLOADING/VERIFYING/INSTALLING/FINISHING 事件映射成下载百分比与解包/安装阶段，等待扫码按 6 分钟窗口推进，退出按阶段推进；都可取消（完成后阶段禁用取消，取消时共享安装器回滚未完成的 CLI 安装）。
- 失败与超时用 `DdNoticeBar` 就地展示，动作按失败的操作重试；Linux 环境不可用时给重新检查入口。连接中扫码卡置顶，二维码出现不引起布局跳动；页面不播放自定义动效。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让工具/提示词立即按新状态物化；CLI 安装完成发稳定 id 通知，退出登录与重新授权前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
