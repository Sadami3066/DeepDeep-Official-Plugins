package io.github.whywood.deepdeep.plugin.sysu

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SysuStep {
    Account,
    Code,
}

private enum class SysuStage {
    Checking,
    Account,
    Code,
    SignedIn,
}

private enum class SysuFailure {
    None,
    Status,
    SignIn,
    SubmitCode,
    Resend,
    SignOut,
}

private class SysuPageState {
    var netId by mutableStateOf("")
    var netIdError by mutableStateOf<String?>(null)
    var password by mutableStateOf("")
    var passwordVisible by mutableStateOf(false)
    var passwordError by mutableStateOf<String?>(null)
    var passwordSaved by mutableStateOf(false)
    var code by mutableStateOf("")
    var codeError by mutableStateOf<String?>(null)
    var step by mutableStateOf(SysuStep.Account)
    var busy by mutableStateOf(false)
    var checking by mutableStateOf(true)
    var progress by mutableStateOf<String?>(null)
    var status by mutableStateOf<SysuStatus?>(null)
    var error by mutableStateOf<String?>(null)
    var failure by mutableStateOf(SysuFailure.None)
    var codeDeadline by mutableStateOf(0L)
    var codeRemaining by mutableStateOf(0)
}

@Composable
internal fun SysuPage(plugin: SysuPlugin, host: PluginHost) {
    val state = remember { SysuPageState() }
    val motionEnabled = LocalDdMotionEnabled.current

    LaunchedEffect(host) {
        state.checking = true
        try {
            val secrets = runCatching { host.secrets() }.getOrNull()
            state.netId = runCatching { secrets?.get(SysuCli.SECRET_NET_ID) }.getOrNull().orEmpty()
            state.passwordSaved =
                !runCatching { secrets?.get(SysuCli.SECRET_PASSWORD) }.getOrNull().isNullOrBlank()
            state.status = plugin.loadStatus(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
            state.failure = SysuFailure.Status
        } finally {
            state.checking = false
        }
    }

    LaunchedEffect(state.codeDeadline) {
        if (state.codeDeadline == 0L) {
            state.codeRemaining = 0
            return@LaunchedEffect
        }
        while (true) {
            val remaining = ((state.codeDeadline - System.currentTimeMillis() + 999L) / 1000L).toInt()
            state.codeRemaining = remaining.coerceAtLeast(0)
            if (remaining <= 0) break
            delay(250L)
        }
    }

    suspend fun reloadStatus(markChecking: Boolean = false) {
        if (markChecking) state.checking = true
        try {
            state.status = plugin.loadStatus(host)
        } finally {
            if (markChecking) state.checking = false
        }
    }

    suspend fun reloadAfterSuccess() {
        try {
            reloadStatus()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
            state.failure = SysuFailure.Status
        }
    }

    fun runAction(failure: SysuFailure, action: suspend () -> Unit) {
        if (state.busy) return
        host.scope.launch {
            state.busy = true
            state.error = null
            state.failure = SysuFailure.None
            state.progress = null
            try {
                action()
                host.requestContributionsRefresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.error = error.message ?: host.text(TEXT_ERROR_UNKNOWN)
                state.failure = failure
            } finally {
                state.busy = false
                state.progress = null
            }
        }
    }

    val refreshStatus: () -> Unit = {
        runAction(SysuFailure.Status) { reloadStatus(markChecking = true) }
    }

    val signIn: () -> Unit = {
        val netId = state.netId.trim()
        state.netIdError = if (netId.isEmpty()) host.text(TEXT_ERROR_NET_ID) else null
        state.passwordError =
            if (state.password.isEmpty() && !state.passwordSaved) host.text(TEXT_ERROR_PASSWORD) else null
        if (state.netIdError == null && state.passwordError == null) {
            runAction(SysuFailure.SignIn) {
                val password = state.password.ifEmpty {
                    runCatching { host.secrets().get(SysuCli.SECRET_PASSWORD) }.getOrNull().orEmpty()
                }
                if (password.isEmpty()) throw SysuException(host.text(TEXT_ERROR_PASSWORD))
                plugin.saveAccount(host, netId, password)
                state.password = ""
                state.passwordSaved = true
                plugin.ensureInstalled(host) { state.progress = it }
                val needsCode = plugin.startLogin(host) { state.progress = it }
                if (needsCode) {
                    state.code = ""
                    state.codeError = null
                    state.codeDeadline = System.currentTimeMillis() + CODE_RESEND_MILLIS
                    state.codeRemaining = CODE_RESEND_SECONDS
                    state.step = SysuStep.Code
                } else {
                    state.status = state.status?.copy(loggedIn = true, account = netId)
                    state.step = SysuStep.Account
                    host.ui().snackbar(host.text(TEXT_SIGNED_IN))
                }
                reloadAfterSuccess()
            }
        }
    }

    val submitCode: () -> Unit = {
        val code = state.code.trim()
        state.codeError = if (SysuCli.CODE_PATTERN.matches(code)) null else host.text(TEXT_ERROR_CODE)
        if (state.codeError == null) {
            runAction(SysuFailure.SubmitCode) {
                plugin.submitCode(host, code) { state.progress = it }
                state.status = state.status?.copy(loggedIn = true)
                state.code = ""
                state.codeDeadline = 0L
                state.step = SysuStep.Account
                reloadAfterSuccess()
                host.ui().snackbar(host.text(TEXT_SIGNED_IN))
            }
        }
    }

    val sendCode: () -> Unit = {
        runAction(SysuFailure.Resend) {
            val needsCode = plugin.startLogin(host) { state.progress = it }
            if (needsCode) {
                state.code = ""
                state.codeError = null
                state.codeDeadline = System.currentTimeMillis() + CODE_RESEND_MILLIS
                state.codeRemaining = CODE_RESEND_SECONDS
                host.ui().snackbar(host.text(TEXT_CODE_RESENT))
            } else {
                state.status = state.status?.copy(loggedIn = true)
                state.codeDeadline = 0L
                state.step = SysuStep.Account
                reloadAfterSuccess()
                host.ui().snackbar(host.text(TEXT_SIGNED_IN))
            }
        }
    }

    val resend: () -> Unit = {
        if (!state.busy && state.codeRemaining <= 0) sendCode()
    }

    val back: () -> Unit = {
        if (!state.busy) {
            state.code = ""
            state.codeError = null
            state.codeDeadline = 0L
            state.step = SysuStep.Account
        }
    }

    val performSignOut: () -> Unit = {
        runAction(SysuFailure.SignOut) {
            state.progress = host.text(TEXT_SIGNING_OUT)
            plugin.logout(host)
            state.status = state.status?.copy(loggedIn = false)
            state.codeDeadline = 0L
            state.step = SysuStep.Account
            reloadAfterSuccess()
            host.ui().snackbar(host.text(TEXT_SIGNED_OUT))
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
            if (confirmed) performSignOut()
        }
    }

    val status = state.status
    val stage = when {
        status == null -> SysuStage.Checking
        status.loggedIn -> SysuStage.SignedIn
        state.step == SysuStep.Code -> SysuStage.Code
        else -> SysuStage.Account
    }
    val retryAction: (() -> Unit)? = when (state.failure) {
        SysuFailure.Status -> refreshStatus
        SysuFailure.SignIn -> signIn
        SysuFailure.SubmitCode -> submitCode
        SysuFailure.Resend -> sendCode
        SysuFailure.SignOut -> performSignOut
        SysuFailure.None -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        StatusCard(host, state)
        state.error?.let { message ->
            DdNoticeBar(
                text = message,
                tone = DdNoticeTone.DANGER,
                actionLabel = retryAction?.let { host.text(TEXT_RETRY) },
                onAction = retryAction,
            )
        }
        environmentHint(host, status)?.let { hint ->
            DdNoticeBar(
                text = hint,
                tone = DdNoticeTone.WARNING,
                actionLabel = host.text(TEXT_RECHECK),
                onAction = refreshStatus,
            )
        }
        Crossfade(
            targetState = stage,
            animationSpec = tween(durationMillis = if (motionEnabled) DdMotion.BASE else 0),
            label = "sysu-stage",
        ) { current ->
            when (current) {
                SysuStage.Checking -> Unit
                SysuStage.Account -> SignInSection(
                    host = host,
                    state = state,
                    onSignIn = signIn,
                )
                SysuStage.Code -> CodeSection(
                    host = host,
                    state = state,
                    onBack = back,
                    onSubmit = submitCode,
                    onResend = resend,
                )
                SysuStage.SignedIn -> SignedInSection(
                    host = host,
                    state = state,
                    onSignOut = signOut,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(host: PluginHost, state: SysuPageState) {
    val status = state.status
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
        ) {
            DdStatusRow(
                label = host.text(TEXT_ACCOUNT),
                value = status?.account ?: state.netId.trim().ifEmpty { host.text(TEXT_NOT_SET) },
                tone = DdStatusTone.NEUTRAL,
            )
            DdStatusRow(
                label = host.text(TEXT_SIGN_IN_STATE),
                value = when {
                    status == null -> host.text(TEXT_CHECKING)
                    status.loggedIn -> host.text(TEXT_SIGNED_IN_STATE)
                    else -> host.text(TEXT_NOT_SIGNED_IN)
                },
                tone = if (status?.loggedIn == true) DdStatusTone.SUCCESS else DdStatusTone.NEUTRAL,
            )
            DdStatusRow(
                label = host.text(TEXT_ENVIRONMENT),
                value = environmentValue(host, status),
                tone = environmentTone(status),
                busy = state.checking,
            )
        }
    }
}

@Composable
private fun SignInSection(host: PluginHost, state: SysuPageState, onSignIn: () -> Unit) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        DdSectionLabel(host.text(TEXT_SIGN_IN_SECTION))
        DdGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DdSpacing.Card),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                    DdTextField(
                        value = state.netId,
                        onValueChange = {
                            if (!state.busy) {
                                state.netId = it
                                state.netIdError = null
                            }
                        },
                        placeholder = host.text(TEXT_NET_ID),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                        ),
                    )
                    state.netIdError?.let { FieldError(it) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DdTextField(
                            value = state.password,
                            onValueChange = {
                                if (!state.busy) {
                                    state.password = it
                                    state.passwordError = null
                                }
                            },
                            placeholder = host.text(TEXT_PASSWORD),
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            visualTransformation = if (state.passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            onDone = onSignIn,
                        )
                        DdIconButton(
                            icon = DdIcons.Eye,
                            onClick = { state.passwordVisible = !state.passwordVisible },
                            contentDescription = host.text(
                                if (state.passwordVisible) TEXT_HIDE_PASSWORD else TEXT_SHOW_PASSWORD,
                            ),
                            tint = if (state.passwordVisible) colors.accent else colors.inkTertiary,
                            enabled = !state.busy,
                        )
                    }
                    state.passwordError?.let { FieldError(it) }
                }
                if (state.passwordSaved) {
                    DdText(
                        text = host.text(TEXT_PASSWORD_SAVED),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                }
                DdPrimaryButton(
                    text = host.text(TEXT_SIGN_IN),
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
                if (state.busy && state.progress != null) ProgressRow(state.progress.orEmpty())
            }
        }
    }
}

@Composable
private fun CodeSection(
    host: PluginHost,
    state: SysuPageState,
    onBack: () -> Unit,
    onSubmit: () -> Unit,
    onResend: () -> Unit,
) {
    val colors = deepDeepColors()
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        DdSectionLabel(host.text(TEXT_CODE_SECTION))
        DdGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DdSpacing.Card),
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdTextAction(
                        text = host.text(TEXT_BACK),
                        onClick = onBack,
                        modifier = Modifier.offset(x = -DdSpacing.Md),
                        color = colors.inkSecondary,
                        enabled = !state.busy,
                    )
                }
                DdText(
                    text = host.text(TEXT_CODE_HINT),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
                    DdTextField(
                        value = state.code,
                        onValueChange = { value ->
                            if (!state.busy) {
                                state.code = value.filter { it.isDigit() }.take(4)
                                state.codeError = null
                            }
                        },
                        placeholder = host.text(TEXT_CODE_LABEL),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = PasswordVisualTransformation(),
                        onDone = onSubmit,
                    )
                    state.codeError?.let { FieldError(it) }
                }
                DdPrimaryButton(
                    text = host.text(TEXT_COMPLETE_LOGIN),
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
                if (state.busy && state.progress != null) ProgressRow(state.progress.orEmpty())
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.codeRemaining > 0) {
                        DdText(
                            text = host.text(TEXT_RESEND_IN, state.codeRemaining),
                            style = DdType.bodySmall,
                            color = colors.inkTertiary,
                        )
                    } else {
                        DdTextAction(
                            text = host.text(TEXT_RESEND),
                            onClick = onResend,
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignedInSection(host: PluginHost, state: SysuPageState, onSignOut: () -> Unit) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
    ) {
        DdTextAction(
            text = host.text(TEXT_SIGN_OUT),
            onClick = onSignOut,
            color = colors.danger,
            enabled = !state.busy,
        )
        if (state.busy && state.progress != null) ProgressRow(state.progress.orEmpty())
    }
}

@Composable
private fun FieldError(text: String) {
    DdText(
        text = text,
        modifier = Modifier.padding(horizontal = DdSpacing.Xs),
        style = DdType.bodySmall,
        color = deepDeepColors().danger,
    )
}

@Composable
private fun ProgressRow(text: String) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DdSpinner(size = 14.dp, strokeWidth = 1.5.dp, color = colors.inkTertiary)
        Spacer(Modifier.width(DdSpacing.Sm))
        DdText(text = text, style = DdType.bodySmall, color = colors.inkSecondary)
    }
}

private fun environmentValue(host: PluginHost, status: SysuStatus?): String = when {
    status == null -> host.text(TEXT_CHECKING)
    !status.linuxAvailable -> host.text(TEXT_ENV_UNAVAILABLE)
    status.nodeVersion == null -> host.text(TEXT_ENV_NEEDS_SETUP)
    status.nodeTooOld -> host.text(TEXT_ENV_NEEDS_UPGRADE)
    !status.installed -> host.text(TEXT_ENV_PENDING)
    status.outdated -> host.text(TEXT_ENV_PENDING_UPDATE)
    else -> host.text(TEXT_ENV_READY)
}

private fun environmentTone(status: SysuStatus?): DdStatusTone = when {
    status == null -> DdStatusTone.NEUTRAL
    !status.linuxAvailable || status.nodeVersion == null || status.nodeTooOld -> DdStatusTone.DANGER
    !status.installed || status.outdated -> DdStatusTone.WARNING
    else -> DdStatusTone.SUCCESS
}

private fun environmentHint(host: PluginHost, status: SysuStatus?): String? = when {
    status == null -> null
    !status.linuxAvailable -> host.text(TEXT_HINT_ENV_UNAVAILABLE)
    status.nodeVersion == null -> host.text(TEXT_HINT_ENV_MISSING)
    status.nodeTooOld -> host.text(TEXT_HINT_ENV_OLD)
    else -> null
}

private const val CODE_RESEND_SECONDS = 60
private const val CODE_RESEND_MILLIS = CODE_RESEND_SECONDS * 1000L

private val TEXT_ACCOUNT = literal("账号", "Account")
private val TEXT_SIGN_IN_STATE = literal("登录状态", "Sign-in")
private val TEXT_ENVIRONMENT = literal("环境", "Environment")
private val TEXT_CHECKING = literal("检查中…", "Checking…")
private val TEXT_NOT_SET = literal("未设置", "Not set")
private val TEXT_SIGNED_IN_STATE = literal("已登录", "Signed in")
private val TEXT_NOT_SIGNED_IN = literal("未登录", "Not signed in")
private val TEXT_ENV_READY = literal("已就绪", "Ready")
private val TEXT_ENV_PENDING = literal("待准备", "Not set up")
private val TEXT_ENV_PENDING_UPDATE = literal("待更新", "Update available")
private val TEXT_ENV_NEEDS_SETUP = literal("需安装", "Needs setup")
private val TEXT_ENV_NEEDS_UPGRADE = literal("需升级", "Needs upgrade")
private val TEXT_ENV_UNAVAILABLE = literal("不可用", "Unavailable")
private val TEXT_SIGN_IN_SECTION = literal("登录", "Sign in")
private val TEXT_CODE_SECTION = literal("验证码", "Verification")
private val TEXT_NET_ID = literal("NetID", "NetID")
private val TEXT_PASSWORD = literal("密码", "Password")
private val TEXT_SHOW_PASSWORD = literal("显示密码", "Show password")
private val TEXT_HIDE_PASSWORD = literal("隐藏密码", "Hide password")
private val TEXT_PASSWORD_SAVED = literal("密码已保存，留空则继续使用", "Password saved; leave blank to use it")
private val TEXT_SIGN_IN = literal("登录", "Sign in")
private val TEXT_BACK = literal("返回", "Back")
private val TEXT_CODE_LABEL = literal("4 位验证码", "4-digit code")
private val TEXT_CODE_HINT = literal(
    "验证码已发送到企业微信，10 分钟内有效。",
    "A 4-digit code was sent to WeCom and is valid for 10 minutes.",
)
private val TEXT_COMPLETE_LOGIN = literal("完成登录", "Finish signing in")
private val TEXT_RESEND = literal("重新发送验证码", "Resend code")
private val TEXT_RESEND_IN = literal("%1\$d 秒后可重发", "Resend in %1\$d s")
private val TEXT_CODE_RESENT = literal("验证码已重新发送", "A new code was sent")
private val TEXT_CANCEL = literal("取消", "Cancel")
private val TEXT_SIGN_OUT = literal("退出登录", "Sign out")
private val TEXT_RETRY = literal("重试", "Retry")
private val TEXT_RECHECK = literal("重新检查", "Check again")
private val TEXT_ERROR_NET_ID = literal("请输入 NetID", "Enter your NetID")
private val TEXT_ERROR_PASSWORD = literal("请输入密码", "Enter your password")
private val TEXT_ERROR_CODE = literal("请输入 4 位数字验证码", "Enter the 4-digit code")
private val TEXT_SIGNED_IN = literal("登录成功", "Signed in successfully")
private val TEXT_SIGNED_OUT = literal("已退出登录", "Signed out")
private val TEXT_SIGNING_OUT = literal("正在退出登录…", "Signing out…")
private val TEXT_CONFIRM_SIGN_OUT_TITLE = literal("退出中山大学登录？", "Sign out of SYSU?")
private val TEXT_CONFIRM_SIGN_OUT_BODY = literal(
    "退出后需要重新登录才能继续使用校园服务。",
    "You will need to sign in again to use campus services.",
)
private val TEXT_HINT_ENV_UNAVAILABLE = literal(
    "当前设备没有可用的运行环境，校园服务暂不可用。",
    "This device has no usable runtime, so campus services are unavailable.",
)
private val TEXT_HINT_ENV_MISSING = literal(
    "运行环境不完整，请在 设置 → 运行环境 重新安装。",
    "The runtime is incomplete. Reinstall it in Settings → Runtime.",
)
private val TEXT_HINT_ENV_OLD = literal(
    "运行环境版本过低，请在 设置 → 运行环境 升级。",
    "The runtime is too old. Upgrade it in Settings → Runtime.",
)
private val TEXT_ERROR_UNKNOWN = literal("操作失败，请重试", "Something went wrong. Try again.")
