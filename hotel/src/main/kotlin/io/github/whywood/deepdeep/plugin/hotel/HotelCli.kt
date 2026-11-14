package io.github.whywood.deepdeep.plugin.hotel

internal object HotelCli {
    const val BINARY = "rgh"
    const val CONFIG_DIR = "/root/.hotel-cli"
    private const val ENV = "HOME=/root"

    fun versionCommand(): String = "$ENV $BINARY --version 2>&1"

    fun statusCommand(): String = "$ENV $BINARY status 2>&1"

    fun loginCommand(): String = "$ENV $BINARY login 2>&1"

    fun logoutCommand(): String = "$ENV $BINARY logout 2>&1"

    fun installCommand(): String = "$ENV npm install -g @rollinggo/hotel@latest 2>&1"

    fun uninstallCommand(): String = "$ENV npm uninstall -g @rollinggo/hotel >/dev/null 2>&1; rm -rf $CONFIG_DIR; true"

    fun parseVersion(output: String): String? =
        Regex("""(?m)^\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$""").find(output)?.groupValues?.get(1)
            ?: Regex("""([0-9]+\.[0-9]+\.[0-9]+)""").find(output)?.groupValues?.get(1)

    data class Status(val loggedIn: Boolean, val account: String?)

    fun parseStatus(output: String): Status {
        val loggedIn = output.lineSequence().any { it.trim() == "已登录" }
        val account = output.lineSequence()
            .firstOrNull { it.trim().startsWith("用户:") || it.trim().startsWith("用户：") }
            ?.substringAfter(':')
            ?.substringAfter('：')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return Status(loggedIn = loggedIn, account = account)
    }

    fun parseLoginUrl(output: String): String? =
        Regex("""https://[^\s"'<>\\]+""").findAll(output)
            .map { it.value.trimEnd('.', ',', ')', ']') }
            .firstOrNull { it.contains("rollinggo") || it.contains("api.") }
}
