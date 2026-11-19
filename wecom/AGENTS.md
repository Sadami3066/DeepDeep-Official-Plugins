# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。bash 的输出原样交给模型，由模型读取 JSON 作答。插件页负责安装、扫码授权与组件更新。CLI 更新/安装流程复用仓库内 `:cli-common`（`implementation(project(":cli-common"))`，类随插件一起进 `.ddp`）。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.6.2、versionCode 15、Compose，plugin-api 1.4.0、design 0.11.0、`:cli-common` 依赖）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.6.2/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.4.3/capabilities/entryClass/provides（skill `deepdeep-wecom`、`uiSlots` 为空、page `main`） |
| `src/main/kotlin/.../WecomInstall.kt` | 安装计划与错误文案：备份/替换/回滚脚本、解包验版本、`wecom-cli` 版本解析；执行走 `:cli-common` 的 `CliInstallRunner`（下载、sha512 校验、进度、取消回滚） |
| `src/main/kotlin/.../WecomReleases.kt` | 更新源与缓存：npm registry `latest` 解析 version/tarball/integrity（sha512）、`WecomRelease` 缓存（24 小时节流）、`CliUpdateSource` 实现、把 registry release 映射成 `CliRelease(sha512Base64)`、pinned 1.3.4（sha256）回退；版本比较委托 `CliVersions` |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、版本参数化的解包/安装/回滚/卸载命令、双下载源、版本/授权/二维码解析 |
| `src/main/kotlin/.../WecomStatus.kt` | `host.settings()`：CLI 版本、授权、Bot ID（旧 `wecom-status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / contributions（仅通知动作）/ ui（页面，无槽位）；技能文档内含读取与写操作纪律 |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：标题与用途、状态摘要、扫码优先、单一主操作、账号次要操作、安装/更新/连接/退出分阶段进度与取消、更新提示与 24 小时检查（`CliUpdateCheckController`）、错误重试、危险操作确认 |
| `src/main/kotlin/.../WecomTexts.kt` | 页面 zh/en 文案与 `host.t`/`host.tf` 扩展 |
| `src/test/kotlin/...` | 更新源与 sha512 解析、版本比较、下载源映射的单测 |
| `src/test/resources/fixtures/` | 真机 npm registry 采样（`registry-latest.json`）与说明 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :wecom:testDebugUnitTest :wecom:verifyPlugin`，产物 `wecom/build/plugin/wecom.ddp`；`.ddp` 内含 `io.github.whywood.deepdeep.plugin.cli.*` 类。
- 安装走 `host.network().download`：优先用更新检查拿到的 npm registry release（`dist.tarball` + `dist.integrity` 的 sha512），由共享 `CliInstallRunner` 下载后本地重算 sha512 校验，通过才解包；registry 不可用时首次安装回退内置 1.3.4 + sha256。安装脚本备份旧二进制，版本不符或中途取消回滚；成功后删除备份。更新不退出登录、不碰 `/root/.config/wecom`。
- 更新检查：`https://registry.npmjs.org/@wecom/cli-linux-arm64/latest` → version/tarball/integrity，`WecomReleaseSource` 收敛异常为 `failed`，单飞与 24 小时节流由 `CliUpdateCheckController` 负责；页面状态为已就绪/待更新（检查中、失败带重试）；缺 sha512 或校验不符一律拒绝更新并保留现有组件。
- 对话：bash 返回 CLI 的原始 stdout；技能要求每次一条普通 CLI 调用，读取 JSON 字段作答，先给结论与下一步，不贴原始 JSON。未授权或登录失效引导到插件页重新登录，权限不足说明缺少什么，其余按 CLI 错误文本如实说明。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后用 `DdImage` 显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp。首屏先给一句标题与用途；连接进行时扫码区排在视觉首位，二维码框固定 220dp，加载与展示在同一尺寸内切换，主区域不跳动；状态摘要用 `DdStatusRow`（运行环境/组件/授权）组成，值写「已就绪/待更新/未安装/已连接」这类人类可读结果，不含 Bot ID 与版本号，刷新时保留旧值并用行内加载圈标记检查中。
- 安装、更新、连接、退出各有阶段文案与确定进度条 `DdProgressBar`：安装/更新把共享安装器的 DOWNLOADING/VERIFYING/INSTALLING/FINISHING 事件映射成下载百分比与解包/安装阶段，等待扫码按 6 分钟窗口推进，退出按阶段推进；都可取消（完成后阶段禁用取消，取消时共享安装器回滚未完成的 CLI 安装）。
- 失败与超时用 `DdNoticeBar` 就地展示，动作按失败的操作重试；Linux 环境不可用时给重新检查入口。连接中扫码区置顶，二维码出现不引起布局跳动；页面不播放自定义动效。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让提示词立即按新状态物化；CLI 安装完成发稳定 id 通知，退出登录与重新授权前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
