# feishu · 飞书外部插件

DeepDeep 官方飞书插件，当前 2.2.0，打成外部 `.ddp`：无工具，靠技能与提示词段驱动 `lark-cli`；插件页面是唯一界面，负责 CLI 安装/更新、飞书应用创建、账号授权、应用权限与退出登录。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api` 与 `design`，不引用宿主内部类型。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName/Compose，plugin-api 1.2.0、design 0.9.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（含 `clipboard`）/entryClass/icon/provides（skills `deepdeep-feishu`、pages `main`；页面内容直接挂在 `PluginPage.content`，不需要槽位） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的飞书图标 |
| `src/main/kotlin/.../FeishuPlugin.kt` | 入口：availability/onEnable/skills/promptSections/ui/uninstall 与提示词正文 |
| `.../FeishuRuntime.kt` | 页面动作：状态检查、CLI 安装/更新、device-code 登录/重新登录/退出；安装与登录可取消，状态保留上次值，错误记录可重试的操作 |
| `.../FeishuState.kt` | 运行状态快照与 `host.settings()` 持久化（版本/应用/账号/权限；首次读旧 `state.json` 后迁移删除） |
| `.../FeishuContent.kt` | 版本、下载地址、sha256、超时、阶段与错误文案、技能 markdown，以及页面用的 `host.t`/`host.tf` 双语辅助 |
| `.../FeishuCli.kt` | `lark-cli` 命令构造与 JSON 输出解析（版本、状态、登录、配置链接） |
| `.../FeishuPage.kt` | 插件页面 Compose：首屏状态摘要、安装/连接进度与取消、授权链接复制/打开、缺失权限列表、错误提示与重试、账号操作确认 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :feishu:verifyPlugin`，产物 `feishu/build/plugin/feishu.ddp`。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp；宿主已有顶栏，页面内不再重复标题。首屏用 `DdStatusRow` 展示运行环境/CLI/账号/连接状态，刷新时保留上次值。
- 进度与取消：安装显示确定进度与百分比，登录轮询显示不确定进度；两者都提供取消，取消会终止当前命令并回滚未完成的 CLI 安装。
- 授权链接：device-code 链接同时提供「打开浏览器」和「复制链接」（`host.ui().openUrl` / `host.clipboard()`）。
- 权限与错误：缺失权限逐项列出（最多 6 项，其余计数），错误用 `DdNoticeBar` 就地展示并按失败的操作重试。
- `lark-cli` 来自 npm 镜像或 GitHub release（v1.0.98，sha256 固定），经 `host.network().download` 下载后由 `host.linux()` 在 proot 内解包、校验版本并安装到 `/usr/local/bin/lark-cli`；`/data` 已 bind 进 proot，`host.dataDir` 下的暂存目录与状态文件可直接被 Linux 访问。
- 连接沿用 device-code 流程：`config init --new` 输出创建应用链接，`auth login --no-wait` 输出授权链接，`auth login --device-code` 轮询完成。
- 无宿主资源：页面文案全部 `localizedText(...)` 或 `host.t`/`host.tf`（zh/en），图标走 `asset:`。动效统一走 `DdMotion` 并尊重 `LocalDdMotionEnabled`。
- 卸载时删除 CLI 与 `~/.lark-cli`、`~/.local/share/lark-cli`，并清空 `host.settings()`。
- 安装完成通过 `host.notifications().notifyChecked` 发稳定 id 通知（带「打开插件页」动作，由 `notificationAction` hook 处理）；重新登录与退出登录前用 `host.ui().confirm` 确认。
