package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private class SysuPageState {
    var netId by mutableStateOf("")
    var password by mutableStateOf("")
    var passwordSaved by mutableStateOf(false)
    var code by mutableStateOf("")
    var busy by mutableStateOf(false)
    var progress by mutableStateOf<String?>(null)
    var status by mutableStateOf<SysuStatus?>(null)
    var codeRequired by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
internal fun SysuPage(plugin: SysuPlugin, host: PluginHost) {
    val state = remember { SysuPageState() }

    LaunchedEffect(host) {
        state.busy = true
        try {
            val secrets = runCatching { host.secrets() }.getOrNull()
            state.netId = runCatching { secrets?.get(SysuCli.SECRET_NET_ID) }.getOrNull().orEmpty()
            state.passwordSaved =
                !runCatching { secrets?.get(SysuCli.SECRET_PASSWORD) }.getOrNull().isNullOrBlank()
            state.status = plugin.loadStatus(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.error = error.message
        } finally {
            state.busy = false
        }
    }

    fun runAction(action: suspend () -> Unit) {
        if (state.busy) return
        host.scope.launch {
            state.busy = true
            state.error = null
            state.progress = null
            try {
                action()
                host.requestContributionsRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
            } finally {
                state.busy = false
                state.progress = null
            }
        }
    }

    val saveAndLogin: () -> Unit = {
        val netId = state.netId.trim()
        val typedPassword = state.password
        runAction {
            if (netId.isEmpty()) throw SysuException(host.text(TEXT_ERROR_NET_ID))
            val savedPassword = runCatching { host.secrets().get(SysuCli.SECRET_PASSWORD) }
                .getOrNull()
                .orEmpty()
            val password = typedPassword.ifEmpty { savedPassword }
            if (password.isEmpty()) throw SysuException(host.text(TEXT_ERROR_PASSWORD))
            plugin.saveAccount(host, netId, password)
            state.password = ""
            state.passwordSaved = true
            plugin.ensureInstalled(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            val needsCode = plugin.startLogin(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            if (needsCode) {
                state.codeRequired = true
                host.ui().snackbar(host.text(TEXT_CODE_SENT))
            } else {
                state.codeRequired = false
                host.ui().snackbar(host.text(TEXT_SIGNED_IN))
            }
        }
    }

    val relogin: () -> Unit = {
        runAction {
            val secrets = host.secrets()
            val netId = runCatching { secrets.get(SysuCli.SECRET_NET_ID) }.getOrNull()?.trim().orEmpty()
            val password = runCatching { secrets.get(SysuCli.SECRET_PASSWORD) }.getOrNull().orEmpty()
            if (netId.isEmpty() || password.isEmpty()) throw SysuException(host.text(TEXT_ERROR_CREDENTIALS))
            plugin.logout(host)
            plugin.saveAccount(host, netId, password)
            plugin.ensureInstalled(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            val needsCode = plugin.startLogin(host) { state.progress = it }
            state.status = plugin.loadStatus(host)
            if (needsCode) {
                state.codeRequired = true
                host.ui().snackbar(host.text(TEXT_CODE_SENT))
            } else {
                state.codeRequired = false
                host.ui().snackbar(host.text(TEXT_SIGNED_IN))
            }
        }
    }

    val signOut: () -> Unit = {
        host.scope.launch {
            val confirmed = host.ui().confirm(
                uiConfirmRequest(
                    title = host.text(TEXT_CONFIRM_SIGN_OUT_TITLE),
                    message = host.text(TEXT_CONFIRM_SIGN_OUT_BODY),
                    confirmLabel = host.text(TEXT_SIGN_OUT),
                    cancelLabel = host.text(TEXT_CANCEL),
                    destructive = true,
                ),
            )
            if (confirmed) {
                runAction {
                    plugin.logout(host)
                    state.codeRequired = false
                    state.status = plugin.loadStatus(host)
                    host.ui().snackbar(host.text(TEXT_SIGNED_OUT))
                }
            }
        }
    }

    val submitCode: () -> Unit = {
        val code = state.code.trim()
        runAction {
            if (!SysuCli.CODE_PATTERN.matches(code)) throw SysuException(host.text(TEXT_ERROR_CODE))
            plugin.submitCode(host, code) { state.progress = it }
            state.code = ""
            state.codeRequired = false
            state.status = plugin.loadStatus(host)
            host.ui().snackbar(host.text(TEXT_SIGNED_IN))
        }
    }

    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(host, state.status)
        statusHint(host, state.status)?.let { hint ->
            Text(
                text = hint,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.inkTertiary,
            )
        }
        if (state.busy) DdLoadingBlock(text = state.progress)
        state.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.danger,
            )
        }
        if (state.codeRequired) {
            CodeCard(
                host = host,
                state = state,
                onSubmit = submitCode,
                onCancel = {
                    state.codeRequired = false
                    state.code = ""
                },
            )
        } else {
            AccountCard(
                host = host,
                state = state,
                onSaveAndLogin = saveAndLogin,
                onRelogin = relogin,
                onSignOut = signOut,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(host: PluginHost, status: SysuStatus?) {
    DdGroupCard {
        DdListRow(title = host.text(TEXT_RUNTIME), value = runtimeValue(host, status))
        DdListRow(title = host.text(TEXT_NODE_JS), value = nodeValue(host, status))
        DdListRow(title = host.text(TEXT_CLI), value = cliValue(host, status))
        DdListRow(title = host.text(TEXT_ACCOUNT), value = status?.account ?: host.text(TEXT_NOT_SET))
        DdListRow(title = host.text(TEXT_SIGN_IN_STATE), value = signInValue(host, status))
    }
}

@Composable
private fun AccountCard(
    host: PluginHost,
    state: SysuPageState,
    onSaveAndLogin: () -> Unit,
    onRelogin: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DdTextField(
                value = state.netId,
                onValueChange = { if (!state.busy) state.netId = it },
                placeholder = host.text(TEXT_NET_ID),
                modifier = Modifier.fillMaxWidth(),
            )
            DdTextField(
                value = state.password,
                onValueChange = { if (!state.busy) state.password = it },
                placeholder = host.text(TEXT_PASSWORD),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (state.passwordSaved) {
                Text(
                    text = host.text(TEXT_PASSWORD_SAVED),
                    style = DdType.bodySmall,
                    color = colors.inkTertiary,
                )
            }
            DdPrimaryButton(
                text = host.text(TEXT_SAVE_AND_LOGIN),
                onClick = onSaveAndLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(text = host.text(TEXT_RELOGIN), onClick = onRelogin, enabled = !state.busy)
                Spacer(Modifier.width(24.dp))
                DdTextAction(
                    text = host.text(TEXT_SIGN_OUT),
                    onClick = onSignOut,
                    color = colors.danger,
                    enabled = !state.busy && state.status?.loggedIn == true,
                )
            }
        }
    }
}

@Composable
private fun CodeCard(
    host: PluginHost,
    state: SysuPageState,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = host.text(TEXT_CODE_HINT),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
            DdTextField(
                value = state.code,
                onValueChange = { value ->
                    if (!state.busy) state.code = value.filter { it.isDigit() }.take(4)
                },
                placeholder = host.text(TEXT_CODE_LABEL),
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            DdPrimaryButton(
                text = host.text(TEXT_COMPLETE_LOGIN),
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DdTextAction(text = host.text(TEXT_CANCEL), onClick = onCancel, enabled = !state.busy)
            }
        }
    }
}

private fun statusHint(host: PluginHost, status: SysuStatus?): String? = when {
    status == null -> null
    !status.linuxAvailable -> host.text(TEXT_HINT_LINUX)
    status.nodeVersion == null -> host.text(TEXT_HINT_NODE)
    !status.installed -> host.text(TEXT_HINT_INSTALL)
    status.outdated -> host.text(TEXT_HINT_UPDATE)
    else -> null
}

private fun runtimeValue(host: PluginHost, status: SysuStatus?): String = when {
    status == null -> host.text(TEXT_CHECKING)
    status.linuxAvailable -> host.text(TEXT_AVAILABLE)
    else -> host.text(TEXT_UNAVAILABLE)
}

private fun nodeValue(host: PluginHost, status: SysuStatus?): String = when {
    status == null -> host.text(TEXT_CHECKING)
    !status.linuxAvailable -> host.text(TEXT_UNKNOWN)
    status.nodeVersion == null -> host.text(TEXT_NOT_INSTALLED)
    status.nodeTooOld -> host.text(TEXT_NODE_TOO_OLD, status.nodeVersion.orEmpty())
    else -> status.nodeVersion.orEmpty()
}

private fun cliValue(host: PluginHost, status: SysuStatus?): String = when {
    status == null -> host.text(TEXT_CHECKING)
    !status.linuxAvailable -> host.text(TEXT_UNKNOWN)
    status.installedVersion != null -> "v${status.installedVersion}"
    status.recordedVersion != null -> host.text(TEXT_CLI_RECORD, status.recordedVersion.orEmpty())
    else -> host.text(TEXT_NOT_INSTALLED)
}

private fun signInValue(host: PluginHost, status: SysuStatus?): String = when {
    status == null -> host.text(TEXT_CHECKING)
    status.loggedIn -> host.text(TEXT_SIGNED_IN_STATE)
    else -> host.text(TEXT_NOT_SIGNED_IN)
}

private val TEXT_RUNTIME = literal("运行环境", "Runtime")
private val TEXT_NODE_JS = literal("Node.js", "Node.js")
private val TEXT_CLI = literal("校园 CLI", "Campus CLI")
private val TEXT_ACCOUNT = literal("账号", "Account")
private val TEXT_SIGN_IN_STATE = literal("登录状态", "Sign-in")
private val TEXT_AVAILABLE = literal("可用", "Available")
private val TEXT_UNAVAILABLE = literal("不可用", "Unavailable")
private val TEXT_UNKNOWN = literal("未知", "Unknown")
private val TEXT_CHECKING = literal("检查中…", "Checking…")
private val TEXT_NOT_INSTALLED = literal("未安装", "Not installed")
private val TEXT_NOT_SET = literal("未设置", "Not set")
private val TEXT_SIGNED_IN_STATE = literal("已登录", "Signed in")
private val TEXT_NOT_SIGNED_IN = literal("未登录", "Not signed in")
private val TEXT_NODE_TOO_OLD = literal("%1\$s（需要 22 或更高）", "%1\$s (version 22 or newer required)")
private val TEXT_CLI_RECORD = literal("未找到（上次安装 %1\$s）", "Not found (last installed %1\$s)")
private val TEXT_HINT_LINUX = literal(
    "请先在 设置 → 运行环境 安装 Linux 环境",
    "Install the Linux environment in Settings → Runtime first",
)
private val TEXT_HINT_NODE = literal(
    "缺少 Node.js，请到 设置 → 运行环境 安装 Node.js（需要 22 或更高）",
    "Node.js is missing. Install Node.js (version 22 or newer) in Settings → Runtime.",
)
private val TEXT_HINT_INSTALL = literal(
    "尚未安装校园 CLI，点「保存并登录」会自动安装",
    "The campus CLI is not installed yet; tap \"Save and sign in\" to install it automatically.",
)
private val TEXT_HINT_UPDATE = literal(
    "校园 CLI 版本不是最新，点「保存并登录」会自动更新",
    "The campus CLI is not up to date; tap \"Save and sign in\" to update it automatically.",
)
private val TEXT_NET_ID = literal("NetID", "NetID")
private val TEXT_PASSWORD = literal("密码", "Password")
private val TEXT_PASSWORD_SAVED = literal("密码已保存，留空则不修改", "Password saved; leave blank to keep it")
private val TEXT_SAVE_AND_LOGIN = literal("保存并登录", "Save and sign in")
private val TEXT_RELOGIN = literal("重新登录", "Sign in again")
private val TEXT_SIGN_OUT = literal("退出登录", "Sign out")
private val TEXT_CODE_LABEL = literal("4 位验证码", "4-digit code")
private val TEXT_CODE_HINT = literal(
    "验证码已发送到企业微信（10 分钟内有效），请输入 4 位验证码",
    "A 4-digit code was sent to WeCom (valid for 10 minutes). Enter it to finish signing in.",
)
private val TEXT_COMPLETE_LOGIN = literal("完成登录", "Complete sign-in")
private val TEXT_CANCEL = literal("取消", "Cancel")
private val TEXT_ERROR_NET_ID = literal("请输入 NetID", "Enter your NetID")
private val TEXT_ERROR_PASSWORD = literal("请输入密码", "Enter your password")
private val TEXT_ERROR_CREDENTIALS = literal("请先填写并保存账号密码", "Enter and save your account credentials first")
private val TEXT_ERROR_CODE = literal("请输入 4 位数字验证码", "Enter the 4-digit verification code")
private val TEXT_CODE_SENT = literal(
    "验证码已发送，请输入企业微信里的 4 位验证码",
    "Code sent. Enter the 4-digit code from WeCom.",
)
private val TEXT_SIGNED_IN = literal("登录成功", "Signed in successfully")
private val TEXT_SIGNED_OUT = literal("已退出登录", "Signed out")
private val TEXT_CONFIRM_SIGN_OUT_TITLE = literal("退出中山大学登录？", "Sign out of SYSU?")
private val TEXT_CONFIRM_SIGN_OUT_BODY = literal(
    "退出后需要重新登录才能继续使用校园服务。",
    "You will need to sign in again to use campus services.",
)
private val TEXT_ERROR_UNKNOWN = literal("操作失败，请重试", "Something went wrong. Try again.")
