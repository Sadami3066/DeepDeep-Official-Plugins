package io.github.whywood.deepdeep.plugin.wecom

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdImage
import io.github.whywood.deepdeep.design.DdMotion
import io.github.whywood.deepdeep.design.DdNoticeBar
import io.github.whywood.deepdeep.design.DdNoticeTone
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdProgressBar
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdStatusRow
import io.github.whywood.deepdeep.design.DdStatusTone
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.LocalDdMotionEnabled
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.LinuxChunkKind
import io.github.whywood.deepdeep.plugin.api.LocalizedText
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.notificationAction
import io.github.whywood.deepdeep.plugin.api.pluginNotificationWithActions
import io.github.whywood.deepdeep.plugin.api.uiConfirmRequest
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class WecomOperation {
    NONE,
    INSTALL,
    CONNECT,
    LOGOUT,
}

internal data class WecomPageState(
    val checked: Boolean = false,
    val checking: Boolean = false,
    val operation: WecomOperation = WecomOperation.NONE,
    val cancelling: Boolean = false,
    val canCancel: Boolean = true,
    val linuxAvailable: Boolean = true,
    val cliVersion: String? = null,
    val authorized: Boolean = false,
    val botId: String? = null,
    val stage: LocalizedText? = null,
    val progress: Float? = null,
    val percent: Int? = null,
    val qrPath: String? = null,
    val qrUrl: String? = null,
    val error: String? = null,
    val failedOperation: WecomOperation? = null,
) {
    val installed: Boolean get() = cliVersion != null

    val busy: Boolean get() = checking || operation != WecomOperation.NONE
}

internal class WecomPageController(
    private val host: PluginHost,
    private val plugin: WecomPlugin,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(WecomPageState())
        private set

    private val store = WecomStatusStore(host)

    private var operationJob: Job? = null

    suspend fun refresh() {
        if (state.checking) return
        state = state.copy(checking = true, error = null, failedOperation = null)
        try {
            if (!host.linux().available) {
                state = state.copy(
                    checking = false,
                    checked = true,
                    linuxAvailable = false,
                )
                return
            }
            val versionResult = host.linux().exec(WecomCli.versionCommand(), timeoutMs = VERSION_TIMEOUT_MS)
            val version = versionResult.takeIf { it.exitCode == 0 }?.stdout?.let(WecomCli::parseVersion)
            if (version == null) {
                store.write(WecomStatus())
                plugin.updateStatus(WecomStatus())
                state = state.copy(
                    checking = false,
                    checked = true,
                    linuxAvailable = true,
                    cliVersion = null,
                    authorized = false,
                    botId = null,
                )
                return
            }
            val statusResult = host.linux().exec(WecomCli.authStatusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
            val authorized = WecomCli.parseAuthStatus(statusResult.stdout)
                ?: throw IllegalStateException(
                    host.text(WecomTexts.statusError, statusResult.stdout.takeLast(200).trim()),
                )
            val botId = if (authorized) {
                val showResult = host.linux().exec(WecomCli.authShowCommand(), timeoutMs = STATUS_TIMEOUT_MS)
                WecomCli.parseBotId(showResult.stdout)
            } else {
                null
            }
            val status = WecomStatus(version, authorized, botId, System.currentTimeMillis())
            store.write(status)
            plugin.updateStatus(status)
            state = state.copy(
                checking = false,
                checked = true,
                linuxAvailable = true,
                cliVersion = version,
                authorized = authorized,
                botId = botId,
            )
        } catch (error: CancellationException) {
            state = state.copy(checking = false)
            throw error
        } catch (error: Exception) {
            state = state.copy(
                checking = false,
                error = error.message ?: error.javaClass.simpleName,
                failedOperation = null,
            )
        }
    }

    fun startInstall() {
        startOperation(WecomOperation.INSTALL) { installCli() }
    }

    fun startConnect() {
        startOperation(WecomOperation.CONNECT) { connect() }
    }

    fun startLogout() {
        startOperation(WecomOperation.LOGOUT) { logout() }
    }

    fun retry() {
        when (state.failedOperation) {
            WecomOperation.INSTALL -> startInstall()
            WecomOperation.CONNECT -> startConnect()
            WecomOperation.LOGOUT -> startLogout()
            WecomOperation.NONE, null -> retryCheck()
        }
    }

    fun retryCheck() {
        if (state.checking || state.operation != WecomOperation.NONE) return
        scope.launch { refresh() }
    }

    fun cancelOperation() {
        if (state.operation == WecomOperation.NONE || state.cancelling || !state.canCancel) return
        val job = operationJob ?: return
        state = state.copy(cancelling = true, stage = WecomTexts.stageCancelling)
        job.cancel()
    }

    private fun startOperation(operation: WecomOperation, block: suspend () -> Unit) {
        if (state.operation != WecomOperation.NONE || state.checking) return
        state = state.copy(
            operation = operation,
            cancelling = false,
            canCancel = true,
            stage = null,
            progress = 0f,
            percent = null,
            qrPath = null,
            qrUrl = null,
            error = null,
            failedOperation = null,
        )
        val job = scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    error = error.message ?: error.javaClass.simpleName,
                    failedOperation = operation,
                )
            }
        }
        operationJob = job
        job.invokeOnCompletion {
            if (operationJob === job) operationJob = null
            state = state.copy(
                operation = WecomOperation.NONE,
                cancelling = false,
                canCancel = true,
                stage = null,
                progress = null,
                percent = null,
                qrPath = null,
                qrUrl = null,
            )
        }
    }

    private suspend fun installCli() {
        val staging = File(host.dataDir, STAGING_DIR)
        val archive = File(staging, WecomCli.ARCHIVE_NAME)
        var hadBinary: Boolean? = null
        var installed = false
        try {
            hadBinary = hasExistingBinary()
            staging.deleteRecursively()
            staging.mkdirs()
            state = state.copy(stage = WecomTexts.stageDownloading, progress = 0.02f, percent = 0)
            downloadArchive(archive)
            state = state.copy(stage = WecomTexts.stageVerifying, progress = 0.85f, percent = null)
            val extract = host.linux().exec(
                WecomCli.extractCommand(),
                cwd = staging.absolutePath,
                timeoutMs = EXTRACT_TIMEOUT_MS,
            )
            val stagedVersion = WecomCli.parseVersion(extract.stdout)
            if (extract.exitCode != 0 || stagedVersion != WecomCli.VERSION) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorVersion, extract.stdout.takeLast(400).trim()),
                )
            }
            state = state.copy(stage = WecomTexts.stageInstalling, progress = 0.92f)
            val install = host.linux().exec(
                WecomCli.installCommand(),
                cwd = staging.absolutePath,
                timeoutMs = INSTALL_TIMEOUT_MS,
            )
            val installedVersion = WecomCli.parseVersion(install.stdout)
            if (install.exitCode != 0 || installedVersion != WecomCli.VERSION) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorInstall, install.stdout.takeLast(400).trim()),
                )
            }
            state = state.copy(
                stage = WecomTexts.stageFinishingInstall,
                progress = 0.98f,
                canCancel = false,
            )
            installed = true
            refresh()
            host.requestContributionsRefresh()
            notifyInstalled()
            if (state.error == null) {
                host.ui().snackbar(host.text(WecomTexts.installDone))
            }
        } catch (error: Exception) {
            val previous = hadBinary
            if (!installed && previous != null) restore(previous)
            throw error
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun connect() {
        val workspace = host.files().workspaceDir
            ?: throw IllegalStateException(host.text(WecomTexts.errorWorkspace))
        val qrFile = WecomCli.qrFile(workspace)
        qrFile.delete()
        val pageUrl = AtomicReference<String?>(null)
        val outputTail = StringBuilder()
        val startedAt = System.currentTimeMillis()
        var timedOut = false
        var exitCode: Int? = null
        state = state.copy(stage = WecomTexts.stagePreparing, progress = 0.05f)
        try {
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        if (state.cancelling) break
                        val complete = WecomCli.isCompleteQrCode(qrFile)
                        val elapsed = (System.currentTimeMillis() - startedAt).toFloat() / INIT_TIMEOUT_MS
                        state = state.copy(
                            qrPath = if (complete) qrFile.absolutePath else state.qrPath,
                            qrUrl = pageUrl.get() ?: state.qrUrl,
                            stage = if (complete) WecomTexts.stageWaiting else state.stage,
                            progress = maxOf(state.progress ?: 0f, 0.05f + 0.8f * elapsed.coerceIn(0f, 1f)),
                        )
                        delay(QR_POLL_INTERVAL_MS)
                    }
                }
                try {
                    val completed = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        host.linux().stream(WecomCli.authInitCommand(qrFile)).collect { chunk ->
                            when (chunk.kind) {
                                LinuxChunkKind.STDOUT -> {
                                    appendTail(outputTail, chunk.text.orEmpty())
                                    publishQrUrl(pageUrl, chunk.text.orEmpty(), qrFile)
                                }
                                LinuxChunkKind.STDERR -> {
                                    appendTail(outputTail, chunk.text.orEmpty())
                                    publishQrUrl(pageUrl, chunk.text.orEmpty(), qrFile)
                                }
                                LinuxChunkKind.EXIT -> exitCode = chunk.exitCode
                                else -> Unit
                            }
                        }
                    }
                    timedOut = completed == null
                } finally {
                    poller.cancel()
                }
            }
            if (timedOut) {
                throw IllegalStateException(host.text(WecomTexts.errorTimeout))
            }
            if (exitCode != 0) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorAuth, failureDetail(outputTail, exitCode ?: -1)),
                )
            }
            state = state.copy(stage = WecomTexts.stageFinishing, progress = 0.95f, canCancel = false)
            refresh()
            if (state.error != null) {
                host.requestContributionsRefresh()
                return
            }
            if (!state.authorized) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorAuth, host.text(WecomTexts.notConnected)),
                )
            }
            state = state.copy(progress = 1f)
            host.requestContributionsRefresh()
            host.ui().snackbar(host.text(WecomTexts.snackbarConnected))
        } finally {
            qrFile.delete()
        }
    }

    private suspend fun logout() {
        state = state.copy(stage = WecomTexts.stageSigningOut, progress = 0.2f)
        val result = host.linux().exec(WecomCli.logoutCommand(), timeoutMs = LOGOUT_TIMEOUT_MS)
        state = state.copy(progress = 0.8f, canCancel = false)
        if (result.exitCode != 0) {
            throw IllegalStateException(
                host.text(WecomTexts.errorLogout, result.stdout.takeLast(300).trim()),
            )
        }
        state = state.copy(authorized = false, botId = null, progress = 0.95f)
        refresh()
        host.requestContributionsRefresh()
        host.ui().snackbar(host.text(WecomTexts.snackbarLoggedOut))
    }

    private fun notifyInstalled() {
        runCatching {
            host.notifications().notifyChecked(
                pluginNotificationWithActions(
                    id = WecomTexts.NOTIFY_CLI_ID,
                    title = host.text(WecomTexts.notifyTitle),
                    body = host.text(WecomTexts.notifyBody),
                    actions = listOf(
                        notificationAction(WecomTexts.ACTION_OPEN_PAGE, host.text(WecomTexts.actionOpenPage)),
                    ),
                ),
            )
        }
    }

    private suspend fun downloadArchive(archive: File) {
        var lastError: Exception? = null
        for (url in WecomCli.DOWNLOAD_URLS) {
            try {
                host.network().download(url, archive.absolutePath, WecomCli.ARCHIVE_SHA256) { percent ->
                    if (percent != null && state.operation == WecomOperation.INSTALL && !state.cancelling) {
                        val clamped = percent.coerceIn(0, 100)
                        state = state.copy(
                            progress = downloadProgress(clamped),
                            percent = clamped,
                        )
                    }
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                archive.delete()
            }
        }
        throw IllegalStateException(
            host.text(WecomTexts.errorDownload, lastError?.message?.takeLast(300).orEmpty()),
        )
    }

    private fun downloadProgress(percent: Int): Float =
        0.05f + 0.75f * percent.coerceIn(0, 100) / 100f

    private suspend fun hasExistingBinary(): Boolean = try {
        host.linux().exec(
            "if [ -x ${WecomCli.INSTALL_PATH} ]; then echo yes; else echo no; fi",
            timeoutMs = VERSION_TIMEOUT_MS,
        ).stdout.contains("yes")
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private suspend fun restore(hadBinary: Boolean) {
        withContext(NonCancellable) {
            runCatching {
                host.linux().exec(WecomCli.restoreCommand(hadBinary), timeoutMs = CLEANUP_TIMEOUT_MS)
            }
        }
    }

    private fun publishQrUrl(pageUrl: AtomicReference<String?>, output: String, qrFile: File) {
        val url = WecomCli.parseQrPageUrl(output) ?: return
        if (pageUrl.compareAndSet(null, url)) {
            state = state.copy(qrPath = qrFile.takeIf(WecomCli::isCompleteQrCode)?.absolutePath, qrUrl = url)
        }
    }

    private fun appendTail(tail: StringBuilder, text: String) {
        tail.append(text)
        if (tail.length > ERROR_TAIL) tail.delete(0, tail.length - ERROR_TAIL)
    }

    private fun failureDetail(tail: StringBuilder, exitCode: Int): String =
        tail.toString().trim().ifEmpty { host.text(WecomTexts.errorExitCode, exitCode) }

    private companion object {
        const val STAGING_DIR = "cli-staging"
        const val QR_POLL_INTERVAL_MS = 400L
        const val ERROR_TAIL = 800
        const val EXTRACT_TIMEOUT_MS = 120_000L
        const val INSTALL_TIMEOUT_MS = 120_000L
        const val VERSION_TIMEOUT_MS = 20_000L
        const val STATUS_TIMEOUT_MS = 30_000L
        const val INIT_TIMEOUT_MS = 6 * 60_000L
        const val LOGOUT_TIMEOUT_MS = 60_000L
        const val CLEANUP_TIMEOUT_MS = 30_000L
    }
}

@Composable
internal fun WecomPage(plugin: WecomPlugin, host: PluginHost) {
    val scope = rememberCoroutineScope()
    val controller = remember(host, plugin, scope) { WecomPageController(host, plugin, scope) }
    val scrollState = rememberScrollState()
    val page = controller.state
    val motionEnabled = LocalDdMotionEnabled.current
    val fadeSpec = motionFadeSpec(motionEnabled)
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(page.operation) {
        if (page.operation != WecomOperation.NONE) {
            if (motionEnabled) {
                scrollState.animateScrollTo(
                    0,
                    tween(durationMillis = DdMotion.BASE, easing = DdMotion.EaseOut),
                )
            } else {
                scrollState.scrollTo(0)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = DdSpacing.Xl,
                end = DdSpacing.Xl,
                top = DdSpacing.Md,
                bottom = DdSpacing.Xxl,
            ),
    ) {
        Crossfade(
            targetState = page.operation,
            animationSpec = fadeSpec,
            label = "wecom-operation",
        ) { operation ->
            Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg)) {
                when (operation) {
                    WecomOperation.CONNECT -> {
                        ScanSection(host, page, controller)
                        StatusSummary(host, page)
                    }
                    WecomOperation.INSTALL, WecomOperation.LOGOUT -> {
                        OperationSection(host, page, controller)
                        StatusSummary(host, page)
                    }
                    WecomOperation.NONE -> {
                        StatusSummary(host, page)
                        page.error?.let { message ->
                            DdNoticeBar(
                                text = message,
                                tone = DdNoticeTone.DANGER,
                                actionLabel = host.text(WecomTexts.retry),
                                onAction = { controller.retry() },
                            )
                        }
                        if (page.checked && !page.linuxAvailable) {
                            DdNoticeBar(
                                text = host.text(WecomTexts.unavailableLinux),
                                tone = DdNoticeTone.WARNING,
                                actionLabel = host.text(WecomTexts.recheck),
                                onAction = { controller.retryCheck() },
                            )
                        }
                        if (page.checked && page.linuxAvailable) {
                            ActionSection(host, page, controller, scope)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(host: PluginHost, page: WecomPageState) {
    val unknownValue = if (page.checking) {
        host.text(WecomTexts.checking)
    } else {
        host.text(WecomTexts.checkFailed)
    }
    val unknownTone = if (page.checking) DdStatusTone.NEUTRAL else DdStatusTone.WARNING
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Xs),
        ) {
            DdStatusRow(
                label = host.text(WecomTexts.runtime),
                value = when {
                    !page.checked -> unknownValue
                    page.linuxAvailable -> host.text(WecomTexts.ready)
                    else -> host.text(WecomTexts.unavailable)
                },
                tone = when {
                    !page.checked -> unknownTone
                    page.linuxAvailable -> DdStatusTone.SUCCESS
                    else -> DdStatusTone.DANGER
                },
                busy = page.checking && !page.checked,
            )
            DdStatusRow(
                label = host.text(WecomTexts.component),
                value = when {
                    page.checked && !page.linuxAvailable -> host.text(WecomTexts.unavailable)
                    page.cliVersion != null -> page.cliVersion
                    page.checked -> host.text(WecomTexts.notInstalled)
                    else -> unknownValue
                },
                tone = when {
                    page.checked && !page.linuxAvailable -> DdStatusTone.NEUTRAL
                    page.cliVersion != null -> DdStatusTone.SUCCESS
                    page.checked -> DdStatusTone.NEUTRAL
                    else -> unknownTone
                },
                busy = page.checking,
            )
            DdStatusRow(
                label = host.text(WecomTexts.authorization),
                value = when {
                    page.checked && !page.linuxAvailable -> host.text(WecomTexts.unavailable)
                    !page.checked -> unknownValue
                    page.authorized -> host.text(WecomTexts.connected)
                    else -> host.text(WecomTexts.notConnected)
                },
                tone = when {
                    page.checked && !page.linuxAvailable -> DdStatusTone.NEUTRAL
                    !page.checked -> unknownTone
                    page.authorized -> DdStatusTone.SUCCESS
                    else -> DdStatusTone.NEUTRAL
                },
                busy = page.checking,
            )
            if (page.botId != null && page.linuxAvailable) {
                DdStatusRow(
                    label = host.text(WecomTexts.botId),
                    value = page.botId,
                    tone = DdStatusTone.NEUTRAL,
                    busy = page.checking,
                )
            }
        }
    }
}

@Composable
private fun ActionSection(
    host: PluginHost,
    page: WecomPageState,
    controller: WecomPageController,
    scope: CoroutineScope,
) {
    val colors = deepDeepColors()
    val enabled = !page.busy
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            when {
                !page.installed -> {
                    DdPrimaryButton(
                        text = host.text(WecomTexts.install),
                        onClick = { controller.startInstall() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    DdText(
                        text = host.text(WecomTexts.installHint),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                !page.authorized -> {
                    DdPrimaryButton(
                        text = host.text(WecomTexts.connect),
                        onClick = { controller.startConnect() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        DdTextAction(
                            text = host.text(WecomTexts.update),
                            onClick = { controller.startInstall() },
                            enabled = enabled,
                        )
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdTextAction(
                            text = host.text(WecomTexts.reauthorize),
                            onClick = {
                                scope.launch {
                                    val confirmed = host.ui().confirm(
                                        uiConfirmRequest(
                                            title = host.text(WecomTexts.confirmReauthTitle),
                                            message = host.text(WecomTexts.confirmReauthBody),
                                            confirmLabel = host.text(WecomTexts.confirmReauthOk),
                                            cancelLabel = host.text(WecomTexts.confirmCancel),
                                        ),
                                    )
                                    if (confirmed) controller.startConnect()
                                }
                            },
                            enabled = enabled,
                        )
                        DdTextAction(
                            text = host.text(WecomTexts.logout),
                            onClick = {
                                scope.launch {
                                    val confirmed = host.ui().confirm(
                                        uiConfirmRequest(
                                            title = host.text(WecomTexts.confirmLogoutTitle),
                                            message = host.text(WecomTexts.confirmLogoutBody),
                                            confirmLabel = host.text(WecomTexts.confirmLogoutOk),
                                            cancelLabel = host.text(WecomTexts.confirmCancel),
                                            destructive = true,
                                        ),
                                    )
                                    if (confirmed) controller.startLogout()
                                }
                            },
                            color = colors.danger,
                            enabled = enabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationSection(
    host: PluginHost,
    page: WecomPageState,
    controller: WecomPageController,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DdText(
                    text = page.stage?.let { host.text(it) } ?: host.text(WecomTexts.stageWorking),
                    modifier = Modifier.weight(1f),
                    style = DdType.body,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                page.percent?.let { percent ->
                    DdText(
                        text = "$percent%",
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                }
                DdTextAction(
                    text = host.text(WecomTexts.cancel),
                    onClick = { controller.cancelOperation() },
                    enabled = page.canCancel && !page.cancelling,
                )
            }
            DdProgressBar(progress = page.progress ?: 0f)
        }
    }
}

@Composable
private fun ScanSection(
    host: PluginHost,
    page: WecomPageState,
    controller: WecomPageController,
) {
    val colors = deepDeepColors()
    val motionEnabled = LocalDdMotionEnabled.current
    val qrSpec = motionFadeSpec(motionEnabled)
    Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Sm)) {
        DdSectionLabel(host.text(WecomTexts.stageScan))
        DdGroupCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DdSpacing.Card, vertical = DdSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DdSpacing.Md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DdText(
                        text = page.stage?.let { host.text(it) } ?: host.text(WecomTexts.stageWorking),
                        modifier = Modifier.weight(1f),
                        style = DdType.body,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DdTextAction(
                        text = host.text(WecomTexts.cancel),
                        onClick = { controller.cancelOperation() },
                        enabled = page.canCancel && !page.cancelling,
                    )
                }
                DdProgressBar(progress = page.progress ?: 0f)
                Box(
                    modifier = Modifier.size(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = page.qrPath,
                        animationSpec = qrSpec,
                        label = "wecom-qr",
                    ) { path ->
                        if (path == null) {
                            DdSpinner(size = 20.dp, color = colors.inkTertiary)
                        } else {
                            DdImage(
                                path = path,
                                contentDescription = host.text(WecomTexts.stageScan),
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                DdText(
                    text = host.text(WecomTexts.hintScan),
                    style = DdType.bodySmall,
                    color = colors.inkSecondary,
                    textAlign = TextAlign.Center,
                )
                DdTextAction(
                    text = host.text(WecomTexts.openLink),
                    onClick = { page.qrUrl?.let { host.ui().openUrl(it) } },
                    enabled = page.qrUrl != null,
                )
            }
        }
    }
}

private fun motionFadeSpec(motionEnabled: Boolean): FiniteAnimationSpec<Float> =
    if (motionEnabled) tween(durationMillis = DdMotion.FAST, easing = DdMotion.EaseOut) else snap()
