package io.github.whywood.deepdeep.plugin.wecom

import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.localizedText

internal object WecomTexts {
    val pageTitle = literal("企业微信", "WeCom")

    val unavailableLinux = literal(
        "当前设备不支持 Linux 环境，或尚未安装 Linux 环境；请先在 设置 → 运行环境 中安装。",
        "Linux is not supported on this device or not installed; install it in Settings → Runtime first.",
    )

    val runtime = literal("运行环境", "Runtime")
    val ready = literal("已就绪", "Ready")
    val unavailable = literal("不可用", "Unavailable")
    val component = literal("组件", "Component")
    val notInstalled = literal("未安装", "Not installed")
    val authorization = literal("授权", "Authorization")
    val checking = literal("检查中…", "Checking…")
    val checkFailed = literal("检查失败", "Check failed")
    val connected = literal("已连接", "Connected")
    val notConnected = literal("未连接", "Not connected")
    val botId = literal("机器人 ID", "Bot ID")

    val install = literal("安装", "Install")
    val update = literal("更新组件", "Update")
    val connect = literal("连接企业微信", "Connect WeCom")
    val reauthorize = literal("重新授权", "Reauthorize")
    val logout = literal("退出登录", "Sign out")
    val installHint = literal(
        "安装后即可扫码连接企业微信。",
        "Install it, then scan to connect WeCom.",
    )

    val stageWorking = literal("正在处理…", "Working…")
    val stagePreparing = literal("正在生成二维码…", "Creating QR code…")
    val stageWaiting = literal("等待扫码…", "Waiting for scan…")
    val stageScan = literal("扫码连接企业微信", "Scan to connect WeCom")
    val hintScan = literal(
        "用企业微信扫描二维码，或打开链接完成授权；完成后会自动继续。",
        "Scan the QR code with WeCom, or open the link to authorize; sign-in continues automatically.",
    )
    val openLink = literal("打开链接", "Open link")
    val stageFinishing = literal("正在完成登录…", "Finishing sign-in…")
    val errorTimeout = literal("扫码超时，请重新连接", "Scan timed out; connect again")
    val errorAuth = literal("企业微信授权失败：%s", "WeCom authorization failed: %s")
    val errorExitCode = literal("退出码 %d", "Exit code %d")
    val errorWorkspace = literal(
        "无法写入临时文件，请重启应用后重试。",
        "Couldn't write a temporary file; restart the app and try again.",
    )

    val stageDownloading = literal("正在下载安装包…", "Downloading the installer…")
    val stageVerifying = literal("正在校验安装包…", "Verifying the package…")
    val stageInstalling = literal("正在安装组件…", "Installing the component…")
    val stageFinishingInstall = literal("正在完成安装…", "Finishing installation…")
    val installDone = literal("企业微信组件已安装", "WeCom component installed")
    val errorDownload = literal("下载安装包失败：%s", "Installer download failed: %s")
    val errorVersion = literal("安装包版本不匹配：%s", "Package version mismatch: %s")
    val errorInstall = literal("安装失败：%s", "Install failed: %s")

    val stageSigningOut = literal("正在退出登录…", "Signing out…")
    val errorLogout = literal("退出登录失败：%s", "Sign-out failed: %s")
    val statusError = literal("状态检测失败：%s", "Status check failed: %s")
    val stageCancelling = literal("正在取消…", "Cancelling…")

    val cancel = literal("取消", "Cancel")
    val retry = literal("重试", "Retry")
    val recheck = literal("重新检查", "Check again")

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
    val notifyTitle = literal("企业微信组件已就绪", "WeCom component is ready")
    val notifyBody = literal(
        "组件安装完成，打开插件页扫码连接企业微信。",
        "The component is installed. Open the plugin page to scan and connect WeCom.",
    )

    private fun literal(zh: String, en: String): LocalizedText =
        localizedText(mapOf("default" to zh, "zh" to zh, "en" to en))
}
