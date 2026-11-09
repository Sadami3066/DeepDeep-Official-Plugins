# micloud · 小米云服务外部插件

DeepDeep 官方小米云插件，打成外部 `.ddp`：WebView 登录 i.mi.com 拿 cookie 后加密保存，用 `mi_cloud_*` 工具读取账号、短信、联系人、相册与录音，读写笔记与待办；所有开关与登录操作都在插件自绘页面完成。只 `compileOnly` 依赖公开 Maven 仓库 `https://sadami3066.github.io/deepdeep-maven` 的 `io.github.sadami3066.deepdeep:plugin-api`，不引用宿主内部类型（`R.string`、DataStore、`Activity`、`AppGraph` 等一律不用）。

## 文件

| 文件 | 职责 |
|---|---|
| `build.gradle.kts` | application 模块配置（namespace/applicationId/versionName/Compose）；打包签名由根 `gradle/plugin-bundle.gradle.kts` 提供 |
| `src/main/assets/plugin.json` | 清单：id/version/apiLevel=4/capabilities/minHostVersion/entryClass/icon/provides（7 个工具、skills `deepdeep-micloud`、pages `main`；页面内容直接挂在 `PluginPage.content`，不需要槽位） |
| `src/main/assets/icon.png` | 清单 `icon: asset:icon.png` 引用的小米图标 |
| `src/main/kotlin/.../MiCloudPlugin.kt` | 入口：availability/uninstall/onEnable/onDisable/skills/promptSections/contributions/ui；`MiCloudContent` 持有各组件与登录态缓存 |
| `.../MiCloudPage.kt` | 插件页面 Compose：登录状态与账号、登录/重新登录/退出/刷新登录状态、能力开关、内嵌 WebView 登录 |
| `.../MiCloudPreferences.kt` | 能力开关与 `MiCloudCapabilities`，持久化到 `host.dataDir/capabilities.json` |
| `.../MiCloudCookieStore.kt` | cookie 加密存取（`host.secrets()`，按插件隔离） |
| `.../MiCloudSession.kt` | 登录态缓存（SIGNED_OUT/VERIFIED/EXPIRED/UNVERIFIED）与 TTL、cookie 指纹失效 |
| `.../MiCloudClient.kt` | 只读 HTTP 客户端：带 cookie 请求 i.mi.com、401/403 判定失效、JSON 与文件下载 |
| `.../MiCloudWebWriter.kt` | 写操作走隐藏 WebView 的 JS fetch（复刻浏览器环境），表单 POST 与失败判定；登录 cookie 清理 |
| `.../MiCloudParsers.kt` | profile、cookie 与鉴权失败判定 |
| `.../MiCloudToolSupport.kt` | 工具公共设施：参数校验、结果截断、错误包装、JSON 压缩、路径拼接 |
| `.../MiCloudTools.kt` | 按能力开关组装 `mi_cloud_*` 工具列表（含 account） |
| `.../MiCloudNotesTools.kt` | `mi_cloud_notes`：列表、正文、新建、修改、删除 |
| `.../MiCloudTodoTools.kt` | `mi_cloud_todo`：列表、新建、修改（含完成态）、删除 |
| `.../MiCloudMessagesTools.kt` | `mi_cloud_sms`：短信会话与通话记录列表（只读） |
| `.../MiCloudContactsTools.kt` | `mi_cloud_contacts`：联系人列表与搜索（只读） |
| `.../MiCloudMediaTools.kt` | `mi_cloud_gallery` 相册/媒体列表与 `mi_cloud_recordings` 录音列表、下载 |

## 要点

- 构建：在仓库根执行 `./scripts/gradlew-locked :micloud:verifyPlugin`，产物 `micloud/build/plugin/micloud.ddp`。
- 登录 = 插件页面内 `AndroidView(WebView)` 加载 `https://i.mi.com`；`CookieManager` 同步出的 cookie 先经 `MiCloudClient.profile()` 校验，再存入 `host.secrets()`；页面轮询自动完成，也可手动点「完成登录」。
- 登录态由 `MiCloudSession` 缓存（验证 60s TTL、失效 10 分钟内不重复打接口）；页面打开与宿主刷新时异步探测，工具收到 401/403 立即标记失效。
- 读走 `MiCloudClient`（HttpURLConnection + cookie），写（笔记/待办）必须走 `MiCloudWebWriter` 的隐藏 WebView JS fetch——小米云写接口校验浏览器环境，直接 HTTP POST 会失败。
- 工具按登录态与能力开关双重门控：只有登录有效（VERIFIED/UNVERIFIED）才注册 `mi_cloud_*` 工具，未登录/登录失效时不注入；登录、退出、登录失效都会调用 `host.requestContributionsRefresh()` 重新物化，启动时异步探测完成后的登录态变化也会触发一次刷新。能力开关再决定具体注册哪些工具；笔记/待办的开关同时打开读写，写操作前 agent 必须先向用户确认，删除/批量改不可逆。
- 录音下载落到 `host.dataDir/recordings`，工具返回绝对路径；相册只列不下载。
- 短信、联系人、录音、相册只按开关和用户要求读取；401/403 统一标记会话过期并提示到插件页面重新登录，不要重试刷接口。
- 所有用户可见文案走 `LocalizedText.Literal`（default/zh/en），不引用宿主资源；页面不注册 Activity，登录 WebView 只存在于插件页面。
