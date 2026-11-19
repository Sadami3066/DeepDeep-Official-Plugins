package io.github.whywood.superpi.plugin.wecom

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
import io.github.whywood.superpi.design.DdGroupCard
import io.github.whywood.superpi.design.DdHairline
import io.github.whywood.superpi.design.DdImage
import io.github.whywood.superpi.design.DdLoadingBlock
import io.github.whywood.superpi.design.DdNoticeBar
import io.github.whywood.superpi.design.DdNoticeTone
import io.github.whywood.superpi.design.DdPrimaryButton
import io.github.whywood.superpi.design.DdProgressBar
import io.github.whywood.superpi.design.DdSpacing
import io.github.whywood.superpi.design.DdSpinner
import io.github.whywood.superpi.design.DdStatusRow
import io.github.whywood.superpi.design.DdStatusTone
import io.github.whywood.superpi.design.DdText
import io.github.whywood.superpi.design.DdTextAction
import io.github.whywood.superpi.design.DdType
import io.github.whywood.superpi.design.superPiColors
import io.github.whywood.superpi.plugin.api.LinuxChunkKind
import io.github.whywood.superpi.plugin.api.LocalizedText
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.notificationAction
import io.github.whywood.superpi.plugin.api.pluginNotificationWithActions
import io.github.whywood.superpi.plugin.api.uiConfirmRequest
import io.github.whywood.superpi.plugin.cli.update.CliInstallEvent
import io.github.whywood.superpi.plugin.cli.update.CliInstallStage
import io.github.whywood.superpi.plugin.cli.update.CliRelease
import io.github.whywood.superpi.plugin.cli.update.CliUpdateCheck
import io.github.whywood.superpi.plugin.cli.update.CliUpdateCheckController
import io.github.whywood.superpi.plugin.cli.update.CliUpdateThrottle
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class WecomOperation {
    NONE,
    INSTALL,
    UPDATE,
    CONNECT,
    LOGOUT,
}

internal enum class WecomUpdateStatus {
    IDLE,
    CHECKING,
    LATEST,
    AVAILABLE,
    FAILED,
}

internal data class WecomUpdateState(
    val status: WecomUpdateStatus = WecomUpdateStatus.IDLE,
    val release: WecomRelease? = null,
    val latestVersion: String? = null,
    val checkedAt: Long = 0L,
    val error: String? = null,
) {
    val outdated: Boolean get() = status == WecomUpdateStatus.AVAILABLE
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
    val update: WecomUpdateState = WecomUpdateState(),
) {
    val installed: Boolean get() = cliVersion != null

    val busy: Boolean get() = checking || operation != WecomOperation.NONE

    val updateBusy: Boolean get() = update.status == WecomUpdateStatus.CHECKING
}

internal class WecomPageController(
    private val host: PluginHost,
    private val plugin: WecomPlugin,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(WecomPageState())
        private set

    private val store = WecomStatusStore(host)
    private val releaseStore = WecomReleaseStore(host)
    private val releaseSource = WecomReleaseSource()
    private val updateChecker = CliUpdateCheckController(releaseSource, WecomReleases.THROTTLE_MS)

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
                checkUpdate(force = false)
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
            checkUpdate(force = false)
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
        startOperation(WecomOperation.INSTALL) { installCli(update = false) }
    }

    fun startUpdate() {
        if (!state.installed) return
        startOperation(WecomOperation.UPDATE) { installCli(update = true) }
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
            WecomOperation.UPDATE -> startUpdate()
            WecomOperation.CONNECT -> startConnect()
            WecomOperation.LOGOUT -> startLogout()
            WecomOperation.NONE, null -> retryCheck()
        }
    }

    fun retryCheck() {
        if (state.checking || state.operation != WecomOperation.NONE) return
        scope.launch { refresh() }
    }

    fun retryUpdateCheck() {
        if (state.updateBusy || state.operation != WecomOperation.NONE) return
        scope.launch { checkUpdate(force = true) }
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

    private suspend fun checkUpdate(force: Boolean) {
        if (state.updateBusy) return
        val cached = releaseStore.read()
        if (!force && cached != null && !CliUpdateThrottle.shouldCheck(cached.checkedAt, System.currentTimeMillis())) {
            applyRelease(cached)
            return
        }
        updateChecker.request(
            scope = scope,
            network = runCatching { host.network() }.getOrNull(),
            installedVersion = state.cliVersion ?: "",
            lastCheckedAt = cached?.checkedAt ?: 0L,
            force = force,
            onStart = {
                state = state.copy(
                    update = state.update.copy(status = WecomUpdateStatus.CHECKING, error = null),
                )
            },
            onResult = { result -> handleUpdateResult(result) },
        )
    }

    private suspend fun handleUpdateResult(result: CliUpdateCheck) {
        if (result.failed) {
            val reason = releaseSource.failure ?: host.t("网络不可用", "Network unavailable")
            failUpdate(host.text(WecomTexts.updateCheckFailed, reason))
            return
        }
        val release = result.release?.let { WecomReleases.fromCheck(it, System.currentTimeMillis()) }
        if (release == null) {
            failUpdate(
                host.text(
                    WecomTexts.updateCheckFailed,
                    host.t("无法获取更新信息", "Couldn't fetch update info"),
                ),
            )
            return
        }
        releaseStore.write(release)
        applyRelease(release)
    }

    private fun applyRelease(release: WecomRelease) {
        val installed = state.cliVersion
        val status = when {
            installed == null -> WecomUpdateStatus.LATEST
            WecomReleases.isNewer(release.version, installed) -> WecomUpdateStatus.AVAILABLE
            else -> WecomUpdateStatus.LATEST
        }
        state = state.copy(
            update = WecomUpdateState(
                status = status,
                release = release,
                latestVersion = release.version,
                checkedAt = release.checkedAt,
                error = null,
            ),
        )
    }

    private fun failUpdate(message: String) {
        state = state.copy(
            update = state.update.copy(
                status = WecomUpdateStatus.FAILED,
                error = message,
            ),
        )
    }

    private suspend fun installCli(update: Boolean) {
        val release = resolveInstallRelease(update)
        WecomInstall.run(
            host = host,
            linux = host.linux(),
            network = host.network(),
            release = release,
        ) { event -> applyInstallEvent(event) }
        state = state.copy(
            stage = WecomTexts.stageFinishingInstall,
            progress = 0.98f,
            canCancel = false,
        )
        refresh()
        host.requestContributionsRefresh()
        if (!update) notifyInstalled()
        if (state.error == null) {
            val message = if (update) WecomTexts.snackbarUpdated else WecomTexts.installDone
            host.ui().snackbar(host.text(message))
        }
    }

    private suspend fun resolveInstallRelease(update: Boolean): CliRelease {
        val cached = state.update.release ?: releaseStore.read()
        if (cached != null) return WecomReleases.toCliRelease(cached)
        if (!update) return WecomReleases.pinnedRelease()
        val message = state.update.error
            ?: host.text(WecomTexts.updateCheckFailed, host.t("无法获取更新信息", "Couldn't fetch update info"))
        throw IllegalStateException(message)
    }

    private fun applyInstallEvent(event: CliInstallEvent) {
        state = when (event.stage) {
            CliInstallStage.DOWNLOADING -> {
                val percent = event.progress
                state.copy(
                    stage = WecomTexts.stageDownloading,
                    progress = percent?.let(::downloadProgress) ?: 0.05f,
                    percent = percent,
                )
            }
            CliInstallStage.VERIFYING -> state.copy(
                stage = WecomTexts.stageVerifying,
                progress = 0.85f,
                percent = null,
            )
            CliInstallStage.INSTALLING -> state.copy(
                stage = WecomTexts.stageInstalling,
                progress = 0.92f,
                percent = null,
            )
            CliInstallStage.FINISHING -> state.copy(
                stage = WecomTexts.stageFinishingInstall,
                progress = 0.98f,
                percent = null,
                canCancel = false,
            )
        }
    }

    private fun downloadProgress(percent: Int): Float =
        0.05f + 0.75f * percent.coerceIn(0, 100) / 100f

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
        const val QR_POLL_INTERVAL_MS = 400L
        const val ERROR_TAIL = 800
        const val VERSION_TIMEOUT_MS = 20_000L
        const val STATUS_TIMEOUT_MS = 30_000L
        const val INIT_TIMEOUT_MS = 6 * 60_000L
        const val LOGOUT_TIMEOUT_MS = 60_000L
    }
}

@Composable
internal fun WecomPage(plugin: WecomPlugin, host: PluginHost) {
    val scope = rememberCoroutineScope()
    val controller = remember(host, plugin, scope) { WecomPageController(host, plugin, scope) }
    val scrollState = rememberScrollState()
    val page = controller.state
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(page.operation) {
        if (page.operation != WecomOperation.NONE) scrollState.scrollTo(0)
    }
    val colors = superPiColors()
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
        verticalArrangement = Arrangement.spacedBy(DdSpacing.Lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DdSpacing.Xs)) {
            DdText(
                text = host.text(WecomTexts.pageTitle),
                style = DdType.heading,
                color = colors.ink,
            )
            DdText(
                text = host.text(WecomTexts.purpose),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
        if (page.operation == WecomOperation.CONNECT) {
            ConnectCard(host = host, page = page, controller = controller)
        }
        StatusCard(host = host, page = page)
        if (page.operation != WecomOperation.CONNECT) {
            if (page.checked && !page.linuxAvailable) {
                DdNoticeBar(
                    text = host.text(WecomTexts.unavailableLinux),
                    tone = DdNoticeTone.WARNING,
                    actionLabel = host.text(WecomTexts.recheck),
                    onAction = { controller.retryCheck() },
                )
            }
            if (page.error != null) {
                DdNoticeBar(
                    text = page.error,
                    tone = DdNoticeTone.DANGER,
                    actionLabel = host.text(WecomTexts.retry),
                    onAction = { controller.retry() },
                )
            }
            if (page.operation == WecomOperation.NONE && page.installed) {
                when (page.update.status) {
                    WecomUpdateStatus.AVAILABLE -> DdNoticeBar(
                        text = host.text(WecomTexts.updateNotice),
                        tone = DdNoticeTone.INFO,
                        actionLabel = host.text(WecomTexts.update),
                        onAction = { controller.startUpdate() },
                    )
                    WecomUpdateStatus.FAILED -> DdNoticeBar(
                        text = page.update.error ?: host.text(WecomTexts.updateCheckFailed, ""),
                        tone = DdNoticeTone.WARNING,
                        actionLabel = host.text(WecomTexts.retry),
                        onAction = { controller.retryUpdateCheck() },
                    )
                    WecomUpdateStatus.CHECKING -> DdText(
                        text = host.text(WecomTexts.checkingUpdate),
                        style = DdType.bodySmall,
                        color = colors.inkTertiary,
                    )
                    WecomUpdateStatus.IDLE, WecomUpdateStatus.LATEST -> Unit
                }
            }
            if (!page.checked && page.checking) {
                DdLoadingBlock(text = host.text(WecomTexts.checkingStatus))
            }
            when {
                page.busy -> OperationCard(host = host, page = page, controller = controller)
                page.checked && page.linuxAvailable -> ActionSection(
                    host = host,
                    page = page,
                    controller = controller,
                    scope = scope,
                )
            }
        }
        DdText(
            text = host.text(WecomTexts.footer),
            style = DdType.micro,
            color = colors.inkTertiary,
        )
    }
}

@Composable
private fun StatusCard(host: PluginHost, page: WecomPageState) {
    val unknownValue = if (page.checking) {
        host.text(WecomTexts.checking)
    } else {
        host.text(WecomTexts.checkFailed)
    }
    val unknownTone = if (page.checking) DdStatusTone.NEUTRAL else DdStatusTone.WARNING
    val outdated = page.update.status == WecomUpdateStatus.AVAILABLE
    val componentBusy = (page.checking && !page.checked) || page.updateBusy
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
            DdHairline()
            DdStatusRow(
                label = host.text(WecomTexts.component),
                value = when {
                    !page.checked -> unknownValue
                    !page.linuxAvailable -> host.text(WecomTexts.unavailable)
                    !page.installed -> host.text(WecomTexts.notInstalled)
                    outdated -> host.text(WecomTexts.componentUpdate)
                    else -> host.text(WecomTexts.componentReady)
                },
                tone = when {
                    !page.checked -> unknownTone
                    !page.linuxAvailable -> DdStatusTone.NEUTRAL
                    !page.installed -> DdStatusTone.NEUTRAL
                    outdated -> DdStatusTone.WARNING
                    else -> DdStatusTone.SUCCESS
                },
                busy = componentBusy,
            )
            DdHairline()
            DdStatusRow(
                label = host.text(WecomTexts.authorization),
                value = when {
                    !page.checked -> unknownValue
                    !page.linuxAvailable -> host.text(WecomTexts.unavailable)
                    page.authorized -> host.text(WecomTexts.connected)
                    else -> host.text(WecomTexts.notConnected)
                },
                tone = when {
                    !page.checked -> unknownTone
                    !page.linuxAvailable -> DdStatusTone.NEUTRAL
                    page.authorized -> DdStatusTone.SUCCESS
                    page.installed -> DdStatusTone.WARNING
                    else -> DdStatusTone.NEUTRAL
                },
                busy = page.checking && !page.checked,
            )
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
    val colors = superPiColors()
    val enabled = !page.busy && !page.checking
    when {
        !page.installed -> DdPrimaryButton(
            text = host.text(WecomTexts.install),
            onClick = { controller.startInstall() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        !page.authorized -> DdPrimaryButton(
            text = host.text(WecomTexts.connect),
            onClick = { controller.startConnect() },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )

        else -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DdTextAction(
                text = host.text(WecomTexts.reauthorize),
                enabled = enabled,
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
            )
            DdTextAction(
                text = host.text(WecomTexts.logout),
                enabled = enabled,
                color = colors.danger,
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
            )
        }
    }
}

@Composable
private fun OperationCard(host: PluginHost, page: WecomPageState, controller: WecomPageController) {
    val colors = superPiColors()
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
                page.percent?.takeIf { !page.cancelling }?.let { percent ->
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
            DdProgressBar(progress = if (page.cancelling) null else page.progress ?: 0f)
        }
    }
}

@Composable
private fun ConnectCard(host: PluginHost, page: WecomPageState, controller: WecomPageController) {
    val colors = superPiColors()
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
            DdProgressBar(progress = if (page.cancelling) null else page.progress ?: 0f)
            Box(
                modifier = Modifier.size(QrCodeSize),
                contentAlignment = Alignment.Center,
            ) {
                val path = page.qrPath
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
            DdText(
                text = host.text(WecomTexts.scanHint),
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

private val QrCodeSize = 220.dp
