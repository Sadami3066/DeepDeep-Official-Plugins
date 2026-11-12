package io.github.whywood.deepdeep.plugin.sysu

import android.os.Build
import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.LinuxService
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.PluginPage
import io.github.whywood.deepdeep.plugin.api.PluginSkill
import io.github.whywood.deepdeep.plugin.api.PluginUiContributions
import io.github.whywood.deepdeep.plugin.api.PromptSection
import io.github.whywood.deepdeep.plugin.api.SlotBinding
import io.github.whywood.deepdeep.plugin.api.assetIcon
import io.github.whywood.deepdeep.plugin.api.available
import io.github.whywood.deepdeep.plugin.api.localizedText
import io.github.whywood.deepdeep.plugin.api.pluginPage
import io.github.whywood.deepdeep.plugin.api.pluginSkill
import io.github.whywood.deepdeep.plugin.api.promptSection
import io.github.whywood.deepdeep.plugin.api.unavailable
import java.io.File

class SysuPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability {
        if (SUPPORTED_ABI !in Build.SUPPORTED_ABIS) {
            return unavailable(host.text(TEXT_UNAVAILABLE_DEVICE))
        }
        if (!linuxAvailable(host)) {
            return unavailable(host.text(TEXT_UNAVAILABLE_LINUX))
        }
        return available()
    }

    override suspend fun onEnable(host: PluginHost) {
        runCatching { loadStatus(host) }
    }

    override suspend fun uninstall(host: PluginHost) {
        runCatching { host.secrets().clear() }
        val linux = linuxOrNull(host)
        if (linux != null && linux.available) {
            runCatching {
                linux.exec(
                    listOf(SysuCli.logoutCommand(), SysuCli.cleanupCommand(), SysuCli.purgeCommand()).joinToString("; "),
                    timeoutMs = WRITE_TIMEOUT_MS,
                )
            }
        }
        SysuStatusStore.clear(host)
    }

    override fun skills(host: PluginHost): List<PluginSkill> =
        listOf(pluginSkill(SysuCli.SKILL_ID, SysuContent.skillMarkdown()))

    override fun promptSections(host: PluginHost): List<PromptSection> {
        val stored = SysuStatusStore.read(host)
        val status = SysuStatus(
            linuxAvailable = linuxAvailable(host),
            nodeVersion = stored?.nodeVersion,
            installedVersion = stored?.installedVersion,
            account = stored?.account,
            loggedIn = stored?.loggedIn == true,
        )
        return listOf(promptSection(SECTION_ID, SysuContent.promptBody(status), PROMPT_PRIORITY))
    }

    override fun ui(host: PluginHost): PluginUiContributions = object : PluginUiContributions {
        override val bindings: List<SlotBinding> = emptyList()

        override val pages: List<PluginPage> = listOf(
            pluginPage(
                id = PAGE_ID,
                title = TEXT_PAGE_TITLE,
                icon = assetIcon(ICON_ASSET),
                content = { SysuPage(this@SysuPlugin, host) },
            ),
        )
    }

    internal suspend fun loadStatus(host: PluginHost): SysuStatus {
        if (!linuxAvailable(host)) return SysuStatus(linuxAvailable = false)
        val linux = linuxOrNull(host) ?: return SysuStatus(linuxAvailable = false)
        val recorded = SysuStatusStore.read(host)
        val output = runCatching {
            linux.exec(SysuCli.statusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
        }.getOrNull()?.stdout.orEmpty()
        val raw = SysuCli.parseStatus(output)
        val savedNetId = runCatching { host.secrets().get(SysuCli.SECRET_NET_ID) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val status = SysuStatus(
            linuxAvailable = true,
            nodeVersion = SysuCli.parseNodeVersion(raw.node),
            installedVersion = SysuCli.parseVersion(raw.cli),
            recordedVersion = recorded?.installedVersion,
            account = SysuCli.netId(raw.credentials) ?: savedNetId,
            loggedIn = SysuCli.isLoggedIn(raw.session),
        )
        SysuStatusStore.write(
            host,
            SysuStoredStatus(
                installedVersion = status.installedVersion,
                nodeVersion = status.nodeVersion,
                account = status.account,
                loggedIn = status.loggedIn,
                checkedAt = System.currentTimeMillis(),
            ),
        )
        return status
    }

    internal suspend fun saveAccount(host: PluginHost, netId: String, password: String) {
        val secrets = host.secrets()
        secrets.put(SysuCli.SECRET_NET_ID, netId)
        secrets.put(SysuCli.SECRET_PASSWORD, password)
        val linux = linuxOrNull(host)
        if (linux == null || !linux.available) return
        val previous = runCatching { linux.exec(SysuCli.credentialsReadCommand(), timeoutMs = WRITE_TIMEOUT_MS) }
            .getOrNull()
            ?.let { SysuCli.netId(it.stdout) }
        val result = linux.exec(SysuCli.credentialsCommand(netId, password), timeoutMs = WRITE_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw SysuException(
                host.text(TEXT_ERROR_SAVE, SysuCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)),
            )
        }
        val changed = previous != null && previous != netId
        if (changed) {
            runCatching { linux.exec(SysuCli.logoutCommand(), timeoutMs = WRITE_TIMEOUT_MS) }
        }
        val stored = SysuStatusStore.read(host)
        SysuStatusStore.write(
            host,
            SysuStoredStatus(
                installedVersion = stored?.installedVersion,
                nodeVersion = stored?.nodeVersion,
                account = netId,
                loggedIn = if (changed) false else stored?.loggedIn == true,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    internal suspend fun ensureInstalled(host: PluginHost, onProgress: (String) -> Unit): String {
        val linux = requireLinux(host)
        onProgress(host.text(TEXT_STAGE_CHECKING))
        val nodeVersion = SysuCli.parseNodeVersion(
            linux.exec(SysuCli.nodeVersionCommand(), timeoutMs = NODE_TIMEOUT_MS).stdout,
        ) ?: throw SysuException(host.text(TEXT_ERROR_NODE_MISSING))
        val nodeMajor = SysuCli.parseNodeMajor(nodeVersion)
        if (nodeMajor == null || nodeMajor < SysuCli.MIN_NODE_MAJOR) {
            throw SysuException(host.text(TEXT_ERROR_NODE_OLD, nodeVersion))
        }
        val installed = runCatching {
            linux.exec(SysuCli.versionCommand(), timeoutMs = NODE_TIMEOUT_MS)
        }.getOrNull()
            ?.takeIf { it.exitCode == 0 }
            ?.let { SysuCli.parseVersion(it.stdout) }
        if (installed == SysuCli.VERSION) {
            recordInstall(host, installed, nodeVersion)
            return installed
        }
        val assetDir = File(host.assetsDir, SysuCli.ASSET_ROOT)
        if (!File(assetDir, SysuCli.ENTRY_RELATIVE).isFile) {
            throw SysuException(host.text(TEXT_ERROR_ASSET, SysuCli.ENTRY_RELATIVE))
        }
        onProgress(host.text(TEXT_STAGE_INSTALLING))
        try {
            val result = linux.exec(SysuCli.installCommand(assetDir.absolutePath), timeoutMs = INSTALL_TIMEOUT_MS)
            val version = SysuCli.parseVersion(result.stdout)
            if (result.exitCode != 0 || version != SysuCli.VERSION) {
                throw SysuException(
                    host.text(TEXT_ERROR_INSTALL, SysuCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)),
                )
            }
            recordInstall(host, version, nodeVersion)
            return version
        } catch (error: Exception) {
            runCatching { linux.exec(SysuCli.cleanupCommand(), timeoutMs = WRITE_TIMEOUT_MS) }
            SysuStatusStore.clear(host)
            throw error
        }
    }

    internal suspend fun startLogin(host: PluginHost, onProgress: (String) -> Unit): Boolean {
        val linux = requireLinux(host)
        onProgress(host.text(TEXT_STAGE_LOGIN))
        val result = linux.exec(SysuCli.loginSendCodeCommand(), timeoutMs = LOGIN_TIMEOUT_MS)
        val output = SysuCli.cleanOutput(result.stdout)
        if (result.timedOut) throw SysuException(host.text(TEXT_ERROR_LOGIN_TIMEOUT))
        if (result.exitCode != 0) throw loginError(host, output)
        if (SysuCli.needsCode(output)) return true
        if (!SysuCli.loginSucceeded(output)) throw loginError(host, output)
        return false
    }

    internal suspend fun submitCode(host: PluginHost, code: String, onProgress: (String) -> Unit) {
        val linux = requireLinux(host)
        if (!SysuCli.CODE_PATTERN.matches(code)) throw SysuException(host.text(TEXT_ERROR_CODE_FORMAT))
        onProgress(host.text(TEXT_STAGE_CODE))
        val result = linux.exec(SysuCli.loginCodeCommand(code), timeoutMs = LOGIN_TIMEOUT_MS)
        val output = SysuCli.cleanOutput(result.stdout)
        if (result.timedOut) throw SysuException(host.text(TEXT_ERROR_LOGIN_TIMEOUT))
        if (result.exitCode != 0) throw loginError(host, output)
        if (!loadStatus(host).loggedIn) throw loginError(host, output)
    }

    internal suspend fun logout(host: PluginHost) {
        val linux = requireLinux(host)
        val result = linux.exec(SysuCli.logoutCommand(), timeoutMs = WRITE_TIMEOUT_MS)
        if (result.exitCode != 0) {
            throw SysuException(
                host.text(TEXT_ERROR_LOGOUT, SysuCli.cleanOutput(result.stdout).takeLast(ERROR_TAIL)),
            )
        }
        val stored = SysuStatusStore.read(host)
        if (stored != null) {
            SysuStatusStore.write(host, stored.copy(loggedIn = false, checkedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun recordInstall(host: PluginHost, version: String, nodeVersion: String) {
        val stored = SysuStatusStore.read(host)
        SysuStatusStore.write(
            host,
            SysuStoredStatus(
                installedVersion = version,
                nodeVersion = nodeVersion,
                account = stored?.account,
                loggedIn = stored?.loggedIn == true,
                checkedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun linuxAvailable(host: PluginHost): Boolean =
        runCatching { host.linux().available }.getOrDefault(false)

    private fun linuxOrNull(host: PluginHost): LinuxService? = runCatching { host.linux() }.getOrNull()

    private fun requireLinux(host: PluginHost): LinuxService {
        val linux = linuxOrNull(host)
        if (linux == null || !linux.available) throw SysuException(host.text(TEXT_UNAVAILABLE_LINUX))
        return linux
    }

    private fun loginError(host: PluginHost, output: String): SysuException {
        val detail = output.takeLast(ERROR_TAIL).trim()
        return SysuException(
            if (detail.isEmpty()) host.text(TEXT_ERROR_LOGIN_EMPTY) else host.text(TEXT_ERROR_LOGIN, detail),
        )
    }

    private companion object {
        const val PAGE_ID = "main"
        const val SECTION_ID = "sysu_prompt"
        const val ICON_ASSET = "icon.png"
        const val SUPPORTED_ABI = "arm64-v8a"
        const val PROMPT_PRIORITY = 10

        const val STATUS_TIMEOUT_MS = 30_000L
        const val NODE_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 30_000L
        const val INSTALL_TIMEOUT_MS = 60_000L
        const val LOGIN_TIMEOUT_MS = 180_000L
        const val ERROR_TAIL = 500

        val TEXT_UNAVAILABLE_DEVICE = literal(
            "当前设备不支持 Linux 环境",
            "Linux environment is not supported on this device",
        )
        val TEXT_UNAVAILABLE_LINUX = literal(
            "请先在 设置 → 运行环境 安装 Linux 环境",
            "Install the Linux environment in Settings → Runtime first",
        )
        val TEXT_STAGE_CHECKING = literal("正在检查 Node.js…", "Checking Node.js…")
        val TEXT_STAGE_INSTALLING = literal("正在安装校园 CLI…", "Installing the campus CLI…")
        val TEXT_STAGE_LOGIN = literal("正在登录…", "Signing in…")
        val TEXT_STAGE_CODE = literal("正在提交验证码…", "Submitting the verification code…")
        val TEXT_ERROR_NODE_MISSING = literal(
            "没有找到 Node.js：请到 设置 → 运行环境 安装 Node.js",
            "Node.js not found. Install Node.js in Settings → Runtime first.",
        )
        val TEXT_ERROR_NODE_OLD = literal(
            "Node.js 版本过低（%1\$s）：需要 22 或更高，请到 设置 → 运行环境 升级",
            "Node.js is too old (%1\$s). Version 22 or newer is required; upgrade it in Settings → Runtime.",
        )
        val TEXT_ERROR_INSTALL = literal("安装失败：%1\$s", "Install failed: %1\$s")
        val TEXT_ERROR_SAVE = literal("保存凭据失败：%1\$s", "Failed to save credentials: %1\$s")
        val TEXT_ERROR_ASSET = literal(
            "插件资源缺失：%1\$s，请重新安装插件",
            "Plugin asset is missing: %1\$s. Reinstall the plugin.",
        )
        val TEXT_ERROR_LOGIN = literal("登录失败：%1\$s", "Sign-in failed: %1\$s")
        val TEXT_ERROR_LOGIN_EMPTY = literal(
            "登录失败，请检查网络与账号密码后重试",
            "Sign-in failed. Check your network and credentials, then try again.",
        )
        val TEXT_ERROR_LOGIN_TIMEOUT = literal(
            "登录超时，请检查网络后重试",
            "Sign-in timed out. Check your network and try again.",
        )
        val TEXT_ERROR_LOGOUT = literal("退出登录失败：%1\$s", "Sign-out failed: %1\$s")
        val TEXT_ERROR_CODE_FORMAT = literal("请输入 4 位数字验证码", "Enter the 4-digit verification code")
        val TEXT_PAGE_TITLE = literal("中山大学 CLI", "SYSU CLI")
    }
}

internal class SysuException(message: String) : Exception(message)

internal fun literal(zh: String, en: String): LocalizedText =
    localizedText(mapOf("default" to zh, "zh" to zh, "en" to en))
