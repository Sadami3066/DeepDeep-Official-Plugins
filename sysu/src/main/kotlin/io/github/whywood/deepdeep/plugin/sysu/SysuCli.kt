package io.github.whywood.deepdeep.plugin.sysu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal enum class SysuSite(val key: String) {
    Jwxt("jwxt"),
    Lms("lms"),
    Gym("gym"),
    Netpay("netpay"),
    ;

    fun loginCommand(): String = if (this == Jwxt) "sysu login" else "sysu $key login"

    fun loginCodeCommand(code: String): String =
        if (this == Jwxt) "sysu login --code $code" else "sysu $key login --code $code"

    companion object {
        fun fromKey(value: String?): SysuSite? = entries.firstOrNull { it.key == value }
    }
}

internal object SysuCli {
    const val VERSION = "3.4.3"
    const val MIN_NODE_MAJOR = 22
    const val SKILL_ID = "deepdeep-sysu"

    const val SECRET_NET_ID = "netid"
    const val SECRET_PASSWORD = "password"

    val CODE_PATTERN = Regex("""^\d{4}$""")

    const val ASSET_ROOT = "sysu"
    const val ENTRY_RELATIVE = "bin/sysu"
    const val PACKAGE_RELATIVE = "package.json"

    const val INSTALL_DIR = "/root/projects/sysu"
    const val BIN_PATH = "/usr/local/bin/sysu"
    const val CONFIG_DIR = "/root/.config/sysu"
    const val CREDENTIALS_PATH = "$CONFIG_DIR/credentials"
    const val SESSION_PATH = "$CONFIG_DIR/jwxt/session.json"
    const val MFA_PATH = "$CONFIG_DIR/jwxt/mfa.json"
    const val LMS_PATH = "$CONFIG_DIR/lms.json"
    const val GYM_PATH = "$CONFIG_DIR/gym.json"
    const val GYM_JAR_PATH = "$CONFIG_DIR/gym.jar"
    const val NETPAY_PATH = "$CONFIG_DIR/netpay.json"

    fun statusCommand(): String = buildString {
        appendLine("echo '@@node@@'")
        appendLine("node --version 2>&1 || true")
        appendLine("echo '@@cli@@'")
        appendLine("$BIN_PATH --version 2>&1 || true")
        appendLine("echo '@@session@@'")
        appendLine("cat $SESSION_PATH 2>/dev/null || true")
        appendLine("echo")
        appendLine("echo '@@lms@@'")
        appendLine("cat $LMS_PATH 2>/dev/null || true")
        appendLine("echo")
        appendLine("echo '@@gym@@'")
        appendLine("cat $GYM_PATH 2>/dev/null || true")
        appendLine("echo")
        appendLine("echo '@@netpay@@'")
        appendLine("cat $NETPAY_PATH 2>/dev/null || true")
        appendLine("echo")
        appendLine("echo '@@credentials@@'")
        appendLine("cat $CREDENTIALS_PATH 2>/dev/null || true")
        appendLine("echo")
        appendLine("echo '@@end@@'")
    }

    val SITE_LOGIN_ORDER: List<SysuSite> = listOf(SysuSite.Lms, SysuSite.Gym, SysuSite.Netpay)

    fun siteLoginSequence(): List<String> =
        listOf(loginSendCodeCommand()) + SITE_LOGIN_ORDER.map { it.loginCommand() }

    fun nodeVersionCommand(): String = "node --version"

    fun versionCommand(): String = "$BIN_PATH --version"

    fun installCommand(assetDir: String): String = buildString {
        appendLine("set -e")
        appendLine("rm -rf $INSTALL_DIR $BIN_PATH")
        appendLine("mkdir -p /root/projects /usr/local/bin")
        appendLine("cp -R '${assetDir.trimEnd('/')}' $INSTALL_DIR")
        appendLine("chmod 755 $INSTALL_DIR/$ENTRY_RELATIVE")
        appendLine("ln -sf $INSTALL_DIR/$ENTRY_RELATIVE $BIN_PATH")
        appendLine("$BIN_PATH --version")
    }

    fun cleanupCommand(): String = "rm -rf $INSTALL_DIR $BIN_PATH"

    fun purgeCommand(): String = "rm -rf $CONFIG_DIR"

    fun writeFileCommand(path: String, base64: String): String =
        "mkdir -p ${path.substringBeforeLast('/')} && printf %s '$base64' | base64 -d > $path"

    fun credentialsReadCommand(): String = "cat $CREDENTIALS_PATH 2>/dev/null || true"

    fun credentialsCommand(netId: String, password: String): String =
        writeFileCommand(CREDENTIALS_PATH, base64(credentialsContent(netId, password))) +
            " && chmod 600 $CREDENTIALS_PATH"

    fun logoutCommand(): String =
        "rm -f $SESSION_PATH $MFA_PATH $LMS_PATH $GYM_PATH $GYM_JAR_PATH $NETPAY_PATH"

    fun loginSendCodeCommand(): String = "sysu login --send-code"

    fun loginCodeCommand(code: String): String = "sysu login --code $code"

    fun credentialsContent(netId: String, password: String): String =
        "netid=${netId.trim()}\npassword=${password.trim()}\n"

    fun base64(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun netId(credentials: String?): String? = credentials
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("netid=") }
        ?.substringAfter('=')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun isLoggedIn(sessionJson: String?): Boolean {
        if (sessionJson.isNullOrBlank()) return false
        return runCatching {
            Json.parseToJsonElement(sessionJson).jsonObject["cookies"]?.jsonArray?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    fun parseStatus(output: String): SysuRawStatus {
        val sections = linkedMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            val marker = STATUS_MARKERS.firstOrNull { line == "@@$it@@" }
            if (marker != null) {
                current = StringBuilder().also { sections[marker] = it }
            } else {
                current?.appendLine(raw)
            }
        }
        fun body(name: String): String? = sections[name]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        return SysuRawStatus(
            node = body("node"),
            cli = body("cli"),
            session = body("session"),
            lms = body("lms"),
            gym = body("gym"),
            netpay = body("netpay"),
            credentials = body("credentials"),
        )
    }

    fun parseNodeVersion(output: String?): String? = NODE_VERSION.find(output.orEmpty())?.value

    fun parseNodeMajor(version: String): Int? = Regex("""\d+""").find(version)?.value?.toIntOrNull()

    fun parseVersion(output: String?): String? = SEMVER.find(output.orEmpty())?.value

    fun cleanOutput(output: String?): String = ANSI.replace(output.orEmpty(), "").trim()

    fun needsCode(output: String): Boolean = CODE_NEEDED.any { output.contains(it) }

    fun loginSucceeded(output: String): Boolean =
        output.contains("登录态正常") || output.contains("不需要验证码")

    fun hasCookies(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching {
            Json.parseToJsonElement(json).jsonObject["cookies"]?.jsonArray?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    fun hasGymToken(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return runCatching {
            Json.parseToJsonElement(json).jsonObject["access_token"]?.scalarText()?.isNotBlank() == true
        }.getOrDefault(false)
    }

    private val CODE_NEEDED = listOf(
        "login --code",
        "--code <4 位>",
        "需要企业微信二次验证",
        "验证码已推送",
        "验证码已发送",
        "验证码已发到企业微信",
    )
    private val STATUS_MARKERS = listOf("node", "cli", "session", "lms", "gym", "netpay", "credentials", "end")
    private val NODE_VERSION = Regex("""v?\d+\.\d+\.\d+""")
    private val SEMVER = Regex("""\b\d+\.\d+\.\d+\b""")
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")
}

internal data class SysuRawStatus(
    val node: String?,
    val cli: String?,
    val session: String?,
    val lms: String?,
    val gym: String?,
    val netpay: String?,
    val credentials: String?,
) {
    fun connected(site: SysuSite): Boolean = when (site) {
        SysuSite.Jwxt -> SysuCli.isLoggedIn(session)
        SysuSite.Lms -> SysuCli.hasCookies(lms)
        SysuSite.Gym -> SysuCli.hasGymToken(gym)
        SysuSite.Netpay -> SysuCli.hasCookies(netpay)
    }
}
