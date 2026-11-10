# sysu · 中山大学 CLI 插件

模块名、插件 id 与安装路径均为 `sysu`（`io.github.whywood.deepdeep.plugin.sysu`），对外名称「中山大学 CLI」。
分发 Node.js 版 `sysu` CLI：教务、在线教学、体育场馆、网费与统一认证换票。
打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=5/capabilities/entryClass/provides（skills `deepdeep-sysu`、page `main`、publisher `milous`） |
| `src/main/assets/sysu/**` | Node.js 校园 CLI（bin 与 src），安装时从 `host.assetsDir` 拷入 proot |
| `src/main/kotlin/.../SysuCli.kt` | 安装路径、Node 检查、登录/查询命令与输出解析 |
| `src/main/kotlin/.../SysuStatus.kt` | `dataDir/status.json`：安装状态、登录账号 |
| `src/main/kotlin/.../SysuPlugin.kt` | `DeepDeepPlugin` 入口：availability / skills / promptSections / ui |
| `src/main/kotlin/.../SysuPage.kt` | 插件自绘页：学号/密码表单、保存并登录、安装与登录状态 |
| `src/main/kotlin/.../SysuContent.kt` | zh/en 文案与技能 markdown（`deepdeep-sysu`） |

## 要点

- 凭证存 `host.secrets()`（netid/password），页面只回显 netid，密码不回显。
- 安装：检查 Node 版本 → 从 `host.assetsDir/sysu/**` 复制到 proot（`/root/projects/sysu` 与 `/usr/local/bin/sysu`）。
- 配置在 `/root/.config/sysu/`：`credentials`、`jwxt/session.json`、`jwxt/mfa.json`、`lms.json`、`gym.json`、`netpay.json`（`jwxt/` 是 CLI 的教务站点目录，与插件名无关）。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。
