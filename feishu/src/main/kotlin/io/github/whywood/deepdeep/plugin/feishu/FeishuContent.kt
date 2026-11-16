package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun literal(zh: String, en: String, default: String = zh): LocalizedText =
    localizedText(mapOf("default" to default, "zh" to zh, "en" to en))

internal fun PluginHost.t(zh: String, en: String): String = text(literal(zh, en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String = text(literal(zh, en), *args)

internal fun PluginHost.isChinese(): Boolean = t("中文", "English") == "中文"

internal const val FEISHU_VERSION = "1.0.100"
internal const val FEISHU_SKILL_ID = "deepdeep-feishu"
internal const val FEISHU_PAGE_ID = "main"
internal const val FEISHU_ICON_ASSET = "icon.png"
internal const val FEISHU_SECTION_ID = "feishu_prompt"

internal const val ARCHIVE_NAME = "lark-cli-$FEISHU_VERSION-linux-arm64.tar.gz"
internal const val ARCHIVE_SHA256 = "166e95ffaec34f82123f50316480250329884c09e71a6f92313047e2c5c3fa5d"

internal val DOWNLOAD_URLS = listOf(
    "https://registry.npmmirror.com/-/binary/lark-cli/v$FEISHU_VERSION/lark-cli-$FEISHU_VERSION-linux-arm64.tar.gz",
    "https://github.com/larksuite/cli/releases/download/v$FEISHU_VERSION/lark-cli-$FEISHU_VERSION-linux-arm64.tar.gz",
)

internal const val APP_CONSOLE_URL = "https://open.feishu.cn/app"
internal const val STAGING_DIR = "install-staging"
internal const val STATE_FILE = "state.json"

internal const val EXTRACT_TIMEOUT_MS = 120_000L
internal const val INSTALL_TIMEOUT_MS = 120_000L
internal const val UNINSTALL_TIMEOUT_MS = 60_000L
internal const val VERSION_TIMEOUT_MS = 20_000L
internal const val STATUS_TIMEOUT_MS = 30_000L
internal const val LOGIN_START_TIMEOUT_MS = 60_000L
internal const val LOGIN_POLL_TIMEOUT_MS = 11 * 60_000L
internal const val LOGOUT_TIMEOUT_MS = 60_000L
internal const val CLEANUP_TIMEOUT_MS = 30_000L
internal const val UPDATE_CHECK_TIMEOUT_MS = 20_000L

internal val UNAVAILABLE_LINUX = literal(
    "飞书连接需要先安装并启用 Linux 运行环境",
    "Feishu needs the Linux environment installed and enabled",
)

internal val STAGE_DOWNLOADING = literal("正在下载飞书组件…", "Downloading the Feishu component…")
internal val STAGE_VERIFYING = literal("正在校验安装包…", "Verifying the package…")
internal val STAGE_INSTALLING = literal("正在安装飞书组件…", "Installing the Feishu component…")
internal val STAGE_UPDATING = literal("正在更新飞书组件…", "Updating the Feishu component…")
internal val STAGE_INSTALL_FINISHING = literal("正在完成安装…", "Finishing installation…")
internal val STAGE_UPDATE_FINISHING = literal("正在完成更新…", "Finishing update…")
internal val STAGE_CREATE_APP = literal(
    "在浏览器中创建并授权飞书应用",
    "Create and authorize the Feishu app in the browser",
)
internal val STAGE_AUTH = literal("在浏览器中授权飞书账号", "Authorize your Feishu account in the browser")
internal val STAGE_LOGIN_FINISHING = literal("正在完成登录…", "Finishing sign-in…")
internal val STAGE_SIGNING_OUT = literal("正在退出登录…", "Signing out…")
internal val STAGE_CANCELLING = literal("正在取消…", "Cancelling…")

internal val ERROR_LINUX = literal(
    "Linux 运行环境不可用：请先安装并启用 Linux 运行环境。",
    "The Linux environment is unavailable: install and enable it first.",
)
internal val ERROR_DOWNLOAD = literal("下载安装包失败：%s", "Install package download failed: %s")
internal val ERROR_VERSION = literal("安装包版本不匹配：%s", "Package version mismatch: %s")
internal val ERROR_INSTALL = literal("安装失败：%s", "Install failed: %s")
internal val ERROR_AUTH = literal("没有拿到飞书授权链接", "No Feishu authorization link was returned")
internal val ERROR_AUTH_OUTPUT = literal("飞书授权失败：%s", "Feishu authorization failed: %s")
internal val ERROR_LOGOUT = literal("退出登录失败：%s", "Sign-out failed: %s")
internal val ERROR_STATUS = literal("状态检测失败：%s", "Status check failed: %s")
internal val ERROR_UNKNOWN = literal("操作失败，请重试。", "The operation failed; try again.")
internal val ERROR_UPDATE_CHECK = literal("暂时无法检查更新", "Update check unavailable")

internal val UPDATE_STATUS_AVAILABLE = literal("可更新", "Update available")
internal val UPDATE_STATUS_CHECKING = literal("正在检查更新…", "Checking for updates…")
internal val UPDATE_STATUS_FAILED = literal("暂时无法检查更新", "Update check unavailable")
internal val UPDATE_NOTICE = literal("飞书组件有新版本，建议更新。", "A newer Feishu component is available.")
internal val UPDATE_ACTION = literal("更新", "Update")
internal val UPDATE_CHECK_AGAIN = literal("检查更新", "Check for updates")
internal val UPDATE_FAILED_NOTICE = literal(
    "暂时无法检查更新，可以稍后重试。",
    "Couldn't check for updates. Try again later.",
)

internal const val NOTIFY_CLI_ID = "feishu-cli"
internal const val ACTION_OPEN_PAGE = "open-page"
internal val ACTION_OPEN_PAGE_LABEL = literal("打开插件页", "Open plugin page")
internal val NOTIFY_CLI_TITLE = literal("飞书 CLI 已就绪", "Feishu CLI is ready")
internal val NOTIFY_CLI_BODY = literal(
    "CLI 安装完成。",
    "The CLI is installed. Open the plugin page to create the Feishu app and authorize your account.",
)

internal val SNACKBAR_INSTALL_DONE = literal("飞书组件安装完成", "Feishu component installed")
internal val SNACKBAR_UPDATE_DONE = literal("飞书组件已更新", "Feishu component updated")
internal val SNACKBAR_CONNECTED = literal("飞书已连接", "Feishu connected")
internal val SNACKBAR_LOGGED_OUT = literal("已退出飞书登录", "Signed out of Feishu")
internal val SNACKBAR_CANCELLED = literal("已取消", "Cancelled")
internal val SNACKBAR_LINK_COPIED = literal("授权链接已复制", "Authorization link copied")
internal val ERROR_COPY = literal("复制失败，请重试。", "Couldn't copy the link. Try again.")

internal val SKILL_MARKDOWN = """
    ---
    name: deepdeep-feishu
    description: 飞书 CLI 用法速查：读取消息与群聊、查看日历、搜索云文档、云空间文件、邮箱与任务；当用户提到飞书、lark、消息、日程或云文档时使用。
    ---

    # 飞书 CLI

    DeepDeep 飞书插件把官方 `lark-cli` 安装在 `/usr/local/bin/lark-cli`，在 bash 中直接运行；命令默认输出 JSON，成功与否以退出码或 `ok` 字段为准。

    ## 读取命令

    消息、日程、云文档与任务这几类读取会返回完整可读内容，并带一张可交付卡片；需要用户看到时用 `present` 交付。为了让插件读到完整结果，每条命令必须是**一条普通 CLI 调用**：

    - 不要接管道或重定向：不要用 `| head`、`| jq`、`> 文件`、`2>&1`。
    - 不要加 `--jq`、`--concise`，也不要改成 table/csv 等输出格式。
    - 一次只跑一条 `lark-cli` 读取命令。

    常用读取：

    - 聊天：`lark-cli im +chat-list --page-size 50`
    - 消息：`lark-cli im +chat-messages-list --chat-id <oc_xxx> --page-size 50`
    - 日程：`lark-cli calendar +agenda --start <日期> --end <日期>`
    - 云文档搜索：`lark-cli drive +search --query "<关键词>"`（关键词不超过 30 个字符）
    - 文档读取：`lark-cli docs +fetch --doc <文档链接或 token> --doc-format markdown`
    - 我的任务：`lark-cli task +get-my-tasks --complete=false`

    分页：读取命令默认只返回一页。结果末尾给出 `--page-token <token>` 时，用户要更多就带上该参数继续，不要一次拉全量。

    空结果是正常结果：当天没有日程、没有待办任务、没有搜到文档时直接告诉用户，不要重试或换命令。

    ## 账号与权限

    - 登录后默认使用用户身份；`auth` 类命令不支持 `--as`，其他命令只在需要应用身份时才加 `--as bot`。
    - 查看登录态：`lark-cli auth status --json`。
    - 不熟悉业务域先看用法：`lark-cli <domain> --help`；也可以读指南：`lark-cli skills list`、`lark-cli skills read lark-im`。
    - 命令返回 `missing_scopes` 或登录时提示缺少权限，说明飞书应用本身未开通该权限；不要反复重新授权。
    - 电子表格/多维表格没有直读权限时改用导出：`mkdir -p <download-directory> && lark-cli drive +export --url <链接> --file-extension xlsx --output-dir <download-directory>`（多维表格把 `xlsx` 换成 `base`）。

    ## 写入与输出

    - 已授权范围内直接执行；发送、修改、删除、授权等操作先询问用户；支持时可用 `--dry-run` 预览。
    - 高风险写入会要求确认参数或返回退出码 10，先向用户说明再继续。
    - 回答时用自然语言概括结果，不要粘贴原始 JSON、XML/HTML 标签或大段命令输出。
    - 表格、HTML 等长内容先提炼结论；用户要原文时再说明在飞书里查看。

    其他入口：

    - 云空间：`lark-cli drive ...`；导出：`lark-cli drive +export`
    - 电子表格：有权限时用 `lark-cli sheets ...`，无直读权限时用 `lark-cli drive +export --file-extension xlsx`
    - 多维表格：有权限时用 `lark-cli base ...`，无直读权限时用 `lark-cli drive +export --file-extension base`
    - 邮箱：`lark-cli mail ...`

""".trimIndent()
