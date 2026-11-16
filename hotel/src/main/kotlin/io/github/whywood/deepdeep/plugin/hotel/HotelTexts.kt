package io.github.whywood.deepdeep.plugin.hotel

import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText

internal fun literal(zh: String, en: String): LocalizedText = localizedText(mapOf("default" to zh, "zh" to zh, "en" to en))

internal fun PluginHost.t(zh: String, en: String): String = text(literal(zh, en))

internal fun PluginHost.tf(zh: String, en: String, vararg args: Any): String = text(literal(zh, en), *args)

internal val STAGE_INSTALL = literal("正在安装酒店 CLI…", "Installing hotel CLI…")
internal val STAGE_UPDATE = literal("正在更新酒店 CLI…", "Updating hotel CLI…")
internal val STAGE_CHECK = literal("正在检查登录状态…", "Checking sign-in status…")
internal val STAGE_LOGIN = literal("正在准备登录…", "Preparing sign-in…")
internal val STAGE_WAITING = literal("等待浏览器授权…", "Waiting for browser authorization…")
internal val STAGE_LOGOUT = literal("正在退出登录…", "Signing out…")
internal val ERROR_UNKNOWN = literal("操作失败，请重试。", "The operation failed; try again.")
internal val ERROR_INSTALL = literal("安装失败：%s", "Install failed: %s")
internal val ERROR_LOGIN = literal("登录未完成，请重试。", "Sign-in did not complete; try again.")
internal val ERROR_LINUX = literal("需要 Linux 运行环境。", "A Linux environment is required.")
internal val ERROR_COPY = literal("复制失败", "Copy failed")
internal val SNACKBAR_INSTALLED = literal("酒店 CLI 安装完成", "Hotel CLI installed")
internal val SNACKBAR_UPDATED = literal("酒店 CLI 已更新", "Hotel CLI updated")
internal val ERROR_UPDATE = literal("更新失败：%s", "Update failed: %s")
internal val SNACKBAR_LOGGED_IN = literal("酒店账号已登录", "Hotel account signed in")
internal val SNACKBAR_COPIED = literal("授权链接已复制", "Authorization link copied")
