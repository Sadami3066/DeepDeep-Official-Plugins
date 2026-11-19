# cli-common · CLI 插件共享层

DeepDeep 官方 CLI 类插件共用的内部 Android library：CLI 更新/安装流程与少量文本、JSON 工具。只在本仓库内使用，不发布 Maven、不进入插件清单；插件 application 模块用 `implementation(project(":cli-common"))` 依赖它，类随插件一起进 `.ddp`。运行时依赖（`plugin-api`、协程、序列化）全部 `compileOnly`，不新增第三方依赖。

CLI 插件不做卡片、也不解析 bash 命令：模型在 bash 里运行 CLI，直接读 stdout。本模块不参与任何工具结果改写。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | `com.android.library`（AGP 已在根工程 classpath，不写版本）；compileSdk 36 / minSdk 29 / Java 17；plugin-api、coroutines、serialization 全部 compileOnly，JUnit4 + coroutines-test 仅测试 |
| `src/main/kotlin/.../cli/CliJsonEnvelope.kt` | CLI stdout 的 JSON 信封：去 ANSI、跳过 dotenv 行、平衡括号扫描；`find`/`isSuccess` 成功判定、`element()` 取第一个平衡对象或数组、`_notice` 剥离 |
| `.../cli/CliJson.kt` | `JsonObject` 安全取值扩展：`stringValue`/`intValue`/`longValue`/`boolValue`/`objValue`/`arrayValue`/`objectList` |
| `.../cli/CliText.kt` | 文本清理：ANSI、HTML 标签与实体、单行/多行截断、文档正文清洗 |
| `.../cli/update/CliVersions.kt` | `major.minor.patch` 归一化、数值比较、取最新 |
| `.../cli/update/CliChecksums.kt` | checksums 文本解析（sha256/sha512 长度可配）与摘要匹配 |
| `.../cli/update/CliRelease.kt` | `CliRelease`（sha256 或 sha512Base64 二选一）与插件提供的 `CliReleasePolicy`（归档名/下载 URL/校验和 URL/摘要长度/`releaseWithSha512`） |
| `.../cli/update/CliUpdate.kt` | `CliUpdateSource`/`CliUpdateCheck`、24 小时节流、单飞 `CliUpdateCheckController`、HTTP GET 取值 `CliHttp` |
| `.../cli/update/CliInstall.kt` | 安装/更新状态机：下载（多源重试，进度；有 sha512 时下载后本地流式校验）→ 解包验版本 → 安装验版本 → 完成；失败/取消回滚旧二进制并清理暂存目录 |
| `src/test/kotlin/...` | 信封、文本、版本、校验和、版本策略、节流、检查控制器与安装器单测 |

## CLI 更新与安装

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

## 构建与测试

```bash
cd DeepDeep-Official-Plugins
./scripts/gradlew-locked :cli-common:testDebugUnitTest
./scripts/gradlew-locked :cli-common:testDebugUnitTest :feishu:testDebugUnitTest :feishu:verifyPlugin
```

## 要点

- 模块是内部共享层：不放插件清单、不做 UI、不引入 Compose。
- 不新增第三方依赖；`plugin-api`/协程/序列化必须保持 `compileOnly`，否则 `.ddp` 会把宿主库重复打包。
- 不参与 bash 结果改写：CLI 插件没有卡片、命令桥或 hooks，结果原样交给模型。
- 插件自己的安装/登录/更新页逻辑与文案留在插件；共享层只出算法与流程。
