package io.github.whywood.deepdeep.plugin.mail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIconButton
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdRadius
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailConnectionTest
import io.github.whywood.deepdeep.plugin.mail.MailProviderPreset

@Composable
internal fun ProviderStep(
    host: PluginHost,
    providers: List<MailProviderPreset>,
    onSelect: (MailProviderPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val regular = providers.filter { !it.custom }
    val custom = providers.firstOrNull { it.custom }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = host.t("选择你的邮箱服务商。", "Choose your mail provider."),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
        Spacer(Modifier.height(14.dp))
        regular.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { preset ->
                    ProviderTile(
                        host = host,
                        preset = preset,
                        onClick = { onSelect(preset) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        if (custom != null) {
            ProviderTile(
                host = host,
                preset = custom,
                onClick = { onSelect(custom) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = host.t(
                "暂不支持 Outlook / Microsoft 365",
                "Outlook and Microsoft 365 aren't supported yet",
            ),
            style = DdType.bodySmall,
            color = colors.inkTertiary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProviderTile(
    host: PluginHost,
    preset: MailProviderPreset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    val name = host.providerName(preset)
    DdPressable(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(DdRadius.Medium),
        background = colors.wash,
        contentDescription = name,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 14.dp),
            style = DdType.label,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HelpStep(
    host: PluginHost,
    preset: MailProviderPreset,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Text(
            text = host.providerName(preset),
            style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.ink,
        )
        Spacer(Modifier.height(4.dp))
        preset.stepsZh.indices.forEach { index ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    text = "${index + 1}",
                    modifier = Modifier.width(22.dp),
                    style = DdType.numeric,
                    color = colors.accent,
                )
                Text(
                    text = host.providerStep(preset, index),
                    modifier = Modifier.weight(1f),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                )
            }
        }
        host.providerNote(preset)?.let { note ->
            Spacer(Modifier.height(4.dp))
            Text(text = note, style = DdType.bodySmall, color = colors.warning)
        }
        Spacer(Modifier.height(16.dp))
        if (preset.helpUrl.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DdTextAction(
                    text = host.t("打开设置页面", "Open settings page"),
                    onClick = { host.ui().openUrl(preset.helpUrl) },
                    color = colors.accent,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        DdPrimaryButton(
            text = host.t("继续", "Continue"),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun CredentialsStep(
    host: PluginHost,
    preset: MailProviderPreset,
    email: String,
    password: String,
    showPassword: Boolean,
    imapHost: String,
    imapPort: String,
    smtpHost: String,
    smtpPort: String,
    showAdvanced: Boolean,
    error: String?,
    testing: Boolean,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onImapHostChange: (String) -> Unit,
    onImapPortChange: (String) -> Unit,
    onSmtpHostChange: (String) -> Unit,
    onSmtpPortChange: (String) -> Unit,
    onToggleAdvanced: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        DdTextField(
            value = email,
            onValueChange = onEmailChange,
            placeholder = host.t("邮箱地址", "Email address"),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdTextField(
                value = password,
                onValueChange = onPasswordChange,
                placeholder = host.t("授权码或应用专用密码", "Authorization code or app password"),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                visualTransformation = if (showPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            )
            DdIconButton(
                icon = DdIcons.Eye,
                onClick = onTogglePasswordVisible,
                contentDescription = if (showPassword) {
                    host.t("隐藏密码", "Hide password")
                } else {
                    host.t("显示密码", "Show password")
                },
                tint = if (showPassword) colors.accent else colors.inkTertiary,
                iconSize = 19.dp,
            )
        }
        if (preset.custom) {
            Spacer(Modifier.height(4.dp))
            DdTextAction(
                text = if (showAdvanced) {
                    host.t("收起服务器设置", "Hide server settings")
                } else {
                    host.t("服务器设置", "Server settings")
                },
                onClick = onToggleAdvanced,
                color = colors.inkSecondary,
            )
            if (showAdvanced) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DdTextField(
                        value = imapHost,
                        onValueChange = onImapHostChange,
                        placeholder = host.t("收件服务器", "Incoming server"),
                        modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    DdTextField(
                        value = imapPort,
                        onValueChange = onImapPortChange,
                        placeholder = host.t("端口", "Port"),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DdTextField(
                        value = smtpHost,
                        onValueChange = onSmtpHostChange,
                        placeholder = host.t("发件服务器", "Outgoing server"),
                        modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    DdTextField(
                        value = smtpPort,
                        onValueChange = onSmtpPortChange,
                        placeholder = host.t("端口", "Port"),
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                    )
                }
            }
        }
        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(text = message, style = DdType.bodySmall, color = colors.danger)
        }
        Spacer(Modifier.height(16.dp))
        DdPrimaryButton(
            text = host.t("测试连接", "Test connection"),
            onClick = onTest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing,
        )
        if (testing) {
            Spacer(Modifier.height(16.dp))
            DdLoadingBlock(text = host.t("正在测试连接", "Testing connection"))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun ResultStep(
    host: PluginHost,
    result: MailConnectionTest,
    error: String?,
    adding: Boolean,
    testing: Boolean,
    onRetry: () -> Unit,
    onModify: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = deepDeepColors()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(
                icon = if (result.ok) DdIcons.Check else DdIcons.Info,
                tint = if (result.ok) colors.success else colors.danger,
                size = 20.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (result.ok) {
                    host.t("连接成功", "Connected")
                } else {
                    host.t("有服务器未连接", "Some servers didn't connect")
                },
                style = DdType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
            )
        }
        Spacer(Modifier.height(12.dp))
        ResultRow(
            host = host,
            title = host.t("收件服务器", "Incoming server"),
            ok = result.imapOk,
            error = result.imapError,
        )
        ResultRow(
            host = host,
            title = host.t("发件服务器", "Outgoing server"),
            ok = result.smtpOk,
            error = result.smtpError,
        )
        error?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(text = message, style = DdType.bodySmall, color = colors.danger)
        }
        Spacer(Modifier.height(20.dp))
        if (result.ok) {
            DdPrimaryButton(
                text = host.t("开始使用", "Start using it"),
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = !adding,
            )
            if (adding) {
                Spacer(Modifier.height(16.dp))
                DdLoadingBlock(text = host.t("正在添加邮箱", "Adding mailbox"))
            }
        } else {
            DdPrimaryButton(
                text = host.t("重试", "Try again"),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
                enabled = !testing,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                DdTextAction(
                    text = host.t("返回修改", "Edit details"),
                    onClick = onModify,
                    color = colors.inkSecondary,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ResultRow(
    host: PluginHost,
    title: String,
    ok: Boolean,
    error: String?,
) {
    val colors = deepDeepColors()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
            if (error != null) {
                Text(
                    text = displayPreview(error, limit = 160),
                    style = DdType.bodySmall,
                    color = colors.danger,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DdIcon(
                icon = if (ok) DdIcons.Check else DdIcons.Close,
                tint = if (ok) colors.success else colors.danger,
                size = 14.dp,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (ok) {
                    host.t("已连接", "Connected")
                } else {
                    host.t("连接失败", "Failed")
                },
                style = DdType.chip,
                color = if (ok) colors.success else colors.danger,
            )
        }
    }
}
