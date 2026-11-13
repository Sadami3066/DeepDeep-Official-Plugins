# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName 2.2.0/Compose，plugin-api 1.2.0、design 0.9.0）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version 2.2.0/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities/entryClass/provides（skills `deepdeep-wecom`、page `main`） |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、双下载源、sha256、版本/授权/二维码解析、解包/安装/回滚/卸载命令 |
| `src/main/kotlin/.../WecomStatus.kt` | `host.settings()`：CLI 版本、授权、Bot ID（旧 `wecom-status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / ui |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：扫码优先、状态摘要、安装/连接/退出分阶段进度与取消、错误重试、危险操作确认 |
| `src/main/kotlin/.../WecomTexts.kt` | 页面 zh/en 文案 |

## 要点

- 安装走 `host.network().download`（npmjs → npmmirror 回退，宿主校验 sha256）+ `host.linux().exec` 解包安装到 proot `/usr/local/bin/wecom-cli`；`/data` 已 bind，`host.dataDir` 可直接作为 cwd。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后用 `DdImage` 显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp，组间 16dp；扫码区在操作进行时排在视觉首位，二维码框固定 220dp，加载与展示在同一尺寸内切换，扫码过程中主区域不跳动；状态摘要用 `DdStatusRow`（运行环境/组件/授权/机器人），刷新时保留旧值并用行内加载圈标记检查中。
- 安装、连接、退出各有阶段文案与确定进度条 `DdProgressBar`：安装显示下载百分比并映射到解包/安装阶段，等待扫码按 6 分钟窗口推进，退出按阶段推进；三者进行中都可取消（完成后阶段禁用取消），安装取消会回滚未完成的 CLI 替换。
- 失败与超时用 `DdNoticeBar` 就地展示，动作按失败的操作重试；Linux 环境不可用时给重新检查入口。操作切换、二维码出现与消失都读 `LocalDdMotionEnabled`，关闭动画时直接切换。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让工具/提示词立即按新状态物化；CLI 安装完成发稳定 id 通知，退出登录与重新授权前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
