# sysu · 中山大学 CLI 插件

模块名、插件 id 与安装路径均为 `sysu`（`io.github.whywood.deepdeep.plugin.sysu`），对外名称「中山大学 CLI」。
分发 Node.js 版 `sysu` CLI：教务、在线教学、体育场馆、网费与统一认证换票。
打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion/capabilities（含 `chat`）/entryClass/provides（skills `deepdeep-sysu`、uiSlots `chat.message:sysu.card`、page `main`、publisher `milous`） |
| `src/main/assets/sysu/**` | Node.js 校园 CLI（bin 与 src），安装时从 `host.assetsDir` 拷入 proot |
| `src/main/kotlin/.../SysuCli.kt` | 安装路径、Node 检查、登录/查询命令与输出解析 |
| `src/main/kotlin/.../SysuStatus.kt` | `host.settings()`：安装状态、登录账号（旧 `status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../SysuPlugin.kt` | `DeepDeepPlugin` 入口：availability / skills / promptSections / contributions（`SysuBridge` hooks）/ ui（聊天卡片绑定） |
| `src/main/kotlin/.../SysuPage.kt` | 插件自绘页：标题与用途、状态摘要卡（账号/登录状态/运行环境/服务版本）、登录与验证码一条流程、就地错误重试与退出登录 |
| `src/main/kotlin/.../SysuContent.kt` | zh/en 文案与技能 markdown（`deepdeep-sysu`）；技能要求查询类命令用单条 `--json` 命令 |
| `src/main/kotlin/.../SysuCommand.kt` | bash 命令解析：只认单条 `sysu` 命令（拒绝管道/重定向/`&&`/`cd` 前缀），识别支持的 `--json` 查询 |
| `src/main/kotlin/.../SysuInvocationRegistry.kt` | callId → 命令 的有界关联表（64 条，按会话清理，在 after 钩子取走） |
| `src/main/kotlin/.../SysuBridge.kt` | `beforeToolCall` 记录命令、`afterToolExecution` 改写结果并投递卡片；TGC/验证码错误转换为可操作提示，不透出登录票据 |
| `src/main/kotlin/.../SysuJson.kt` | `--json` 输出清洗（ANSI/`[exit]` 尾标）与课表/成绩/考试/学分/LMS/场馆/网费 DTO |
| `src/main/kotlin/.../SysuObservation.kt` | 模型可读观察文本与卡片数据：完整日期、课名/学分/绩点、考试、课程待办、场馆空位、网费状态；院系前缀去重、学分双方案标注 |
| `src/main/kotlin/.../SysuGymDisplay.kt` | 场馆时段整理：按日期分组，区分切片时段与覆盖多段的长时段，避免混排 |
| `src/main/kotlin/.../SysuCardModel.kt` | 卡片载荷 DTO（kind/state/title/count/groups/footer）、8 行上限与编解码 |
| `src/main/kotlin/.../SysuChatCard.kt` | `chat.message:sysu.card` Compose 卡片：统一头部（图标/标题/数量）、分组行、footer，空/错误/加载态 |
| `src/test/**` | 命令匹配、注册表、JSON 解析、观察文本、无 TGC/undefined 泄漏、卡片 DTO、netpay/日期/场馆回退测试；`fixtures/` 为真机只读输出的脱敏结构样本 |

## 要点

- 凭证存 `host.secrets()`（netid/password），页面只回显 netid，密码默认隐藏、可切换显示。
- 页面结构：顶部标题 + 一句用途 → 状态摘要卡（`DdStatusRow` × 账号/登录状态/运行环境/服务版本，行间 `DdHairline`）→ 环境与错误 `DdNoticeBar`（可重试、可就地重新检查）→ 登录/验证码/已登录阶段各只有一个主操作 `DdPrimaryButton`，次级动作用 `DdTextAction`（返回、重发、刷新、退出）→ 底部一句说明；不展示 CLI 参数、JSON 或表单内部字段名。
- 登录与验证码是一条流程：表单 → 验证码（返回、60 秒重发倒计时）→ 已登录，切换用 `Crossfade` 并读 `LocalDdMotionEnabled`；校验就地显示，键盘用 `imePadding` 避让，底部留 24dp；加载中用 `DdLoadingBlock`。
- 安装：检查 Node 版本 → 从 `host.assetsDir/sysu/**` 复制到 proot（`/root/projects/sysu` 与 `/usr/local/bin/sysu`）。
- 配置在 `/root/.config/sysu/`：`credentials`、`jwxt/session.json`、`jwxt/mfa.json`、`lms.json`、`gym.json`、`netpay.json`（`jwxt/` 是 CLI 的教务站点目录，与插件名无关）。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。设备 ABI 用 `host.device().info.abis` 判断；安装完成与登录成功发稳定 id 通知，退出登录前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
- 查询桥不新增 PluginTool：`bash` 里运行 `sysu jwxt|lms|gym|netpay ... --json` 时，插件用 hooks 关联 callId，把 JSON 整理成完整观察文本替换给模型，同时用 `host.chat().postCustomMessage` 在回答区展示摘要卡；非支持命令只在校验到登录票据/会话信息时脱敏，写操作与确认规则不变。
- 卡片与观察文本不出现 TGC、session.json、`undefined` 或「卡片」字样；`--json` 才进入结构化路径，技能与 prompt 已要求单条命令（不加管道/head），非 JSON 命令保持 CLI 原始输出。
- 形状取自真机只读命令（`sysu jwxt schedule/scores/exams/credits --json`、`sysu lms/todo --json`、`sysu gym slots --json`、`sysu netpay --json`）的结构；测试夹具已去掉姓名、学号与成绩等个人信息。已知无法从数据区分的情形（如场馆某时段是没空位还是没排期）不臆测。
