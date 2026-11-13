package io.github.whywood.deepdeep.plugin.mail.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAvatar
import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AvatarMaxBytes = 512L * 1024L
private val avatarMemory = ConcurrentHashMap<String, ImageBitmap>()

@Composable
internal fun SenderAvatar(
    host: PluginHost,
    address: String,
    name: String,
    size: Dp = 34.dp,
) {
    var image by remember(address) { mutableStateOf(avatarMemory[address.trim().lowercase()]) }
    LaunchedEffect(host, address) {
        if (image != null || address.isBlank()) return@LaunchedEffect
        image = MailAvatars.load(host, address)
    }
    DdAvatar(
        image = image,
        initials = mailInitial(name, address),
        size = size,
    )
}

private object MailAvatars {
    private val loading = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(host: PluginHost, address: String): ImageBitmap? {
        val key = address.trim().lowercase()
        if (key.isBlank()) return null
        avatarMemory[key]?.let { return it }
        if (!loading.add(key)) return null
        return try {
            val file = cacheFile(host, key)
            val cached = decode(file)
            if (cached != null) {
                avatarMemory[key] = cached
                cached
            } else {
                file.delete()
                val downloaded = download(host, key, file)
                downloaded?.also { avatarMemory[key] = it }
            }
        } finally {
            loading.remove(key)
        }
    }

    private suspend fun download(host: PluginHost, address: String, target: File): ImageBitmap? {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        for (url in sources(address)) {
            temporary.delete()
            val result = runCatching {
                host.network().download(url, temporary.absolutePath)
            }.getOrNull() ?: continue
            if (result.size <= 0L || temporary.length() > AvatarMaxBytes) {
                temporary.delete()
                continue
            }
            val decoded = decode(temporary)
            if (decoded == null) {
                temporary.delete()
                continue
            }
            target.delete()
            temporary.renameTo(target)
            return decoded
        }
        temporary.delete()
        return null
    }

    private fun decode(file: File): ImageBitmap? {
        if (!file.isFile || file.length() <= 0L || file.length() > AvatarMaxBytes) return null
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    private fun cacheFile(host: PluginHost, address: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)
        return File(File(host.dataDir, "avatars"), "$digest.img")
    }

    private fun sources(address: String): List<String> {
        val at = address.lastIndexOf('@')
        if (at <= 0 || at >= address.lastIndex) return emptyList()
        val local = address.substring(0, at).trim()
        val domain = address.substring(at + 1).trim().lowercase()
            .filter { it.isLetterOrDigit() || it == '.' || it == '-' }
        if (domain.isBlank()) return emptyList()
        val urls = mutableListOf<String>()
        val qq = local.takeIf { it.all(Char::isDigit) && it.length in 5..12 }
        if (qq != null && (domain == "qq.com" || domain == "foxmail.com" || domain == "vip.qq.com")) {
            urls += "https://q1.qlogo.cn/g?b=qq&nk=$qq&s=100"
        }
        val gravatarHash = MessageDigest.getInstance("MD5")
            .digest(address.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        urls += "https://www.gravatar.com/avatar/$gravatarHash?s=100&d=404"
        urls += "https://$domain/apple-touch-icon.png"
        urls += "https://$domain/favicon.png"
        urls += "https://$domain/favicon.ico"
        return urls
    }
}
