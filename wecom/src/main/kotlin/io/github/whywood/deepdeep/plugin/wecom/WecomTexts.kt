package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.localizedText

internal object WecomTexts {
    val pageTitle = literal("企业微信", "WeCom")

    val unavailableLinux = literal(
        "当前设备不支持 Linux 环境，或尚未安装 Linux 环境；请先在 设置 → 运行环境 中安装。",
        "Linux is not supported on this device or not installed; install it in Settings → Runtime first.",
    )

    val statusTitle = literal("状态", "Status")
    val cliVersion = literal("CLI 版本", "CLI version")
    val notInstalled = literal("未安装", "Not installed")
    val authorization = literal("授权", "Authorization")
    val checking = literal("检测中…", "Checking…")
    val connected = literal("已连接", "Connected")
    val notConnected = literal("未连接", "Not connected")

    val installCli = literal("安装/更新 CLI", "Install/Update CLI")
    val connect = literal("连接企业微信", "Connect WeCom")
    val reauthorize = literal("重新授权", "Reauthorize")
    val logout = literal("退出登录", "Sign out")
    val installHint = literal(
        "CLI 会安装到 Linux 环境的 /usr/local/bin/wecom-cli，需要 Linux 环境可用。",
        "The CLI is installed to /usr/local/bin/wecom-cli in the Linux environment, which must be available.",
    )

    val stageDownloading = literal("正在下载企业微信 CLI…", "Downloading WeCom CLI…")
    val stageInstalling = literal("正在安装企业微信 CLI…", "Installing WeCom CLI…")
    val installDone = literal("企业微信 CLI 已安装", "WeCom CLI installed")
    val errorVersion = literal("安装包版本不匹配：%s", "Package version mismatch: %s")
    val errorInstall = literal("安装失败：%s", "Install failed: %s")

    val stageScan = literal("扫码连接企业微信", "Scan to connect WeCom")
    val hintScan = literal(
        "用企业微信扫描二维码，或打开链接完成授权；完成后会自动继续。",
        "Scan the QR code with WeCom, or open the link to authorize; sign-in continues automatically.",
    )
    val openLink = literal("打开链接", "Open link")
    val stageFinishing = literal("正在完成登录…", "Finishing sign-in…")
    val errorTimeout = literal("扫码超时，请重新连接", "Scan timed out; connect again")
    val errorAuth = literal("企业微信授权失败：%s", "WeCom authorization failed: %s")
    val errorWorkspace = literal(
        "插件工作目录不可用，请重启应用后重试",
        "Plugin workspace directory is unavailable; restart the app and try again.",
    )

    val stageSigningOut = literal("正在退出登录…", "Signing out…")
    val errorLogout = literal("退出登录失败：%s", "Sign-out failed: %s")
    val statusError = literal("状态检测失败：%s", "Status check failed: %s")

    val statusLoading = literal(
        "企业微信插件状态尚未加载完成，请让用户打开插件页查看。",
        "The WeCom plugin status is not loaded yet; ask the user to open the plugin page.",
    )

    val confirmLogoutTitle = literal("退出企业微信登录？", "Sign out of WeCom?")
    val confirmLogoutBody = literal(
        "退出后需要重新扫码授权才能继续使用企业微信能力。",
        "You will need to scan and authorize again to use WeCom.",
    )
    val confirmLogoutOk = literal("退出登录", "Sign out")
    val confirmReauthTitle = literal("重新授权企业微信？", "Reauthorize WeCom?")
    val confirmReauthBody = literal(
        "当前授权会切换到新的企业微信账号。",
        "The current authorization will switch to a new WeCom account.",
    )
    val confirmReauthOk = literal("重新授权", "Reauthorize")
    val confirmCancel = literal("取消", "Cancel")

    val snackbarConnected = literal("企业微信已连接", "WeCom connected")
    val snackbarLoggedOut = literal("已退出企业微信登录", "Signed out of WeCom")

    const val NOTIFY_CLI_ID = "wecom-cli"
    const val ACTION_OPEN_PAGE = "open-page"
    val actionOpenPage = literal("打开插件页", "Open plugin page")
    val notifyTitle = literal("企业微信 CLI 已就绪", "WeCom CLI is ready")
    val notifyBody = literal(
        "CLI 安装完成，打开插件页扫码连接企业微信。",
        "The CLI is installed. Open the plugin page to scan and connect WeCom.",
    )

    private fun literal(zh: String, en: String): LocalizedText =
        localizedText(mapOf("default" to zh, "zh" to zh, "en" to en))
}
