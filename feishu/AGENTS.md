# feishu · 飞书外部插件

DeepDeep 官方飞书插件，当前 2.3.4，打成外部 `.ddp`：无工具，靠技能与提示词段驱动 `lark-cli`；插件页面是唯一界面，负责 CLI 安装/更新、飞书应用创建、账号授权、应用权限与退出登录。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api` 与 `design`，不引用宿主内部类型。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName、plugin-api 1.2.0、design 0.9.0、Compose 与 JVM 单测依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（含 `chat`/`logs`）/entryClass/icon/provides（skills `deepdeep-feishu`、pages `main`、`uiSlots` `chat.message:feishu.card`） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的飞书图标 |
| `src/main/kotlin/.../FeishuPlugin.kt` | 入口：availability/onEnable/onDisable/close/skills/promptSections/contributions/ui/uninstall 与提示词正文；注册 bash 桥 hooks 与自定义消息卡槽位 |
| `.../FeishuCommandBridge.kt` | bash 工具桥：`beforeToolCall` 按 callId 记录单条 `lark-cli` 读取命令，`afterToolExecution` 关联 callId、解析成功 JSON 信封、生成中文/英文可读观测并投递自定义消息卡，然后 `resultReplace`；不支持的命令/失败/超限输出一律 `resultKeep`。含命令匹配、ANSI/点号前缀清理、平衡括号 JSON 提取、`_notice` 剥离与卡片/观测模型 |
| `.../FeishuRuntime.kt` | 页面动作：状态检查、CLI 安装/更新（参数化 release、进度/取消/回滚）、更新检查（GitHub latest 优先、npmmirror 回退、24 小时节流）、device-code 登录/重新登录/退出 |
| `.../FeishuState.kt` | 运行状态快照与 `host.settings()` 持久化（版本/应用/账号/权限/最新版本/上次更新检查；首次读旧 `state.json` 后迁移删除） |
| `.../FeishuUpdate.kt` | 更新源纯逻辑：GitHub tag / npmmirror 目录解析、版本比较、`checksums.txt` 解析与 arm64 校验和匹配、release 下载地址与节流/状态判定 |
| `.../FeishuContent.kt` | 版本、下载地址、sha256、超时、阶段与错误文案、更新状态文案、技能 markdown，以及页面用的 `host.t`/`host.tf` 双语辅助 |
| `.../FeishuCli.kt` | `lark-cli` 命令构造与 JSON 输出解析（版本、状态、登录、配置链接） |
| `.../FeishuCards.kt` | 自定义消息卡：卡片类型/kind 常量、payload 编解码与 Compose 卡片（统一 header/count/rows/footer，最多 8 行，空结果与不可解析兜底；聊天、消息、日程、云文档搜索、文档摘要、任务六类） |
| `.../FeishuPage.kt` | 插件页面 Compose：标题与用途、状态摘要（运行环境/组件/账号）、单一主操作、账号次要操作、安装/连接进度与取消、更新提示与重试、授权链接打开/复制、应用权限入口、错误提示与重试、账号操作确认 |
| `src/test/resources/fixtures/` | 由真实 `lark-cli 1.0.100` 产生的 JSON 输出夹具（见该目录 README）与 `_notice` 变体 |
| `src/test/kotlin/...` | JVM 单测：命令匹配、JSON 提取/`_notice`、观测文本、卡片 payload、工具桥行为、更新版本/校验和与失败回退 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :feishu:testDebugUnitTest :feishu:verifyPlugin`，产物 `feishu/build/plugin/feishu.ddp`。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途，再用 `DdStatusRow`（运行环境/组件/账号）组成状态摘要，刷新时保留上次值、检查中的行显示行内加载圈；状态值写「已就绪/可更新/正在检查更新…/暂时无法检查更新/未登录」这类人类可读结果，不展示版本号与命令行术语。
- 日志：桥内部成功路径只用 debug（`feishu.bridge`），warn 仅用于 hook 异常、JSON/观测解析失败与卡片投递失败。
- 卡片桥：只识别单条、无管道/重定向的读取命令（`im +chat-list`、`im +chat-messages-list`、`calendar +agenda`、`drive +search`、`docs +fetch`、`task +get-my-tasks`）；hook 内部全部 `runCatching`，异常不会阻断 bash。成功时把 CLI JSON 换成完整可读观测（摘要 + id/时间/发送者/数量/链接，长交互消息只给摘要与链接），并投递 `chat.message:feishu.card` 卡片；模型观测里不出现原始 JSON/HTML 或「卡片」措辞。卡片只出现在回答区，工具过程仍显示原始 bash 工具卡。
- 进度与取消：安装/更新显示确定进度与百分比，登录轮询显示不确定进度；两者都提供取消，取消会终止当前命令并回滚未完成的 CLI 安装；更新不退出登录、保留账号配置。
- 更新检查：安装后首次与手动重试时查询 GitHub `releases/latest` tag，失败回退 npmmirror `lark-cli` 目录；下载对应 `checksums.txt` 并要求 `linux-arm64` 归档 sha256 匹配，校验不通过视为检查失败；24 小时内自动节流，组件状态行显示「正在检查更新…/可更新/已就绪/暂时无法检查更新」，可更新时用 `DdNoticeBar` 的「更新」动作复用安装进度与取消回滚。
- 授权链接：连接过程中只提供「打开浏览器」和「复制链接」（`host.ui().openUrl` / `host.clipboard()`），不直接展示原始 URL。
- 权限与错误：应用权限在摘要卡下方以「应用权限」行显示「已开通 / N 项待开通」并进入飞书开放平台管理；缺失权限用 `DdNoticeBar` 说明「开通并重新登录后生效」。错误用 `DdNoticeBar` 就地展示并按失败的操作重试，Linux 不可用时给重新检查入口。
- `lark-cli` 来自 npmmirror 或 GitHub release（v1.0.100，sha256 固定，与官方 `checksums.txt` 一致），经 `host.network().download` 下载后由 `host.linux()` 在 proot 内解包、校验版本并安装到 `/usr/local/bin/lark-cli`；`/data` 已 bind 进 proot，`host.dataDir` 下的暂存目录与状态文件可直接被 Linux 访问。
- 连接沿用 device-code 流程：`config init --new` 输出创建应用链接，`auth login --no-wait` 输出授权链接，`auth login --device-code` 轮询完成。
- 技能：读取类命令要求单条普通 CLI 调用、不接管道/head/jq，空日程与空任务是合法空结果；个人数据默认用户身份，`auth` 命令不用 `--as`；回答不粘贴原始 JSON/XML/HTML；发送、修改、删除前先询问并保留 `--dry-run` 与退出码 10 的确认规则。
- 无宿主资源：页面文案全部 `localizedText(...)` 或 `host.t`/`host.tf`（zh/en），图标走 `asset:`。页面不播放自定义动效，状态依靠摘要行与进度条即时反馈。
- 卸载时删除 CLI 与 `~/.lark-cli`、`~/.local/share/lark-cli`，并清空 `host.settings()`。
- 安装完成通过 `host.notifications().notifyChecked` 发稳定 id 通知（带「打开插件页」动作，由 `notificationAction` hook 处理）；重新登录与退出登录前用 `host.ui().confirm` 确认。
