# wechat · 微信导出插件

模块名与插件 id 为 `wechat`（`io.github.whywood.deepdeep.plugin.wechat`），对外名称「微信导出」；通过宿主 Linux 服务运行随包 `wechat-cli`，按日期范围导出微信本地记录交给 Agent 总结。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `src/main/assets/plugin.json` | 清单：id/version/capabilities（`linux`）/entryClass/provides（skills `deepdeep-wechat`、page `main`） |
| `src/main/assets/wechat-cli/**` | 随包 Node CLI（`bin/wechat.mjs` 与 `src/lib/**`），同步、解密、导出微信记录 |
| `src/main/kotlin/.../WechatCli.kt` | 安装路径、Node 版本要求、状态与同步/导出命令拼装、`/data/user/0` → `/data/data` 路径归一 |
| `src/main/kotlin/.../WechatPlugin.kt` | `DeepDeepPlugin` 入口：availability（arm64、Linux 可用、数据可读）/ skills / promptSections / ui / 卸载清理 |
| `src/main/kotlin/.../WechatPage.kt` | 插件自绘页：环境状态、安装、同步与导出操作 |
| `src/main/kotlin/.../WechatStatus.kt` | 插件数据目录里的安装与导出状态 |
| `src/main/kotlin/.../WechatContent.kt` | zh/en 文案与技能 markdown（`deepdeep-wechat`） |

## 要点

- 运行前提：arm64 设备、系统访问为 Root 档（要读 `/data/data/com.tencent.mm`）、Linux 环境可用；Node 版本要求见 `WechatCli.MIN_NODE_MAJOR`，首次安装会补装 `sqlcipher`。微信本地库加密与版本差异都可能导致失败，失败要如实报错。
- 安装：把 `host.assetsDir` 下的 `wechat-cli` 拷到 `/root/projects/wechat-cli`，软链 `/usr/local/bin/wechat`；数据、快照与导出在 `/root/.wechat`；卸载删除程序、软链与数据。
- 命令由插件拼装后经 `host.linux()` 执行：`sync` → `ingest` → `export --from/--to`（Markdown）；日期格式 `YYYY-MM-DD`。CLI 只做清洗与导出，不筛选、不排序、不摘要。
- Root chroot 里 `/data/user/0` 是空目录，真实数据在 `/data/data`；`WechatCli.linuxPath` 已统一归一，新增路径处理不要绕开它。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。
