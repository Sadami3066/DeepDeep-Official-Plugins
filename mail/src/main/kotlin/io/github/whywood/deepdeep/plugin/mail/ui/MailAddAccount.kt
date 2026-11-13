package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailAccountInput
import io.github.whywood.deepdeep.plugin.mail.MailConnectionTest
import io.github.whywood.deepdeep.plugin.mail.MailProviderPreset
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import kotlinx.coroutines.launch

private enum class AddStep { PROVIDER, HELP, CREDENTIALS, RESULT }

@Composable
internal fun MailAddAccountFlow(
    host: PluginHost,
    repository: MailRepository,
    onClose: () -> Unit,
    onExit: () -> Unit,
    onAdded: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val providers = remember(repository) { repository.providers() }
    var step by rememberSaveable { mutableStateOf(AddStep.PROVIDER) }
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var imapHost by rememberSaveable { mutableStateOf("") }
    var imapPort by rememberSaveable { mutableStateOf("993") }
    var smtpHost by rememberSaveable { mutableStateOf("") }
    var smtpPort by rememberSaveable { mutableStateOf("465") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<MailConnectionTest?>(null) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val provider = providers.firstOrNull { it.id == providerId }

    LaunchedEffect(provider, step) {
        if (provider == null && step != AddStep.PROVIDER) step = AddStep.PROVIDER
    }

    LaunchedEffect(testResult, step) {
        if (testResult == null && step == AddStep.RESULT) step = AddStep.CREDENTIALS
    }

    fun selectProvider(preset: MailProviderPreset) {
        providerId = preset.id
        email = ""
        password = ""
        imapHost = preset.imapHost
        imapPort = preset.imapPort.toString()
        smtpHost = preset.smtpHost
        smtpPort = preset.smtpPort.toString()
        showAdvanced = preset.custom
        error = null
        step = AddStep.HELP
    }

    fun buildInput(): MailAccountInput? {
        val preset = provider ?: return null
        val address = email.trim()
        if (!validRecipient(address)) {
            error = host.t("请填写正确的邮箱地址。", "Enter a valid email address.")
            return null
        }
        if (password.isBlank()) {
            error = host.t("请填写授权码或应用专用密码。", "Enter the authorization code or app password.")
            return null
        }
        val imapHostValue = if (preset.custom) imapHost.trim() else preset.imapHost
        val smtpHostValue = if (preset.custom) smtpHost.trim() else preset.smtpHost
        val imapPortValue = if (preset.custom) imapPort.trim().toIntOrNull() else preset.imapPort
        val smtpPortValue = if (preset.custom) smtpPort.trim().toIntOrNull() else preset.smtpPort
        if (preset.custom) {
            if (imapHostValue.isBlank() || smtpHostValue.isBlank()) {
                error = host.t("请填写收件和发件服务器。", "Enter both the incoming and outgoing servers.")
                return null
            }
            if (imapPortValue == null || imapPortValue !in 1..65535 || smtpPortValue == null || smtpPortValue !in 1..65535) {
                error = host.t("端口需为 1-65535 之间的数字。", "Ports must be numbers between 1 and 65535.")
                return null
            }
        }
        error = null
        return MailAccountInput(
            providerId = preset.id,
            email = address,
            password = password,
            imapHost = imapHostValue.ifBlank { null },
            imapPort = imapPortValue,
            smtpHost = smtpHostValue.ifBlank { null },
            smtpPort = smtpPortValue,
        )
    }

    fun test() {
        if (testing) return
        val input = buildInput() ?: return
        testing = true
        scope.launch {
            val result = runCatching { repository.testConnection(input) }.fold(
                onSuccess = { it },
                onFailure = {
                    host.logs().warn("mail connection test failed", it)
                    MailConnectionTest(
                        imapOk = false,
                        smtpOk = false,
                        imapError = host.readableError(
                            it,
                            fallbackZh = "连接失败，请重试",
                            fallbackEn = "Couldn't connect. Try again.",
                        ),
                    )
                },
            )
            testing = false
            testResult = result
            step = AddStep.RESULT
        }
    }

    fun add() {
        if (adding) return
        val input = buildInput() ?: return
        adding = true
        error = null
        scope.launch {
            val result = runCatching { repository.addAccount(input) }.fold(
                onSuccess = { it },
                onFailure = { null },
            )
            adding = false
            if (result == null) {
                error = host.t(
                    "添加失败，请检查网络和授权码后重试。",
                    "Couldn't add the mailbox. Check your connection and password, then try again.",
                )
                return@launch
            }
            if (result.ok) {
                host.ui().toast(host.t("邮箱已添加", "Mailbox added"))
                onAdded()
            } else {
                testResult = result
                error = host.t(
                    "添加失败，请检查授权码后重试。",
                    "Couldn't add the mailbox. Check the password and try again.",
                )
            }
        }
    }

    fun back() {
        when (step) {
            AddStep.PROVIDER -> onExit()
            AddStep.HELP -> step = AddStep.PROVIDER
            AddStep.CREDENTIALS -> step = AddStep.HELP
            AddStep.RESULT -> step = AddStep.CREDENTIALS
        }
    }

    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        MailSheetHeader(
            host = host,
            title = host.t("添加邮箱", "Add mailbox"),
            onClose = onClose,
            onBack = { back() },
        )
        when (step) {
            AddStep.PROVIDER -> ProviderStep(
                host = host,
                providers = providers,
                onSelect = { preset -> selectProvider(preset) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            AddStep.HELP -> if (provider != null) {
                HelpStep(
                    host = host,
                    preset = provider,
                    onContinue = { step = AddStep.CREDENTIALS },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            AddStep.CREDENTIALS -> if (provider != null) {
                CredentialsStep(
                    host = host,
                    preset = provider,
                    email = email,
                    password = password,
                    showPassword = showPassword,
                    imapHost = imapHost,
                    imapPort = imapPort,
                    smtpHost = smtpHost,
                    smtpPort = smtpPort,
                    showAdvanced = showAdvanced,
                    error = error,
                    testing = testing,
                    onEmailChange = {
                        email = it
                        error = null
                    },
                    onPasswordChange = {
                        password = it
                        error = null
                    },
                    onTogglePasswordVisible = { showPassword = !showPassword },
                    onImapHostChange = { imapHost = it },
                    onImapPortChange = { imapPort = it },
                    onSmtpHostChange = { smtpHost = it },
                    onSmtpPortChange = { smtpPort = it },
                    onToggleAdvanced = { showAdvanced = !showAdvanced },
                    onTest = { test() },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            AddStep.RESULT -> {
                val result = testResult
                if (result != null) {
                    ResultStep(
                        host = host,
                        result = result,
                        error = error,
                        adding = adding,
                        testing = testing,
                        onRetry = { test() },
                        onModify = { step = AddStep.CREDENTIALS },
                        onStart = { add() },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}
