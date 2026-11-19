package io.github.whywood.superpi.plugin.cli.update

import io.github.whywood.superpi.plugin.api.NetworkService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CliUpdateCheck(
    val latestVersion: String?,
    val release: CliRelease?,
    val failed: Boolean,
)

fun interface CliUpdateSource {
    suspend fun discover(network: NetworkService?, installedVersion: String?): CliUpdateCheck
}

object CliUpdateThrottle {
    const val DEFAULT_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

    fun shouldCheck(
        lastCheckedAt: Long,
        now: Long,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Boolean = lastCheckedAt <= 0L || now - lastCheckedAt >= intervalMs
}

class CliUpdateCheckController(
    private val source: CliUpdateSource,
    private val intervalMs: Long = CliUpdateThrottle.DEFAULT_INTERVAL_MS,
) {
    private val lock = Mutex()

    private var job: Job? = null

    fun isRunning(): Boolean = job?.isActive == true

    fun request(
        scope: CoroutineScope,
        network: NetworkService?,
        installedVersion: String?,
        lastCheckedAt: Long,
        force: Boolean,
        onStart: () -> Unit,
        onResult: suspend (CliUpdateCheck) -> Unit,
    ) {
        if (isRunning()) return
        if (installedVersion == null) return
        val now = System.currentTimeMillis()
        if (!force && !CliUpdateThrottle.shouldCheck(lastCheckedAt, now, intervalMs)) return
        onStart()
        job = scope.launch {
            lock.withLock {
                val result = try {
                    source.discover(network, installedVersion)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    CliUpdateCheck(latestVersion = null, release = null, failed = true)
                }
                onResult(result)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

object CliHttp {
    suspend fun getText(
        network: NetworkService,
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = NetworkService.DEFAULT_TIMEOUT_MS,
    ): String? = try {
        val response = network.request("GET", url, headers, timeoutMs = timeoutMs)
        if (response.statusCode in 200..299) response.body else null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        null
    }

    suspend fun firstText(
        network: NetworkService,
        urls: Iterable<String>,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = NetworkService.DEFAULT_TIMEOUT_MS,
    ): String? {
        for (url in urls) {
            val body = getText(network, url, headers, timeoutMs)
            if (body != null) return body
        }
        return null
    }
}
