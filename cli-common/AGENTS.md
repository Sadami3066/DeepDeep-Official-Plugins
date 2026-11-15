# cli-common · CLI 插件共享层

DeepDeep 官方 CLI 类插件共用的内部 Android library 模块：CLI bash 工具桥、聊天卡片数据模型、CLI 更新与安装流程。只在本仓库内使用，不发布 Maven、不进入插件清单；插件 application 模块用 `implementation(project(":cli-common"))` 依赖它，类随插件一起进 `.ddp`。运行时依赖（`plugin-api`、协程、序列化）全部 `compileOnly`，不新增任何第三方依赖。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | `com.android.library`（AGP 已在根工程 classpath，不写版本）；compileSdk 36 / minSdk 29 / Java 17；plugin-api、coroutines、serialization 全部 compileOnly，JUnit4 + coroutines-test 仅测试 |
| `src/main/kotlin/.../cli/CliCommandLine.kt` | 单条命令词法层：引号/转义 tokenize、环境变量前缀、二进制名匹配（含绝对路径）、flag 读取、shell 操作符判定、诊断摘要（隐藏 flag 值与环境变量） |
| `.../cli/CliJsonEnvelope.kt` | CLI stdout 的 JSON 信封：去 ANSI、跳过 dotenv 行、平衡括号扫描；`find`/`isSuccess` 成功判定（`successField` 可空，null 时第一个平衡 JSON 即成功）、`element()` 取第一个平衡对象或数组（不要求成功字段）、`_notice` 剥离 |
| `.../cli/CliJson.kt` | `JsonObject` 安全取值扩展：`stringValue`/`intValue`/`longValue`/`boolValue`/`objValue`/`arrayValue`/`objectList` |
| `.../cli/CliText.kt` | 文本清理：ANSI、HTML 标签与实体、单行/多行截断、文档正文清洗 |
| `.../cli/CliTime.kt` | 时间展示：ISO 时间 → `MM-dd HH:mm`/`yyyy-MM-dd HH:mm`，epoch 秒格式化 |
| `.../cli/bridge/CliToolBridge.kt` | 通用 bash 工具桥：`beforeToolCall` 记录 callId→命令（有界 64）、`afterToolExecution` 关联/去重/失败隔离/输出上限 256 KiB/成功信封或通用 payload 提取/观测构造/`postCustomMessage` + `resultReplace`；卡片成功后追加模型提示，卡片失败仍替换文本；`isError` 观测按错误结果替换；`runFinished`/`sessionClosed` 清理；hook 内异常 fail-open、`CancellationException` 重抛 |
| `.../cli/card/CliCard.kt` | 通用卡片模型与编解码：header（kind/title/trailing/context/total）、rows（items 或 sections 分组标签）、summary、footer（note/open_url）、READY/EMPTY/ERROR/LOADING；`MAX_ROWS=8` 是数据行上限、分组标题不计行 |
| `.../cli/update/CliVersions.kt` | `major.minor.patch` 归一化、数值比较、取最新 |
| `.../cli/update/CliChecksums.kt` | checksums 文本解析（sha256/sha512 长度可配）与摘要匹配 |
| `.../cli/update/CliRelease.kt` | `CliRelease`（sha256 或 sha512Base64 二选一）与插件提供的 `CliReleasePolicy`（归档名/下载 URL/校验和 URL/摘要长度/`releaseWithSha512`） |
| `.../cli/update/CliUpdate.kt` | `CliUpdateSource`/`CliUpdateCheck`、24 小时节流、单飞 `CliUpdateCheckController`、HTTP GET 取值 `CliHttp` |
| `.../cli/update/CliInstall.kt` | 安装/更新状态机：下载（多源重试，进度；有 sha512 时下载后本地流式校验）→ 解包验版本 → 安装验版本 → 完成；失败/取消回滚旧二进制并清理暂存目录 |
| `src/test/kotlin/...` | 词法、信封（含 element/lenient）、文本、时间、卡片（含 sections/header 扩展）、版本、校验和、版本策略、节流、检查控制器、安装器（含 sha512）与工具桥（含卡片提示/错误观测）单测（69 例） |

## 用法

### CLI 桥

```kotlin
private val CONFIG: CliBridgeConfig<MyCommand, CliCardModel> = CliBridgeConfig(
    binaryName = "lark-cli",
    customType = "feishu.card",
    logTag = "feishu.bridge",
    parser = CliCommandParser { raw -> parseCommand(raw) },
    observer = CliObservationBuilder { host, command, envelope ->
        buildObservation(host, command, envelope)?.let {
            CliObservation(summary = it.summary, text = it.text, card = it.card)
        }
    },
    cardEncoder = { model -> CliCardCodec.encode(model) },
)

internal class MyToolBridge {
    private val bridge = CliToolBridge(CONFIG)

    fun hooks(host: PluginHost): PluginHooks = bridge.hooks(host)

    fun clear() = bridge.clear()
}
```

- 插件只提供 parser、observation builder、card encoder/customType；关联表、去重、输出上限、JSON 信封、卡片投递与结果替换都在共享层。
- parser 只认单条无管道/重定向的 CLI 命令；`CliCommandLine.hasShellOperators`/`tokenize`/`envPrefixLength`/`isBinary`/`readFlags` 是公共词法件，命令白名单与 flag 策略留在插件。
- `beforeToolCall` 命中后记录命令；`afterToolExecution` 只有在结果成功、输出非空且 ≤ 256 KiB、payload 可提取、观测可构造时才 `resultReplace`，其余一律 `resultKeep`。
- 默认走严格信封（`requireSuccessEnvelope = true` 且 `envelope.successField = "ok"`）：要求 JSON 对象带 `ok: true` 并剥 `_notice`。裸对象/顶层数组等无 `ok` 的 CLI 用 `envelope = CliJsonEnvelope.LENIENT`（`successField = null`）或 `requireSuccessEnvelope = false`，由 `payloadFinder`/`CliJsonEnvelope.element` 取第一个平衡 JSON，业务失败由插件 observer 判定。
- `CliObservationBuilder` 的 `JsonObject` 版本是 SAM 入口；需要处理顶层数组时实现 `JsonElement` 重载（默认重载会把对象转给 `JsonObject` 版本）。
- 默认卡片投递 `postCustomMessage(customType, payload, summary)`；`CliObservation.customType` 非空时按观测覆盖（WeCom 一类按命令分槽位的插件用它）。观测 `card = null` 时只替换文本、不投卡片。
- 卡片真正投递成功后，共享层会在模型观测文本末尾追加一句模型提示（`CliBridgeConfig.cardHint`，默认中英双语「以上结果已同时以卡片展示；不必逐条复述列表…」），让模型不必把列表逐条复述一遍；`card = null`、卡片投递失败或原始结果被保留时都不追加。模型观测仍保留全部事实，提示只在末尾。
- 卡片投递失败时仍 `resultReplace(observation.text)`（不带提示、不暴露原始 JSON），只 warn；`CliObservation.isError = true` 时用 `textResult(text, isError = true)` 替换且不追加提示，卡片仍可投（失败文案由插件决定）。
- 需要改写错误结果（例如未授权错误映射成可操作提示）时用 `errorMapper`：

```kotlin
errorMapper = CliErrorMapper { host, request, command ->
    runCatching { mapError(host, request, command) }.getOrNull()
}
```

- hook 内所有异常都 fail-open：`before` 继续调用，`after` 保留原结果；只有卡片投递失败属于「观测有效、卡片失败」，按上面的语义替换文本。`CancellationException` 不吞。
- 不在日志里打印命令参数值、JSON 或凭证：诊断摘要只保留 domain/shortcut/flag 名。

### 卡片模型

```kotlin
val card = CliCardModel(
    kind = "messages",
    title = "文档名",
    trailing = "版本 12",
    context = "会话：oc_1",
    total = 3,
    items = listOf(CliCardItem(title = "标题", subtitle = "来源 · 类型", meta = "09:40", url = "https://…")),
    summary = "正文摘要",
    note = "还有 1 条未显示",
    openUrl = "https://…",
)
val grouped = CliCardModel(
    kind = "schedule",
    total = 4,
    sections = listOf(
        CliCardSection("周一", listOf(CliCardItem(title = "高数"), CliCardItem(title = "英语"))),
        CliCardSection("周二", listOf(CliCardItem(title = "物理"), CliCardItem(title = "体育"))),
    ),
)
val payload: JsonObject = CliCardCodec.encode(grouped)
val decoded: CliCardModel? = CliCardCodec.decode(payload, supportedKinds)
```

- wire 键固定为 `kind`/`state`/`title`/`trailing`/`context`/`total`/`items`/`sections`/`summary`/`open_url`/`note`，行内为 `title`/`subtitle`/`meta`/`badge`/`url`，分组为 `{label, items}`；`title`/`trailing`/`summary`/`sections` 只有非空才写入，旧消息缺省兼容（无 sections 时按 items 平铺）。
- `MAX_ROWS = 8` 是数据行上限（跨 sections 合计），分组标题不计入；`items` 是平铺兜底（有 sections 时取各段行合并），`flatItems` 读取平铺行，`dataRowCount` 是实际数据行数。
- `CliCardModel.isEmpty` 覆盖显式 EMPTY 与无数据行；`decode` 对未知 kind、非法结构返回 null，由插件渲染兜底。
- 渲染仍由插件用 `:design` 的 Dd 组件完成，共享层只出数据与编码，不参与 Compose。

### CLI 更新与安装

```kotlin
private val POLICY = CliReleasePolicy(
    archiveNameFor = { "cli-$it-linux-arm64.tar.gz" },
    downloadUrlsFor = { listOf("https://mirror/$it/archive", "https://github/$it/archive") },
    checksumsUrlsFor = { listOf("https://mirror/$it/checksums.txt") },
)

internal object MyUpdateSource : CliUpdateSource {
    override suspend fun discover(network: NetworkService?, installedVersion: String?): CliUpdateCheck {
        val service = network ?: return CliUpdateCheck(null, null, failed = true)
        val release = … // 请求版本索引 + checksums，返回 POLICY.release(version, checksums)
        return CliUpdateCheck(latestVersion = release?.version, release = release, failed = false)
    }
}

private val checker = CliUpdateCheckController(MyUpdateSource, CliUpdateThrottle.DEFAULT_INTERVAL_MS)

checker.request(
    scope = host.scope,
    network = runCatching { host.network() }.getOrNull(),
    installedVersion = installed,
    lastCheckedAt = lastCheckedAt,
    force = force,
    onStart = { /* 页面状态：正在检查 */ },
    onResult = { result -> /* 持久化 latestVersion/sha256/时间与失败态 */ },
)
```

```kotlin
CliInstallRunner(
    CliInstallPlan(
        binaryExistsCommand = "if [ -x /usr/local/bin/cli ]; then echo yes; else echo no; fi",
        extractCommand = { archive -> "set -e; rm -rf extract; mkdir -p extract; tar -xzf $archive -C extract; ./extract/cli --version" },
        installCommand = "set -e; cp -f extract/cli /usr/local/bin/cli.new; mv -f /usr/local/bin/cli.new /usr/local/bin/cli; /usr/local/bin/cli --version",
        restoreCommand = { hadBinary -> if (hadBinary) "mv -f /usr/local/bin/cli.old /usr/local/bin/cli" else "rm -f /usr/local/bin/cli" },
        parseVersion = CliCli::parseVersion,
        timeouts = CliInstallTimeouts(),
    ),
).run(linux, network, stagingDir, release, messages) { event -> /* stage + progress 映射到页面状态 */ }
```

- 检查结果只包含 `latestVersion`/`release`/`failed`；缓存合并、状态持久化与页面文案留在插件。失败由 `failed = true` 表达，异常在控制器内收敛。
- 安装器只做流程：多 URL 重试下载、成功后由宿主 `network().download` 校验 checksum、解包后校验版本、安装后再次校验版本；任何失败或取消都回滚旧二进制并清理暂存目录，成功才向外发 FINISHING。阶段为 `DOWNLOADING(progress)`/`VERIFYING`/`INSTALLING`/`FINISHING`，插件的 stage 文案与安装还是更新由自己映射。
- 错误文案由插件通过 `CliInstallMessages` 提供（下载失败/版本不匹配/安装失败各一条），共享层不拼用户可见文案。
- 校验二选一：`CliRelease.sha256` 走宿主 `network().download(expectedSha256=…)`；`CliRelease.sha512Base64` 非空时下载不带 sha256，下载后在本地流式算 SHA-512 与 base64 比对，不匹配删包并按 `downloadFailed` 抛出。npm 等只给 sha512 integrity 的源用 `CliReleasePolicy.releaseWithSha512`。
- 通用纯逻辑可直接复用：`CliVersions.normalize/compare/isNewer/latest`、`CliChecksums.parse/digestOf/isDigest`、`CliUpdateThrottle.shouldCheck`、`CliHttp.getText/firstText`。

## 迁移方式（WeCom / SYSU）

1. 插件 `build.gradle.kts` 加 `implementation(project(":cli-common"))`，删掉插件内重复的 pending/handled 关联表、JSON 信封、`postCustomMessage` 投递与 `resultReplace` 逻辑。
2. 保留插件自己的命令解析器，用 `CliCommandLine` 替换私有 tokenizer/flag 读取（各插件额外的拒绝规则，如 WeCom 的 `&`、`--`、分页 flag，继续留在 parser）。
3. 保留插件自己的输出解析与 observation 文案；把最终模型文本包成 `CliObservation(summary, text, card, isError = …)`，`card` 可以是插件自己的 payload 数据类（`CARD` 泛型），`cardEncoder` 负责编码成 `JsonObject`。裸对象/顶层数组命令配置 `envelope = CliJsonEnvelope.LENIENT`（或 `requireSuccessEnvelope = false`），需要处理数组时实现 `CliObservationBuilder` 的 `JsonElement` 重载，业务失败用 `isError = true` 交给桥按错误结果替换。
4. `customType` 固定在配置里；按命令分槽位的插件（WeCom 的 7 个 `wecom.*` 槽位）在 `CliObservation.customType` 上按 command kind 覆盖。卡片用 `sections` 表达分组（课表按天、考试按日期、场馆按类型），`title`/`trailing`/`summary` 表达文档卡头部与摘要。
5. 未授权/失败文案改写用 `errorMapper`；SYSU 这类还要对非命中的原始输出脱敏，用 hook 包装：

```kotlin
val base = bridge.hooks(host)
object : PluginHooks by base {
    override suspend fun afterToolExecution(request: ToolExecutionResult): ToolResultDecision {
        val decision = base.afterToolExecution(request)
        if (decision.action != ToolResultAction.KEEP) return decision
        return redactSensitive(request.result.text)?.let { resultReplace(textResult(it)) } ?: decision
    }
}
```

6. 插件自己的行为测试保留；通用算法测试（版本比较、checksums、节流、安装器、信封提取、卡片编解码）放本模块，不重复写在插件里。
7. 迁移完成后插件单测与 `verifyPlugin` 必须仍然通过，`.ddp` 内应包含 `io.github.whywood.deepdeep.plugin.cli.*` 的类。

## 构建与测试

```bash
cd DeepDeep-Official-Plugins
./scripts/gradlew-locked :cli-common:testDebugUnitTest
./scripts/gradlew-locked :cli-common:testDebugUnitTest :feishu:testDebugUnitTest :feishu:verifyPlugin
```

## 要点

- 模块是内部共享层：不放插件清单、不做 UI、不引入 Compose，渲染由各插件用 `:design` 组件完成。
- 不新增第三方依赖；`plugin-api`/协程/序列化必须保持 `compileOnly`，否则 `.ddp` 会把宿主库重复打包。
- 卡片 wire 格式是已发布插件的历史消息格式，改字段要同时考虑旧会话解码，只能加字段不能改名。
- 模型观测末尾的卡片提示只在卡片投递成功后出现，是面向模型的一句自然语言，不是给用户的开发提示；不要写进卡片 payload。
- CLI 只识别单条普通命令，接管道/重定向/多命令一律不接管；不打印参数值、JSON 正文或凭据。
- 构建：`./scripts/gradlew-locked :cli-common:testDebugUnitTest`；依赖方（飞书）用 `:feishu:testDebugUnitTest :feishu:verifyPlugin` 验证类已进 `.ddp`。
