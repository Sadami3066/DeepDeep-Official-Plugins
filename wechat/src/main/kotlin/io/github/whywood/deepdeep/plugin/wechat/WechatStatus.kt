package io.github.whywood.deepdeep.plugin.wechat

import io.github.whywood.deepdeep.plugin.api.PluginHost

internal data class WechatStatus(
    val linuxAvailable: Boolean = false,
    val nodeVersion: String? = null,
    val cliVersion: String? = null,
    val hasSnapshot: Boolean = false,
    val hasNormalized: Boolean = false,
    val exports: List<String> = emptyList(),
) {
    val nodeTooOld: Boolean
        get() = nodeVersion?.let { (WechatCli.parseMajor(it) ?: 0) < WechatCli.MIN_NODE_MAJOR } == true

    val installed: Boolean get() = cliVersion != null

    val outdated: Boolean get() = cliVersion != null && cliVersion != WechatCli.VERSION
}

internal class WechatSettingsStore(private val host: PluginHost) {
    suspend fun readRange(): Pair<String, String>? {
        val from = runCatching { host.settings().get(KEY_FROM) }.getOrNull()
            ?.takeIf { WechatCli.DATE_PATTERN.matches(it) }
        val to = runCatching { host.settings().get(KEY_TO) }.getOrNull()
            ?.takeIf { WechatCli.DATE_PATTERN.matches(it) }
        return if (from != null && to != null) from to to else null
    }

    suspend fun writeRange(from: String, to: String) {
        runCatching {
            host.settings().put(KEY_FROM, from)
            host.settings().put(KEY_TO, to)
        }
    }

    suspend fun clear() {
        runCatching { host.settings().remove(KEY_FROM) }
        runCatching { host.settings().remove(KEY_TO) }
    }

    private companion object {
        const val KEY_FROM = "export_from"
        const val KEY_TO = "export_to"
    }
}

internal class WechatException(message: String) : Exception(message)
