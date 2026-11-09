# wecom · 企业微信插件

外部 `.ddp` 插件模块：安装官方 `wecom-cli`、扫码授权后，通过技能与提示词驱动 CLI 操作企业微信。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=4/capabilities/entryClass/provides（skills `deepdeep-wecom`、page `main`） |
| `src/main/kotlin/.../WecomCli.kt` | CLI 常量、双下载源、sha256、版本/授权/二维码解析、解包/安装/回滚/卸载命令 |
| `src/main/kotlin/.../WecomStatus.kt` | `dataDir/wecom-status.json`：CLI 版本、授权、Bot ID |
| `src/main/kotlin/.../WecomPlugin.kt` | `DeepDeepPlugin` 入口：availability / uninstall / skills / promptSections / ui |
| `src/main/kotlin/.../WecomPage.kt` | 插件自绘页：状态、安装/更新 CLI、连接/重新授权（二维码）、退出登录、进度 |
| `src/main/kotlin/.../WecomTexts.kt` | zh/en 文案与技能 markdown |

## 要点

- 安装走 `host.network().download`（npmjs → npmmirror 回退，宿主校验 sha256）+ `host.linux().exec` 解包安装到 proot `/usr/local/bin/wecom-cli`；`/data` 已 bind，`host.dataDir` 可直接作为 cwd。
- 授权在插件页完成：`wecom-cli auth init --noninteractive --no-browser --output-qrcode`，二维码文件写在 `host.files().workspaceDir`（CLI 仅允许写 cwd 与 /tmp，写 `dataDir` 会报 PermissionError 893006），轮询完整 PNG 后显示二维码与链接、读完删除；connect 失败时错误文案带合并输出尾部，`refresh` 保留上次已知状态不闪。退出登录删除凭据。
- 页面动作结束调用 `host.requestContributionsRefresh()`，让工具/提示词立即按新状态物化。
