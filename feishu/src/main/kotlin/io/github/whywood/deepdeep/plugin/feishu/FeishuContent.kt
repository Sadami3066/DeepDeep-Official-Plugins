package io.github.whywood.deepdeep.plugin.feishu

import io.github.whywood.deepdeep.plugin.api.LocalizedText

internal fun literal(zh: String, en: String, default: String = zh): LocalizedText =
    LocalizedText.Literal(mapOf("default" to default, "zh" to zh, "en" to en))

internal const val FEISHU_VERSION = "1.0.98"
internal const val FEISHU_SKILL_ID = "deepdeep-feishu"
internal const val FEISHU_PAGE_ID = "main"
internal const val FEISHU_ICON_ASSET = "icon.png"
internal const val FEISHU_SECTION_ID = "feishu_prompt"

internal const val ARCHIVE_NAME = "lark-cli-1.0.98-linux-arm64.tar.gz"
internal const val ARCHIVE_SHA256 = "f798b26ce4b369d70411945e7f66bbe4382c411bc47d81950a662de7b69b41ed"

internal val DOWNLOAD_URLS = listOf(
    "https://registry.npmmirror.com/-/binary/lark-cli/v1.0.98/lark-cli-1.0.98-linux-arm64.tar.gz",
    "https://github.com/larksuite/cli/releases/download/v1.0.98/lark-cli-1.0.98-linux-arm64.tar.gz",
)

internal const val APP_CONSOLE_URL = "https://open.feishu.cn/app"
internal const val SHOWN_SCOPE_LIMIT = 3
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

internal val UNAVAILABLE_LINUX = literal(
    "需要 arm64 设备并先安装并启用 Linux 运行环境",
    "Requires an arm64 device with the Linux environment installed and enabled",
)

internal val STAGE_DOWNLOADING = literal("正在下载飞书 CLI…", "Downloading Feishu CLI…")
internal val STAGE_VERIFYING = literal("正在校验安装包…", "Verifying the package…")
internal val STAGE_INSTALLING = literal("正在安装飞书 CLI…", "Installing Feishu CLI…")
internal val STAGE_CREATE_APP = literal(
    "在浏览器中创建并授权飞书应用",
    "Create and authorize the Feishu app in the browser",
)
internal val STAGE_AUTH = literal("在浏览器中授权飞书账号", "Authorize your Feishu account in the browser")
internal val STAGE_FINISHING = literal("正在完成登录…", "Finishing sign-in…")
internal val STAGE_SIGNING_OUT = literal("正在退出登录…", "Signing out…")

internal val HINT_CREATE_APP = literal(
    "在浏览器完成创建后回到这里，会自动继续登录。",
    "Come back after finishing in the browser; sign-in continues automatically.",
)
internal val HINT_AUTH = literal(
    "在浏览器完成授权后会自动继续，无需复制链接。",
    "Come back after authorizing; no link copying needed.",
)

internal val ERROR_LINUX = literal(
    "Linux 运行环境不可用：请先安装并启用 Linux 环境。",
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

internal const val LARK_CLI_BACKUP_PATH = "/usr/local/bin/.lark-cli.old"
internal const val LARK_CLI_TEMP_PATH = "/usr/local/bin/.lark-cli.new"

internal fun feishuRestoreCommand(hadBinary: Boolean): String = buildString {
    appendLine("if [ -f $LARK_CLI_BACKUP_PATH ]; then")
    appendLine("mv -f $LARK_CLI_BACKUP_PATH ${FeishuCli.INSTALL_PATH}")
    appendLine("chmod 755 ${FeishuCli.INSTALL_PATH}")
    if (!hadBinary) {
        appendLine("else")
        appendLine("rm -f ${FeishuCli.INSTALL_PATH}")
    }
    appendLine("fi")
    appendLine("rm -f $LARK_CLI_TEMP_PATH")
    append("true")
}

internal val SKILL_MARKDOWN = """
    ---
    name: deepdeep-feishu
    description: 飞书 CLI 用法速查：读取消息与群聊、查看日历、搜索云文档、云空间文件、邮箱与任务；当用户提到飞书、lark、消息、日程或云文档时使用。
    ---

    # 飞书 CLI

    DeepDeep 飞书插件把官方 `lark-cli` 安装在 `/usr/local/bin/lark-cli`，在 bash 中直接运行；命令默认输出 JSON，成功与否以退出码或输出字段为准。若提示 `lark-cli: command not found`，说明 CLI 尚未安装，请让用户打开插件页的飞书页面点「安装/更新 CLI」，不要自行下载或安装。

    - 读取用户个人数据必须使用 `--as user`；`--as bot` 只能访问应用自己的资源。
    - 调用前先看用法：`lark-cli <domain> --help`。
    - 不熟悉的业务域先读指南：`lark-cli skills list`、`lark-cli skills read lark-im`。
    - 命令返回 `missing_scopes` 或登录时提示缺少权限，说明飞书应用本身未开通该权限；不要反复重新授权。
    - 电子表格/多维表格没有直读权限时，改用导出兜底：`mkdir -p <download-directory> && lark-cli drive +export --url <链接> --file-extension xlsx --output-dir <download-directory>`（电子表格），多维表格把 `xlsx` 换成 `base`，导出到 `<download-directory>` 后再解析文件。
    - 需要直读时，请用户在插件页面点「应用权限」，到飞书开放平台为应用开通对应权限，再重新授权。
    - 已授权范围内直接执行；超出授权的发送、修改、删除、授权等操作先询问；支持时可用 `--dry-run` 预览。
    - 高风险写入会要求确认参数或返回退出码 10，先向用户说明再继续。

    常用入口：

    - 消息：`lark-cli im +chat-list`、`lark-cli im +chat-messages-list --chat-id oc_xxx --start <ISO> --end <ISO>`、`lark-cli im +messages-search`
    - 日历：`lark-cli calendar +agenda`
    - 云文档：`lark-cli docs +create`、`lark-cli docs +fetch`
    - 云空间：`lark-cli drive ...`；导出：`lark-cli drive +export`
    - 电子表格：有权限时用 `lark-cli sheets ...`，无直读权限时用 `lark-cli drive +export --file-extension xlsx`
    - 多维表格：有权限时用 `lark-cli base ...`，无直读权限时用 `lark-cli drive +export --file-extension base`
    - 邮箱：`lark-cli mail ...`
    - 任务：`lark-cli task ...`

    不要自行创建定时任务或总结流程，除非用户明确要求。
""".trimIndent()
