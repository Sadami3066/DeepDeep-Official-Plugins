# sysu · 中山大学 CLI 插件

模块名、插件 id 与安装路径均为 `sysu`（`io.github.whywood.deepdeep.plugin.sysu`），对外名称「中山大学 CLI」。
分发 Node.js 版 `sysu` CLI：教务、在线教学、体育场馆、网费与统一认证换票。CLI 桥、卡片模型与命令关联复用仓库内 `:cli-common` 共享模块；打包/签名由根 `gradle/plugin-bundle.gradle.kts` 提供。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | `implementation(project(":cli-common"))`（类随插件进 `.ddp`）；版本 2.7.2 / versionCode 22；design 0.11.0；内置 CLI 3.5.1 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=6/`ui.apiLevel`=2/minHostVersion=1.4.0/capabilities（含 `chat`、`logs`）/entryClass/provides（skills `deepdeep-sysu`、uiSlots `chat.message:sysu.card`、page `main`、publisher `milous`） |
| `src/main/assets/sysu/**` | Node.js 校园 CLI（bin 与 src），安装时从 `host.assetsDir` 拷入 proot；`src/netpay.js` 遇到登录页、`success:false` 或登录失效文案时自动 `login()` 重试一次 |
| `src/main/kotlin/.../SysuCli.kt` | 安装路径、Node 检查、四站状态命令（`session.json`/`lms.json`/`gym.json`/`netpay.json`）、四站 login 命令与验证码判定 |
| `src/main/kotlin/.../SysuStatus.kt` | `host.settings()`：安装状态、登录账号、教务/在线教学/场馆/网费四站连接状态（旧 `status.json` 首次读入后迁移删除） |
| `src/main/kotlin/.../SysuPlugin.kt` | `DeepDeepPlugin` 入口：availability / skills / promptSections / contributions（`SysuToolBridge` hooks）/ ui（聊天卡片绑定）；`loginSite` 执行单站 login 并返回成功/需要验证码/失败；`onUpgrade` 调用 `ensureInstalled` 重装内置 CLI，`onEnable` 只读状态 |
| `src/main/kotlin/.../SysuPage.kt` | 插件自绘页：标题与用途、状态摘要卡（账号 + 四站状态 + 运行环境 + 服务版本）、登录/验证码一条流程、单站验证码提示、连接未登录服务、就地错误重试与退出登录 |
| `src/main/kotlin/.../SysuContent.kt` | zh/en 文案与技能 markdown（`deepdeep-sysu`）；技能包含四站 login、`present` 交付与 gym slots 单类型/最多 2 天说明 |
| `src/main/kotlin/.../SysuCommand.kt` | bash 命令解析（词法用 `CliCommandLine`）：只认单条 `sysu` 命令（拒绝管道/重定向/`&&`/`cd` 前缀），识别支持的八类 JSON 查询与命令所属站点（旧命令带 `--json` 仍可识别） |
| `src/main/kotlin/.../SysuBridge.kt` | 共享 `CliToolBridge` 配置（`CliJsonEnvelope.LENIENT` 裸对象/数组 payload、`JsonElement` observer、`redactRaw`）；`errorMapper` 保留错误原文并追加按站点的 login 建议；KEEP 结果补 `deepdeep.raw`、脱敏与 netpay 旧文本提示 |
| `src/main/kotlin/.../SysuJson.kt` | 插件输出 DTO（课表/成绩/考试/学分/LMS/场馆/网费）：从共享层提取的 `JsonElement` 解码，含考试周次/类型、场馆费用 Identity/Timeslot、网费全部服务字段与到期原文 |
| `src/main/kotlin/.../SysuObservation.kt` | 共享 `CliObservation(summary=卡片标题, text=guidance)` + `CliCardModel`：supported kinds 的 text 只写下一步/空态/登录提示，数据全部进卡片；院系前缀去重、学分双方案标注 |
| `src/main/kotlin/.../SysuGymDisplay.kt` | 场馆时段整理：按日期分组，区分切片时段与覆盖多段的长时段；不再限制每场时段数量 |
| `src/main/kotlin/.../SysuCardFormat.kt` | 回答区卡片的展示模型与解析：七天周历（按日期对齐、今天高亮）、成绩分组、考试教师/周次/类型、LMS 详情、场馆时段矩阵、网费全部字段 |
| `src/main/kotlin/.../SysuCardChrome.kt` | 卡片外壳与共用件：`SysuCardSurface`/`SysuTopLine`/`SysuPill`/`SysuWrapRow`/`SysuExpandToggle`/详情行，以及加载、空、失败与全量列表回退卡 |
| `src/main/kotlin/.../SysuChatCard.kt` | `chat.message:sysu.card` 槽位入口：用 `CliCardCodec` 解码共享模型，按 `SysuCardKind` 分发到七张卡片，统一出场动画 |
| `src/main/kotlin/.../SysuScheduleCard.kt` | 课表：七天横向滑动周历（星期列头、节次刻度、课程色块、今天高亮） |
| `src/main/kotlin/.../SysuGradesCard.kt` | 成绩：GPA/学分大字 + 全部课程行（预览 4 行，`DdExpandToggle` 展开；多学期分组） |
| `src/main/kotlin/.../SysuExamsCard.kt` | 考试：日期块 + 考试名 + 时间/地点/教师/周次/类型 + 倒计时胶囊；全部考试可展开 |
| `src/main/kotlin/.../SysuLmsCard.kt` | 在修课程：进度环 + 课名 + 结课/课程代码/开课 + 课程 ID；全部课程可展开 |
| `src/main/kotlin/.../SysuTodoCard.kt` | 待办：未完成圆点 + 课名/课程/动作 + 右侧截止倒计时；全部待办可展开 |
| `src/main/kotlin/.../SysuGymCard.kt` | 场馆：类型分节（价格）+ 场地×时段矩阵，整行共享横向滚动；全部场地/时段/价格可达，类型超过 2 类可展开 |
| `src/main/kotlin/.../SysuNetpayCard.kt` | 网费：到期日大字 + 剩余天数胶囊 + 全部服务行（状态/地址/到期/编号/MAC/部门/用户/服务 ID/暂停信息） |
| `src/test/**` | 命令匹配、四站状态解析、payload 解码、观察文本、卡片模型编解码、错误映射/脱敏、raw 脱敏、共享桥集成（fake host）测试；`fixtures/` 为真机只读输出的脱敏结构样本 |

## 要点

- 课表只接受显式 `sysu jwxt schedule`（含 `--week/--today/--tomorrow`）；内置 CLI 3.5.1 同步移除了裸 `sysu jwxt` 的默认课表行为，裸命令直接报错提示改用 `schedule`，插件桥也不再把它当课表接管。内置 `sysu/SKILL.md`、`sysu/README.md` 与 `--help` 文案都已改成只列 `jwxt schedule`。

- 凭证存 `host.secrets()`（netid/password），页面只回显 netid，密码默认隐藏、可切换显示。
- 页面结构：顶部标题 + 一句用途 → 状态摘要卡（账号、教务/在线教学/场馆/网费四行、运行环境、服务版本，行间 `DdHairline`）→ 环境与错误 `DdNoticeBar`（可重试、可就地重新检查）→ 登录/验证码/已登录阶段各只有一个主操作 `DdPrimaryButton`，次级动作用 `DdTextAction`（返回、重发、刷新、连接未登录服务、退出）→ 底部一句说明；不展示 CLI 参数、JSON 或表单内部字段名。
- 登录与验证码是一条流程：表单 → 验证码（返回、60 秒重发倒计时）→ 已登录，切换用 `Crossfade` 并读 `LocalDdMotionEnabled`；验证码步骤会标明是「教务」还是某个站点（在线教学/体育场馆/网费）。校验就地显示，键盘用 `imePadding` 避让，底部留 24dp；加载中用 `DdLoadingBlock`。
- 登录顺序：`sysu login --send-code` 成功后依次执行 `sysu lms login`、`sysu gym login`、`sysu netpay login`，每站复用同一份 TGC/凭据，页面逐项更新四站状态；单站需要验证码时停在该站提示输入，提交 `sysu <site> login --code` 后继续后面的站点。任一站失败不中断其余站点，已登录页可点「连接其余 N 个服务」重试。
- 安装：检查 Node 版本 → 从 `host.assetsDir/sysu/**` 复制到 proot（`/root/projects/sysu` 与 `/usr/local/bin/sysu`）；插件升级走 `onUpgrade` 重装内置 CLI，`onEnable` 不重装。
- 配置在 `/root/.config/sysu/`：`credentials`、`jwxt/session.json`、`jwxt/mfa.json`、`lms.json`、`gym.json`、`netpay.json`（`jwxt/` 是 CLI 的教务站点目录，与插件名无关）。状态解析只读这些文件，不发网络请求。
- 页面动作结束调用 `host.requestContributionsRefresh()`；页面用 `:design` 的 Dd 组件，内容直接挂在 `PluginPage.content`。设备 ABI 用 `host.device().info.abis` 判断；安装完成与登录成功发稳定 id 通知，退出登录前用 `host.ui().confirm` 确认，结果用 `host.ui().snackbar` 提示。
- 查询桥不新增 PluginTool：`bash` 里运行 `sysu jwxt schedule|scores|...`、`sysu lms`、`sysu gym ...`、`sysu netpay` 时，共享桥关联 callId、提取裸对象/数组 payload，插件提供 observation 与 `CliCardModel`；共享层把卡片放进结果 details 的 `deepdeep.card`（customType + payload + title），把完整上游输出写进 `deepdeep.raw`（`redactRaw` 先脱敏），宿主在所有 hook 之后统一追加 raw 与卡片 id，由模型决定是否调用 `present` 展示。插件自己不再拼「卡片 id」。
- raw 与脱敏：`redactRaw` 调用 `SysuFailures.redactSensitive`，含 TGC/Cookie/JSESSIONID/session.json/mfa.json/ticket/password 的文本行被去掉并留「已隐去」说明，JSON 输出则保留结构、只替换敏感值；其余 CLI 输出（包括不支持的 sysu 子命令和登录等写操作）在 KEEP 结果上补 `deepdeep.raw`。
- 错误：`SysuFailures.actionable` 不再整段替换，返回「原错误（脱敏后）+ 建议动作」。单站会话失效时建议先执行对应站点的 `login`（教务 `sysu login`、LMS `sysu lms login`、场馆 `sysu gym login`、网费 `sysu netpay login`），需要企业微信验证码或 login 失败才引导插件页；原始错误保留，票据值以「已隐去」代替。
- supported kinds 的模型文本只写 guidance（下一步、空态说明、登录提示），不重复数据行；数据全量进卡片的 payload 与 `deepdeep.raw`，不截断。上游没有分页，不造分页。
- 卡片 wire 仍走 `cli-common` 的 `kind/state/title/trailing/context/total/sections/items/note`（行支持 `body`），编码不裁剪行数；旧会话 payload 继续可解码。卡片内默认预览 3–5 行/组，`DdExpandToggle` 展开全部；课表固定七天并横向滑动；场馆用「场地行 × 时段列」矩阵，左列场地固定、右侧共享横向滚动；网费行下方展示全部服务字段。
- 回答区按 `SysuCardKind` 分七种版式：七天周历、成绩、考试、课程、待办、场馆矩阵、网费；外壳与加载/空/失败件在 `SysuCardChrome.kt`，展示模型与解析在 `SysuCardFormat.kt`。卡片内不臆造数据源没有的字段（如网费余额）。
- 形状取自真机只读命令（`sysu jwxt schedule/scores/exams/credits`、`sysu lms/todo`、`sysu gym slots`、`sysu netpay`）的结构；测试夹具已去掉姓名、学号与成绩等个人信息。已知无法从数据区分的情形（如场馆某时段是没空位还是没排期）不臆测。
