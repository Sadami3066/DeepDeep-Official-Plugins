package io.github.whywood.deepdeep.plugin.amap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
internal fun AmapPage(host: PluginHost) {
    val colors = deepDeepColors()
    val scope = rememberCoroutineScope()
    val client = remember(host) { AmapClient(host) }
    var configured by rememberSaveable { mutableStateOf(false) }
    var connected by rememberSaveable { mutableStateOf(false) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSave by rememberSaveable { mutableStateOf(false) }

    fun errorText(throwable: Throwable): String = when (throwable) {
        is AmapException -> throwable.message.orEmpty()
        else -> host.t("网络连接失败，请检查网络后重试", "Network unavailable. Check your connection and try again.")
    }.ifBlank { host.t("连接失败，请稍后重试", "Connection failed. Try again.") }

    suspend fun connect(candidate: String?, persist: Boolean) {
        if (busy) return
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
            editorOpen = false
            pendingSave = false
            host.ui().snackbar(host.t("已连接高德地图", "Connected to Amap Maps"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            connected = false
            pendingSave = persist
            error = errorText(throwable)
        } finally {
            busy = false
        }
    }

    fun retry() {
        scope.launch {
            if (pendingSave && keyInput.isNotBlank()) {
                connect(keyInput, persist = true)
            } else {
                connect(null, persist = false)
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            client.migrateLegacyKey()
            configured = client.hasKey()
            editorOpen = !configured
            error = null
            if (configured) connect(null, persist = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            configured = false
            connected = false
            editorOpen = true
            error = errorText(throwable)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = DdSpacing.Xl,
                end = DdSpacing.Xl,
                top = DdSpacing.Md,
                bottom = DdSpacing.Xxl,
            ),
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        DdGroupCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Sm),
            ) {
                DdStatusRow(
                    label = host.t("高德 Key", "Amap key"),
                    value = if (configured) {
                        host.t("已配置", "Configured")
                    } else {
                        host.t("未配置", "Not configured")
                    },
                    tone = if (configured) DdStatusTone.SUCCESS else DdStatusTone.NEUTRAL,
                )
                DdStatusRow(
                    label = host.t("连接", "Connection"),
                    value = when {
                        busy -> host.t("验证中…", "Verifying…")
                        connected -> host.t("已连接", "Connected")
                        error != null -> host.t("连接失败", "Failed")
                        configured -> host.t("待验证", "Not verified")
                        else -> host.t("未连接", "Not connected")
                    },
                    tone = when {
                        busy -> DdStatusTone.NEUTRAL
                        connected -> DdStatusTone.SUCCESS
                        error != null -> DdStatusTone.DANGER
                        configured -> DdStatusTone.WARNING
                        else -> DdStatusTone.NEUTRAL
                    },
                    busy = busy,
                )
                if (configured && !editorOpen && !busy) {
                    DdHairline()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdTextAction(
                            text = host.t("验证连接", "Verify connection"),
                            onClick = { scope.launch { connect(null, persist = false) } },
                            modifier = Modifier.testTag("amap_verify"),
                        )
                        DdTextAction(
                            text = host.t("更换 Key", "Change key"),
                            onClick = {
                                keyInput = ""
                                error = null
                                editorOpen = true
                            },
                            color = colors.inkSecondary,
                            modifier = Modifier.testTag("amap_change_key"),
                        )
                    }
                }
            }
        }

        if (error != null && !editorOpen) {
            DdNoticeBar(
                text = error.orEmpty(),
                tone = DdNoticeTone.DANGER,
                actionLabel = host.t("重试", "Retry"),
                onAction = { retry() },
                modifier = Modifier.testTag("amap_error"),
            )
        }

        if (editorOpen) {
            DdGroupCard {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                    verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                ) {
                    DdText(
                        text = host.t("填写高德 Key", "Enter your Amap key"),
                        style = DdType.body,
                        color = colors.ink,
                    )
                    DdText(
                        text = host.t(
                            "在高德开放平台创建应用后获取 Key。",
                            "Create an app on the Amap open platform to get a key.",
                        ),
                        style = DdType.micro,
                        color = colors.inkTertiary,
                    )
                    DdTextField(
                        value = keyInput,
                        onValueChange = {
                            keyInput = it.trim()
                            error = null
                        },
                        placeholder = host.t("粘贴高德 Key", "Paste your Amap key"),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        onDone = {
                            if (keyInput.isNotBlank() && !busy) scope.launch { connect(keyInput, persist = true) }
                        },
                        testTag = "amap_key_input",
                    )
                    error?.let { message ->
                        DdText(text = message, style = DdType.bodySmall, color = colors.danger)
                    }
                    DdPrimaryButton(
                        text = if (busy) {
                            host.t("正在连接…", "Connecting…")
                        } else {
                            host.t("保存并连接", "Save and connect")
                        },
                        onClick = { scope.launch { connect(keyInput, persist = true) } },
                        enabled = keyInput.isNotBlank() && !busy,
                        modifier = Modifier.fillMaxWidth().testTag("amap_save"),
                    )
                    if (configured) {
                        DdTextAction(
                            text = host.t("取消", "Cancel"),
                            onClick = {
                                keyInput = ""
                                error = null
                                editorOpen = false
                            },
                            color = colors.inkSecondary,
                            modifier = Modifier.testTag("amap_cancel_key"),
                        )
                    }
                    DdText(
                        text = host.t(
                            "Key 只保存在本机，用于向高德请求地图数据。",
                            "The key stays on this device and is used only for Amap requests.",
                        ),
                        style = DdType.micro,
                        color = colors.inkTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(DdSpacing.Xs))
        DdText(
            text = host.t(
                "地点、路线和天气查询在对话里完成；导航和打车在高德地图里完成。",
                "Places, routes and weather are looked up in chat; navigation and rides happen in Amap Maps.",
            ),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}
