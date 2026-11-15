package io.github.whywood.deepdeep.plugin.ride

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdHairline
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdTextField
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RidePage(host: PluginHost) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val client = remember(host) { DidiClient(host) }
    var configured by rememberSaveable { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var environment by rememberSaveable { mutableStateOf(RideEnvironment.PRODUCTION) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var verifyFailed by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            client.migrateLegacyKey()
            configured = client.hasKey()
            environment = client.environment()
            editorOpen = !configured
            error = null
            verifyFailed = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            configured = false
            editorOpen = true
            verifyFailed = false
            error = summarizeDidiError(throwable.message)
                .ifBlank { host.t("无法读取已保存的配置，请重新填写", "Could not read the saved settings. Please re-enter.") }
        }
    }

    fun connect(candidate: String?, persist: Boolean) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                client.probe(candidate)
                if (persist) {
                    client.saveKey(candidate.orEmpty())
                    keyInput = ""
                }
                configured = true
                connected = true
                verifyFailed = false
                editorOpen = false
                error = null
                host.ui().snackbar(host.t("已连接滴滴账号", "Didi account connected"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                connected = false
                verifyFailed = configured
                error = summarizeDidiError(throwable.message)
                    .ifBlank { host.t("连接失败，请稍后重试", "Connection failed. Try again.") }
            } finally {
                busy = false
            }
        }
    }

    fun selectEnvironment(next: RideEnvironment) {
        if (environment == next) return
        scope.launch {
            environment = next
            client.saveEnvironment(next)
            connected = false
            verifyFailed = false
            error = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DdSpacing.ScreenHorizontal, vertical = DdSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
            DdText(host.t("滴滴出行", "Didi"), style = DdType.heading, color = colors.ink)
            DdText(
                host.t(
                    "连接后可以在对话里搜索地点、预估价格、创建订单、查看司机位置；支付在滴滴内完成。",
                    "After connecting, search places, estimate fares, create orders and check driver locations in chat; payment happens in Didi.",
                ),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }

        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
            ) {
                DdStatusRow(
                    label = host.t("账号", "Account"),
                    value = if (configured) host.t("已配置", "Configured") else host.t("未配置", "Not configured"),
                    tone = if (configured) DdStatusTone.SUCCESS else DdStatusTone.NEUTRAL,
                )
                DdHairline()
                DdStatusRow(
                    label = host.t("连接", "Connection"),
                    value = when {
                        connected -> host.t("已连接", "Connected")
                        verifyFailed -> host.t("验证失败", "Verification failed")
                        configured -> host.t("待验证", "Needs verification")
                        else -> host.t("未连接", "Not connected")
                    },
                    tone = when {
                        connected -> DdStatusTone.SUCCESS
                        verifyFailed -> DdStatusTone.DANGER
                        configured -> DdStatusTone.WARNING
                        else -> DdStatusTone.NEUTRAL
                    },
                    busy = busy,
                )
                if (configured && !editorOpen) {
                    DdHairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdTextAction(
                            text = host.t("验证连接", "Verify connection"),
                            onClick = { connect(null, persist = false) },
                            enabled = !busy,
                            modifier = Modifier.testTag("ride_verify"),
                        )
                        DdTextAction(
                            text = host.t("更换密钥", "Change key"),
                            onClick = {
                                keyInput = ""
                                error = null
                                editorOpen = true
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("ride_change_key"),
                            color = colors.inkSecondary,
                        )
                    }
                }
            }
        }

        if (error != null && !editorOpen) {
            DdNoticeBar(
                text = error.orEmpty(),
                tone = DdNoticeTone.DANGER,
                actionLabel = if (configured) host.t("重试", "Retry") else null,
                onAction = if (configured) {
                    { connect(null, persist = false) }
                } else {
                    null
                },
                modifier = Modifier.testTag("ride_error"),
            )
        }

        if (editorOpen) {
            DdGroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    DdText(host.t("填写滴滴 Key", "Enter Didi key"), style = DdType.body, color = colors.ink)
                    DdTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it.trim()
                            error = null
                        },
                        placeholder = host.t("粘贴滴滴 Key", "Paste Didi key"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        onDone = { if (keyInput.isNotBlank() && !busy) connect(keyInput, persist = true) },
                        testTag = "ride_key_input",
                    )
                    error?.let {
                        DdText(it, style = DdType.bodySmall, color = colors.danger)
                    }
                    DdPrimaryButton(
                        text = if (busy) host.t("正在连接…", "Connecting…") else host.t("保存并连接", "Save and connect"),
                        onClick = { connect(keyInput, persist = true) },
                        enabled = keyInput.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("ride_save"),
                    )
                    if (configured) {
                        DdTextAction(
                            text = host.t("取消", "Cancel"),
                            onClick = {
                                keyInput = ""
                                error = null
                                editorOpen = false
                            },
                            enabled = !busy,
                            modifier = Modifier.testTag("ride_cancel_key"),
                            color = colors.inkSecondary,
                        )
                    }
                    DdText(
                        host.t("Key 只保存在本机，不会发送给其他服务。", "The key stays on this device."),
                        style = DdType.micro,
                        color = colors.inkTertiary,
                    )
                }
            }
        }

        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    DdTextAction(
                        text = host.t("高级设置", "Advanced"),
                        onClick = { advancedOpen = !advancedOpen },
                        enabled = !busy,
                        modifier = Modifier.testTag("ride_advanced"),
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    DdText(
                        if (environment == RideEnvironment.SANDBOX) {
                            host.t("体验模式", "Trial mode")
                        } else {
                            host.t("正式环境", "Production")
                        },
                        style = DdType.micro,
                        color = colors.inkTertiary,
                    )
                }
                if (advancedOpen) {
                    DdHairline()
                    DdStatusRow(
                        label = host.t("环境", "Environment"),
                        value = if (environment == RideEnvironment.SANDBOX) {
                            host.t("体验模式", "Trial mode")
                        } else {
                            host.t("正式环境", "Production")
                        },
                        tone = if (environment == RideEnvironment.SANDBOX) {
                            DdStatusTone.WARNING
                        } else {
                            DdStatusTone.NEUTRAL
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
                    ) {
                        DdTextAction(
                            text = host.t("正式环境", "Production"),
                            onClick = { selectEnvironment(RideEnvironment.PRODUCTION) },
                            enabled = !busy,
                            color = if (environment == RideEnvironment.PRODUCTION) colors.accent else colors.inkTertiary,
                            modifier = Modifier.testTag("ride_env_production"),
                        )
                        DdTextAction(
                            text = host.t("体验模式", "Trial mode"),
                            onClick = { selectEnvironment(RideEnvironment.SANDBOX) },
                            enabled = !busy,
                            color = if (environment == RideEnvironment.SANDBOX) colors.accent else colors.inkTertiary,
                            modifier = Modifier.testTag("ride_env_sandbox"),
                        )
                    }
                    DdText(
                        host.t(
                            "体验模式使用模拟数据，不会产生真实订单。",
                            "Trial mode uses simulated data and never creates real rides.",
                        ),
                        style = DdType.micro,
                        color = colors.inkTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(DdSpacing.Xs))
        DdText(
            host.t(
                "对话里可以完成查询和下单；支付始终在滴滴内完成，插件不会自动支付。",
                "Search and order creation happen in chat; payment always happens in Didi, and the plugin never pays automatically.",
            ),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}
