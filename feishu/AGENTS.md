# feishu · 飞书外部插件

DeepDeep 官方飞书插件，打成外部 `.ddp`：无工具，靠技能与提示词段驱动 `lark-cli`；插件页面是唯一界面，负责 CLI 安装/更新、飞书应用创建、账号授权、应用权限与退出登录。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api`，不引用宿主内部类型。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName/Compose）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=4/capabilities/minHostVersion/entryClass/icon/provides（skills `deepdeep-feishu`、pages `main`；页面内容直接挂在 `PluginPage.content`，不需要槽位） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的飞书图标 |
| `src/main/kotlin/.../FeishuPlugin.kt` | 入口：availability/onEnable/skills/promptSections/ui/uninstall 与提示词正文 |
| `.../FeishuRuntime.kt` | 页面动作：状态刷新、CLI 安装/更新、device-code 登录/重新授权/退出；动作在 `host.scope` 后台执行并写回页面状态 |
| `.../FeishuState.kt` | 运行状态快照与 `host.dataDir/state.json` 持久化（版本/应用/账号/权限） |
| `.../FeishuContent.kt` | 版本、下载地址、sha256、超时、本地化文案与技能 markdown |
| `.../FeishuCli.kt` | `lark-cli` 命令构造与 JSON 输出解析（版本、状态、登录、配置链接） |
| `.../FeishuPage.kt` | 插件页面 Compose：CLI 版本、连接状态、安装/连接/权限/退出按钮与授权链接 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :feishu:verifyPlugin`，产物 `feishu/build/plugin/feishu.ddp`。
- `lark-cli` 来自 npm 镜像或 GitHub release（v1.0.98，sha256 固定），经 `host.network().download` 下载后由 `host.linux()` 在 proot 内解包、校验版本并安装到 `/usr/local/bin/lark-cli`；`/data` 已 bind 进 proot，`host.dataDir` 下的暂存目录与状态文件可直接被 Linux 访问。
- 连接沿用旧 device-code 流程：`config init --new` 输出创建应用链接，`auth login --no-wait` 输出授权链接，`auth login --device-code` 轮询完成；页面展示链接与「打开浏览器」按钮。
- 无宿主资源：页面文案全部 `LocalizedText.Literal`（zh/en），图标走 `asset:`。
- 卸载时删除 CLI 与 `~/.lark-cli`、`~/.local/share/lark-cli`，并清空 `state.json`。
