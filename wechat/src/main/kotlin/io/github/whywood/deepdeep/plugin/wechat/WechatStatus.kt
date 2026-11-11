package io.github.whywood.deepdeep.plugin.wechat

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

internal class WechatException(message: String) : Exception(message)
