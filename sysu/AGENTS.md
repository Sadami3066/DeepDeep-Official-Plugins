# sysu · 中山大学 CLI 插件

模块名、插件 id 与安装路径均为 `sysu`（`io.github.whywood.deepdeep.plugin.sysu`），对外名称「中山大学 CLI」。
分发 Node.js 版 `sysu` CLI：教务、在线教学、体育场馆、网费与统一认证换票。CLI 桥、卡片模型与命令关联复用仓库内 `:cli-common` 共享模块；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | `implementation(project(":cli-common"))`（类随插件进 `.ddp`）；版本 2.4.0 / versionCode 14 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion/capabilities（含 `chat`、`logs`）/entryClass/provides（skills `deepdeep-sysu`、uiSlots `chat.message:sysu.card`、page `main`、publisher `milous`） |
| `src/main/assets/sysu/**` | Node.js 校园 CLI（bin 与 src），安装时从 `host.assetsDir` 拷入 proot |
| `src/main/kotlin/.../SysuCli.kt` | 安装路径、Node 检查、登录/查询命令与输出解析 |
| `src/main/kotlin/.../SysuStatus.kt` | `host.settings()`：安装状态、登录账号（旧 `status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../SysuPlugin.kt` | `DeepDeepPlugin` 入口：availability / skills / promptSections / contributions（`SysuToolBridge` hooks）/ ui（聊天卡片绑定） |
| `src/main/kotlin/.../SysuPage.kt` | 插件自绘页：标题与用途、状态摘要卡（账号/登录状态/运行环境/服务版本）、登录与验证码一条流程、就地错误重试与退出登录 |
| `src/main/kotlin/.../SysuContent.kt` | zh/en 文案与技能 markdown（`deepdeep-sysu`）；技能要求查询类命令用单条 `--json` 命令 |
| `src/main/kotlin/.../SysuCommand.kt` | bash 命令解析（词法用 `CliCommandLine`）：只认单条 `sysu` 命令（拒绝管道/重定向/`&&`/`cd` 前缀），识别支持的八类 `--json` 查询 |
| `src/main/kotlin/.../SysuBridge.kt` | 共享 `CliToolBridge` 配置（`CliJsonEnvelope.LENIENT` 裸对象/数组 payload、`JsonElement` observer）；`errorMapper` 做 TGC/验证码与场馆错误映射；KEEP 结果包装做非 JSON 脱敏与 netpay 旧文本提示 |
| `src/main/kotlin/.../SysuJson.kt` | 插件输出 DTO（课表/成绩/考试/学分/LMS/场馆/网费）：从共享层提取的 `JsonElement` 解码，不再自带信封/ANSI 清洗 |
| `src/main/kotlin/.../SysuObservation.kt` | 模型可读观察文本 + 共享 `CliCardModel`（`sections` 按天/日期/类型分组）：完整日期、课名/学分/绩点、考试、课程待办、场馆空位、网费状态；院系前缀去重、学分双方案标注 |
| `src/main/kotlin/.../SysuGymDisplay.kt` | 场馆时段整理：按日期分组，区分切片时段与覆盖多段的长时段，避免混排 |
| `src/main/kotlin/.../SysuCardFormat.kt` | 回答区卡片的展示模型与解析（`SysuPlanDay`/`SysuPlanBlock`/`SysuGradeRow`/`SysuExamEntry`/`SysuLmsCoursePlan`/`SysuTodoEntry`/`SysuGymTypePlan` 等）：从 `CliCardModel` 还原周历网格、成绩、考试、课程、待办、场馆时段与网费到期 |
| `src/main/kotlin/.../SysuCardChrome.kt` | 卡片外壳与共用件：`SysuCardSurface`/`SysuTopLine`/`SysuPill`/`SysuWrapRow`/状态色，以及加载、空、失败与列表回退卡 |
| `src/main/kotlin/.../SysuChatCard.kt` | `chat.message:sysu.card` 槽位入口：用 `CliCardCodec` 解码共享模型，按 `SysuCardKind` 分发到七张卡片，统一出场动画 |
| `src/main/kotlin/.../SysuScheduleCard.kt` | 课表：周历网格（星期列头、节次刻度、课程色块 + 左侧色条、今天高亮） |
| `src/main/kotlin/.../SysuGradesCard.kt` | 成绩：GPA/学分大字 + 课程行（学分 + 按分数段着色的成绩徽标） |
| `src/main/kotlin/.../SysuExamsCard.kt` | 考试：日期块 + 考试名 + 时间/地点 + 倒计时胶囊 |
| `src/main/kotlin/.../SysuLmsCard.kt` | 在修课程：进度环 + 课名 + 结课日期 + 课程 ID |
| `src/main/kotlin/.../SysuTodoCard.kt` | 待办：未完成圆点 + 课名/课程 + 右侧截止倒计时与截止时间 |
| `src/main/kotlin/.../SysuGymCard.kt` | 场馆：类型分节（费用胶囊）+ 场地名/日期 + 时段胶囊（余量、长时段、已满） |
| `src/main/kotlin/.../SysuNetpayCard.kt` | 网费：到期日大字 + 剩余天数胶囊 + 服务行（状态点/状态/地址/到期） |
| `src/test/**` | 命令匹配、payload 解码、观察文本、卡片模型编解码、错误映射/脱敏、共享桥集成（fake host）测试；`fixtures/` 为真机只读输出的脱敏结构样本 |

## 要点

- 凭证存 `host.secrets()`（netid/password），页面只回显 netid，密码默认隐藏、可切换显示。
- 页面结构：顶部标题 + 一句用途 → 状态摘要卡（`DdStatusRow` × 账号/登录状态/运行环境/服务版本，行间 `DdHairline`）→ 环境与错误 `DdNoticeBar`（可重试、可就地重新检查）→ 登录/验证码/已登录阶段各只有一个主操作 `DdPrimaryButton`，次级动作用 `DdTextAction`（返回、重发、刷新、退出）→ 底部一句说明；不展示 CLI 参数、JSON 或表单内部字段名。
- 登录与验证码是一条流程：表单 → 验证码（返回、60 秒重发倒计时）→ 已登录，切换用 `Crossfade` 并读 `LocalDdMotionEnabled`；校验就地显示，键盘用 `imePadding` 避让，底部留 24dp；加载中用 `DdLoadingBlock`。
- 安装：检查 Node 版本 → 从 `host.assetsDir/sysu/**` 复制到 proot（`/root/projects/sysu` 与 `/usr/local/bin/sysu`）。
- 配置在 `/root/.config/sysu/`：`credentials`、`jwxt/session.json`、`jwxt/mfa.json`、`lms.json`、`gym.json`、`netpay.json`（`jwxt/` 是 CLI 的教务站点目录，与插件名无关）。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。设备 ABI 用 `host.device().info.abis` 判断；安装完成与登录成功发稳定 id 通知，退出登录前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
- 查询桥不新增 PluginTool：`bash` 里运行 `sysu jwxt|lms|gym|netpay ... --json` 时，共享桥关联 callId、提取裸对象/数组 payload，插件提供 observation 与 `CliCardModel`；共享层把卡片放进结果 details 的 `deepdeep.card`（customType + payload + title），在文本末尾附卡片 id，由模型决定是否调用 `present` 展示；业务失败（如网费登录过期）用 `CliObservation.isError` 按错误结果替换并附 error 卡（不附 id）。写操作与确认规则不变。
- 错误与脱敏：TGC/验证码/会话失效由 `errorMapper` 转为「插件页重新登录/输入验证码」，不出现 TGC；共享桥返回 KEEP 的非 JSON 输出再经 hook 包装脱敏（票据/会话行）并把 netpay 旧文本 `undefined · ·` 转成 `sysu netpay --json` 提示。卡片 id 由共享层在卡片 payload 生成后追加到结果文本末尾，观察文本本身仍不含「卡片」。
- 卡片 wire 走 `cli-common` 的 `kind/state/title/trailing/context/total/sections/items/note`；`MAX_ROWS=8` 为跨 section 数据行上限、分组标题不计行。视觉仍由本插件用 design 组件控制。
- 回答区按 `SysuCardKind` 分七种版式：课表周历、成绩大字、考试日期块、课程进度环、待办勾选清单、场馆时段胶囊、网费到期；外壳与加载/空/失败件在 `SysuCardChrome.kt`，展示模型与解析在 `SysuCardFormat.kt`。卡片内不臆造数据源没有的字段（如网费余额）。
- 形状取自真机只读命令（`sysu jwxt schedule/scores/exams/credits --json`、`sysu lms/todo --json`、`sysu gym slots --json`、`sysu netpay --json`）的结构；测试夹具已去掉姓名、学号与成绩等个人信息。已知无法从数据区分的情形（如场馆某时段是没空位还是没排期）不臆测。
