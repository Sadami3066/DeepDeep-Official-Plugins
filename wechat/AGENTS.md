# wechat · 微信导出插件

模块名与插件 id 为 `wechat`（`io.github.whywood.deepdeep.plugin.wechat`），对外名称「微信导出」；通过宿主 Linux 服务运行随包 `wechat-cli`，按日期范围导出微信本地记录交给 Agent 总结。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `src/main/assets/plugin.json` | 清单：id/version 0.5.1/apiLevel=6/`ui.apiLevel`=2/minHostVersion 1.0.8/capabilities（`device`/`linux`/`media`/`notifications`/`settings`/`ui`）/icon/entryClass/provides（skills `deepdeep-wechat`、page `main`） |
| `src/main/assets/wechat-cli/**` | 随包 Node CLI（`bin/wechat.mjs` 与 `src/lib/**`），同步、解密、导出微信记录 |
| `src/main/kotlin/.../WechatCli.kt` | 安装路径、Node 版本要求、状态与同步/导出/导出文件发布命令拼装、`/data/user/0` → `/data/data` 路径归一 |
| `src/main/kotlin/.../WechatPlugin.kt` | `DeepDeepPlugin` 入口：availability（arm64、Linux 可用、数据可读）/ skills / promptSections / ui（页面带 `icon.png`）/ 卸载清理；导出后把 Markdown 拷到 `host.dataDir/exports` 供打开与分享 |
| `src/main/kotlin/.../WechatPage.kt` | 插件自绘页：DdStatusRow 状态摘要、DdDatePicker 日期范围与就地校验、导出进度与失败重试、结果打开/分享、同步与更新入口 |
| `src/main/kotlin/.../WechatStatus.kt` | 安装与导出状态、`WechatExport` 结果；导出日期范围用 `host.settings()` 记忆 |
| `src/main/kotlin/.../WechatContent.kt` | zh/en 文案与技能 markdown（`deepdeep-wechat`） |

## 要点

- 运行前提：arm64 设备、系统访问为 Root 档（要读 `/data/data/com.tencent.mm`）、Linux 环境可用；Node 版本要求见 `WechatCli.MIN_NODE_MAJOR`，首次安装会补装 `sqlcipher`。微信本地库加密与版本差异都可能导致失败，失败要如实报错。
- 安装：把 `host.assetsDir` 下的 `wechat-cli` 拷到 `/root/projects/wechat-cli`，软链 `/usr/local/bin/wechat`；数据、快照与导出在 `/root/.wechat`；卸载删除程序、软链与数据。
- 命令由插件拼装后经 `host.linux()` 执行：`sync` → `ingest` → `export --from/--to`（Markdown）；日期格式 `YYYY-MM-DD`。CLI 只做清洗与导出，不筛选、不排序、不摘要。
- Root chroot 里 `/data/user/0` 是空目录，真实数据在 `/data/data`；`WechatCli.linuxPath` 已统一归一，新增路径处理不要绕开它。
- 导出结果：CLI 仍写到 `/root/.wechat/exports`，插件用 `WechatCli.publishCommand` 复制到 `host.dataDir/exports`，再通过 `host.media().open` 与 `host.ui().share` 打开/分享；`/data` 已 bind 进 Linux 环境。
- 页面骨架：横向 20dp、顶部 12dp、底部 24dp；不写顶栏标题。状态摘要用 `DdStatusRow`，刷新时保留旧值只标记 busy；日期范围用 `DdDatePicker` 并在卡内提示起止顺序；动效统一走 `DdMotion` 并尊重 `LocalDdMotionEnabled`。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。设备 ABI 用 `host.device().info.abis` 判断；安装、同步与导出完成发稳定 id 通知，轻结果用 `host.ui().snackbar` 提示。
