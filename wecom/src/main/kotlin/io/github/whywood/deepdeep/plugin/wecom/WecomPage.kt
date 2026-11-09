package io.github.whywood.deepdeep.plugin.wecom

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdGroupCard
import io.github.whywood.deepdeep.design.DdListRow
import io.github.whywood.deepdeep.design.DdLoadingBlock
import io.github.whywood.deepdeep.design.DdPrimaryButton
import io.github.whywood.deepdeep.design.DdSectionLabel
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.LinuxChunk
import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class WecomPageState(
    val loading: Boolean = true,
    val checking: Boolean = false,
    val installing: Boolean = false,
    val connecting: Boolean = false,
    val loggingOut: Boolean = false,
    val linuxAvailable: Boolean = true,
    val cliVersion: String? = null,
    val authorized: Boolean = false,
    val botId: String? = null,
    val stage: String? = null,
    val progress: Float? = null,
    val qrPath: String? = null,
    val qrUrl: String? = null,
    val notice: String? = null,
    val error: String? = null,
) {
    val installed: Boolean get() = cliVersion != null

    val busy: Boolean get() = checking || installing || connecting || loggingOut
}

internal class WecomPageController(private val host: PluginHost) {
    var state by mutableStateOf(WecomPageState())
        private set

    private val store = WecomStatusStore(host)

    suspend fun refresh() {
        if (state.busy) return
        state = state.copy(checking = true)
        try {
            if (!host.linux().available) {
                state = state.copy(checking = false, loading = false, linuxAvailable = false)
                return
            }
            val versionResult = host.linux().exec(WecomCli.versionCommand(), timeoutMs = VERSION_TIMEOUT_MS)
            val version = versionResult.takeIf { it.exitCode == 0 }?.stdout?.let(WecomCli::parseVersion)
            if (version == null) {
                store.write(WecomStatus())
                state = state.copy(
                    checking = false,
                    loading = false,
                    linuxAvailable = true,
                    cliVersion = null,
                    authorized = false,
                    botId = null,
                )
                return
            }
            var authorized = false
            var error: String? = null
            val statusResult = host.linux().exec(WecomCli.authStatusCommand(), timeoutMs = STATUS_TIMEOUT_MS)
            when (val parsed = WecomCli.parseAuthStatus(statusResult.stdout)) {
                null -> error = host.text(WecomTexts.statusError, statusResult.stdout.takeLast(200).trim())
                else -> authorized = parsed
            }
            var botId: String? = null
            if (authorized) {
                val showResult = host.linux().exec(WecomCli.authShowCommand(), timeoutMs = STATUS_TIMEOUT_MS)
                botId = WecomCli.parseBotId(showResult.stdout)
            }
            store.write(WecomStatus(version, authorized, botId, System.currentTimeMillis()))
            state = state.copy(
                checking = false,
                loading = false,
                linuxAvailable = true,
                cliVersion = version,
                authorized = authorized,
                botId = botId,
                error = error,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = state.copy(
                checking = false,
                loading = false,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    suspend fun installCli() {
        if (state.busy) return
        state = state.copy(
            installing = true,
            error = null,
            notice = null,
            stage = host.text(WecomTexts.stageDownloading),
            progress = null,
            qrPath = null,
            qrUrl = null,
        )
        val staging = File(host.dataDir, STAGING_DIR)
        val archive = File(staging, WecomCli.ARCHIVE_NAME)
        var hadBinary = false
        try {
            hadBinary = hasExistingBinary()
            staging.deleteRecursively()
            staging.mkdirs()
            downloadArchive(archive)
            state = state.copy(stage = host.text(WecomTexts.stageInstalling), progress = null)
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
                installing = false,
                stage = null,
                progress = null,
            )
            refresh()
            host.requestContributionsRefresh()
            if (state.error == null) {
                state = state.copy(notice = host.text(WecomTexts.installDone))
            }
        } catch (error: CancellationException) {
            restore(hadBinary)
            throw error
        } catch (error: Exception) {
            restore(hadBinary)
            state = state.copy(
                installing = false,
                stage = null,
                progress = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun connect() {
        if (state.busy) return
        state = state.copy(
            connecting = true,
            error = null,
            notice = null,
            stage = host.text(WecomTexts.stageScan),
            progress = null,
            qrPath = null,
            qrUrl = null,
        )
        var qrFile: File? = null
        val pageUrl = AtomicReference<String?>(null)
        val outputTail = StringBuilder()
        var timedOut = false
        var exitCode = 0
        try {
            val file = WecomCli.qrFile(
                host.files().workspaceDir ?: throw IllegalStateException(host.text(WecomTexts.errorWorkspace)),
            )
            qrFile = file
            file.delete()
            coroutineScope {
                val poller = launch {
                    while (isActive) {
                        if (WecomCli.isCompleteQrCode(file)) {
                            state = state.copy(qrPath = file.absolutePath, qrUrl = pageUrl.get())
                            break
                        }
                        delay(QR_POLL_INTERVAL_MS)
                    }
                }
                try {
                    val completed = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                        host.linux().stream(WecomCli.authInitCommand(file)).collect { chunk ->
                            when (chunk) {
                                is LinuxChunk.Stdout -> {
                                    appendTail(outputTail, chunk.text)
                                    publishQrUrl(pageUrl, chunk.text, file)
                                }
                                is LinuxChunk.Stderr -> {
                                    appendTail(outputTail, chunk.text)
                                    publishQrUrl(pageUrl, chunk.text, file)
                                }
                                is LinuxChunk.Exit -> exitCode = chunk.exitCode
                            }
                        }
                    }
                    timedOut = completed == null
                } finally {
                    poller.cancel()
                }
            }
            state = state.copy(
                connecting = false,
                stage = host.text(WecomTexts.stageFinishing),
                qrPath = null,
                qrUrl = null,
            )
            when {
                timedOut -> throw IllegalStateException(host.text(WecomTexts.errorTimeout))
                exitCode != 0 -> throw IllegalStateException(
                    host.text(WecomTexts.errorAuth, failureDetail(outputTail, exitCode)),
                )
            }
            refresh()
            if (!state.authorized) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorAuth, host.text(WecomTexts.notConnected)),
                )
            }
            state = state.copy(stage = null)
            host.requestContributionsRefresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = state.copy(
                connecting = false,
                stage = null,
                qrPath = null,
                qrUrl = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            qrFile?.delete()
        }
    }

    suspend fun logout() {
        if (state.busy) return
        state = state.copy(loggingOut = true, error = null, notice = null, stage = host.text(WecomTexts.stageSigningOut))
        try {
            val result = host.linux().exec(WecomCli.logoutCommand(), timeoutMs = LOGOUT_TIMEOUT_MS)
            if (result.exitCode != 0) {
                throw IllegalStateException(
                    host.text(WecomTexts.errorLogout, result.stdout.takeLast(300).trim()),
                )
            }
            state = state.copy(loggingOut = false, stage = null, authorized = false, botId = null)
            refresh()
            host.requestContributionsRefresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state = state.copy(
                loggingOut = false,
                stage = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    private suspend fun downloadArchive(archive: File) {
        var lastError: Exception? = null
        for (url in WecomCli.DOWNLOAD_URLS) {
            try {
                host.network().download(url, archive.absolutePath, WecomCli.ARCHIVE_SHA256) { percent ->
                    state = state.copy(progress = percent?.let { it / 100f })
                }
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
                archive.delete()
            }
        }
        throw lastError ?: IllegalStateException(
            host.text(WecomTexts.errorInstall, "no download url"),
        )
    }

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
        tail.toString().trim().ifEmpty { "exit $exitCode" }

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
internal fun WecomPage(host: PluginHost) {
    val controller = remember(host) { WecomPageController(host) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }
    val page = controller.state
    val qrBitmap = rememberQrBitmap(page.qrPath)
    val colors = deepDeepColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DdGroupCard {
            DdListRow(title = host.text(WecomTexts.cliVersion), value = versionText(host, page))
            DdListRow(title = host.text(WecomTexts.authorization), value = authorizationText(host, page))
        }
        if (!page.linuxAvailable) {
            Text(
                text = host.text(WecomTexts.unavailableLinux),
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.danger,
            )
        } else {
            ActionsCard(host, page, controller, scope)
        }
        if (page.busy && page.qrPath == null && page.qrUrl == null) DdLoadingBlock(text = page.stage)
        page.notice?.let { notice ->
            Text(
                text = notice,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.inkSecondary,
            )
        }
        page.error?.let { error ->
            Text(
                text = error,
                modifier = Modifier.padding(horizontal = 4.dp),
                style = DdType.bodySmall,
                color = colors.danger,
            )
        }
        if (page.qrPath != null || page.qrUrl != null) {
            DdSectionLabel(host.text(WecomTexts.stageScan))
            DdGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap,
                            contentDescription = host.text(WecomTexts.stageScan),
                            modifier = Modifier.size(220.dp),
                        )
                    }
                    Text(
                        text = host.text(WecomTexts.hintScan),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                    page.qrUrl?.let { url ->
                        DdTextAction(
                            text = host.text(WecomTexts.openLink),
                            onClick = { host.ui().openUrl(url) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionsCard(
    host: PluginHost,
    page: WecomPageState,
    controller: WecomPageController,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val colors = deepDeepColors()
    DdGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                !page.installed -> {
                    Text(
                        text = host.text(WecomTexts.installHint),
                        style = DdType.bodySmall,
                        color = colors.inkSecondary,
                    )
                    DdPrimaryButton(
                        text = host.text(WecomTexts.installCli),
                        onClick = { scope.launch { controller.installCli() } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !page.busy,
                    )
                }
                !page.authorized -> {
                    DdPrimaryButton(
                        text = host.text(WecomTexts.connect),
                        onClick = { scope.launch { controller.connect() } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !page.busy,
                    )
                    DdTextAction(
                        text = host.text(WecomTexts.installCli),
                        onClick = { scope.launch { controller.installCli() } },
                        enabled = !page.busy,
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DdTextAction(
                            text = host.text(WecomTexts.reauthorize),
                            onClick = { scope.launch { controller.connect() } },
                            enabled = !page.busy,
                        )
                        Spacer(Modifier.width(24.dp))
                        DdTextAction(
                            text = host.text(WecomTexts.logout),
                            onClick = { scope.launch { controller.logout() } },
                            color = colors.danger,
                            enabled = !page.busy,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberQrBitmap(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }
        }
    }
    return bitmap
}

private fun versionText(host: PluginHost, page: WecomPageState): String = when {
    page.loading -> host.text(WecomTexts.checking)
    page.cliVersion != null -> page.cliVersion
    else -> host.text(WecomTexts.notInstalled)
}

private fun authorizationText(host: PluginHost, page: WecomPageState): String = when {
    page.loading -> host.text(WecomTexts.checking)
    !page.installed -> host.text(WecomTexts.notConnected)
    !page.authorized -> host.text(WecomTexts.notConnected)
    else -> page.botId?.let { "${host.text(WecomTexts.connected)} · $it" } ?: host.text(WecomTexts.connected)
}
