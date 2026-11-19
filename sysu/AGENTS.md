# sysu · 中山大学 CLI 插件

模块名、插件 id 与安装路径均为 `sysu`（`io.github.whywood.deepdeep.plugin.sysu`），对外名称「中山大学 CLI」。
分发 Node.js 版 `sysu` CLI：教务、在线教学、体育场馆、网费与统一认证换票。CLI 通过 bash 工具直接运行，输出文本由模型自己读；插件不做卡片、不解析 bash 命令。打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | `implementation(project(":cli-common"))`（只用于 CLI 更新/安装与文本工具，类随插件进 `.ddp`）；版本 2.7.3 / versionCode 23；design 0.11.0；内置 CLI 3.6.0 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion=1.4.3/capabilities（含 `logs`）/entryClass/provides（skills `deepdeep-sysu`、page `main`、publisher `milous`；无 uiSlots） |
| `src/main/assets/sysu/**` | Node.js 校园 CLI 源码（bin 与 src，`--json` 为机器格式）；安装时从 `host.assetsDir` 拷入 proot；`src/netpay.js` 遇到登录页、`success:false` 或登录失效文案时自动 `login()` 重试一次；`src/output.js` 负责文本/JSON 输出与错误出口，`src/format.js` 是主查询的文本版式 |
| `src/main/kotlin/.../SysuCli.kt` | 安装路径、Node 检查、四站状态命令（`session.json`/`lms.json`/`gym.json`/`netpay.json`）、四站 login 命令（一律带 `--json`）与验证码判定 |
| `src/main/kotlin/.../SysuStatus.kt` | `host.settings()`：安装状态、登录账号、教务/在线教学/场馆/网费四站连接状态（旧 `status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../SysuPlugin.kt` | `DeepDeepPlugin` 入口：availability / skills / promptSections / contributions（只有通知点击）/ ui（只有插件页）；`loginSite` 执行单站 login 并返回成功/需要验证码/失败；`onUpgrade` 调用 `ensureInstalled` 重装内置 CLI，`onEnable` 只读状态 |
| `src/main/kotlin/.../SysuPage.kt` | 插件自绘页：标题与用途、状态摘要卡（账号 + 四站状态 + 运行环境 + 服务版本）、登录/验证码一条流程、单站验证码提示、连接未登录服务、就地错误重试与退出登录 |
| `src/main/kotlin/.../SysuContent.kt` | zh/en 文案与技能 markdown（`deepdeep-sysu`）：命令清单、文本输出、写操作确认与登录处理 |
| `src/test/**` | 四站状态解析、登录 JSON 判定、事件通知与页面辅助逻辑测试；`fixtures/` 为真机只读输出的脱敏结构样本 |

## 要点

- 没有 PluginTool、没有 bash 钩子、没有卡片槽位：模型在 bash 里运行 `sysu …`，直接读 stdout 作答。宿主对 bash 结果不做任何处理。
- CLI 默认输出人类可读文本；`--json` 输出 JSON，插件内部（登录/换票）一律显式加 `--json`，由 `SysuCli.kt` 的命令构造器保证。
- 站点必须给显式子命令：`sysu jwxt schedule`、`sysu lms courses`、`sysu gym info`、`sysu netpay info`；不带子命令打印该站点帮助。裸 `sysu jwxt` 不再默认查课表。
- 报错走 stderr 并以非 0 退出；`--json` 时错误输出 `{ok:false,error:{message,code}}`。会话失效时 CLI 自带「重新登录」提示，模型据此执行对应站点的 login（教务 `sysu login`，LMS `sysu lms login`，场馆 `sysu gym login`，网费 `sysu netpay login`）；需要企业微信验证码或 login 失败才引导用户到插件页。
- 登录与验证码只在插件页完成，对话里不向用户索要账号密码或验证码；验证码只在插件页输入，或用户主动给出后 `sysu login --code <4 位>`。
- 凭据与会话不进对话：不要 `cat` `/root/.config/sysu/**`，不要把 ticket、Cookie、session.json 内容贴给用户；技能里已写明。
- 凭证存 `host.secrets()`（netid/password），页面只回显 netid，密码默认隐藏、可切换显示。
- 页面结构：顶部标题 + 一句用途 → 状态摘要卡（账号、教务/在线教学/场馆/网费四行、运行环境、服务版本，行间 `DdHairline`）→ 环境与错误 `DdNoticeBar`（可重试、可就地重新检查）→ 登录/验证码/已登录阶段各只有一个主操作 `DdPrimaryButton`，次级动作用 `DdTextAction`（返回、重发、刷新、连接未登录服务、退出）→ 底部一句说明；不展示 CLI 参数、JSON 或表单内部字段名。
- 登录与验证码是一条流程：表单 → 验证码（返回、60 秒重发倒计时）→ 已登录，切换用 `Crossfade` 并读 `LocalDdMotionEnabled`；验证码步骤会标明是「教务」还是某个站点（在线教学/体育场馆/网费）。校验就地显示，键盘用 `imePadding` 避让，底部留 24dp；加载中用 `DdLoadingBlock`。
- 登录顺序：`sysu login --send-code --json` 成功后依次执行 `sysu lms login --json`、`sysu gym login --json`、`sysu netpay login --json`，每站复用同一份 TGC/凭据，页面逐项更新四站状态；单站需要验证码时停在该站提示输入，提交 `sysu <site> login --code <N> --json` 后继续后面的站点。任一站失败不中断其余站点，已登录页可点「连接其余 N 个服务」重试。
- 安装：检查 Node 版本 → 从 `host.assetsDir/sysu/**` 复制到 proot（`/root/projects/sysu` 与 `/usr/local/bin/sysu`）；插件升级走 `onUpgrade` 重装内置 CLI，`onEnable` 不重装。
- 配置在 `/root/.config/sysu/`：`credentials`、`jwxt/session.json`、`jwxt/mfa.json`、`lms.json`、`gym.json`、`netpay.json`（`jwxt/` 是 CLI 的教务站点目录，与插件名无关）。状态解析只读这些文件，不发网络请求。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。设备 ABI 用 `host.device().info.abis` 判断；安装完成与登录成功发稳定 id 通知，退出登录前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
- 文本版式在 `src/main/assets/sysu/src/format.js`：课表按天分块、成绩对齐表格、场馆按类型分组、网费按服务行；其他命令（公告全文、教室占用、课程大纲等）保持 JSON，由模型按字段阅读。
- 技能、CLI `--help` 与 README 的命令清单保持一致；改命令时三处一起改。测试夹具已去掉姓名、学号与成绩等个人信息。
