package io.github.whywood.deepdeep.plugin.feishu

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdAvatar
import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val AVATAR_MAX_BYTES = 512L * 1024L

private val avatarMemory = ConcurrentHashMap<String, ImageBitmap>()

@Composable
internal fun FeishuAvatar(host: PluginHost, avatarUrl: String?, name: String, size: Int) {
    val initial = initialOf(name)
    var image by remember(avatarUrl) { mutableStateOf(avatarUrl?.let { avatarMemory[it] }) }
    LaunchedEffect(host, avatarUrl) {
        if (image != null || avatarUrl.isNullOrBlank()) return@LaunchedEffect
        image = FeishuAvatarCache.load(host, avatarUrl)
    }
    DdAvatar(
        image = image,
        initials = initial,
        size = size.dp,
    )
}

private object FeishuAvatarCache {
    private val loading = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(host: PluginHost, url: String): ImageBitmap? {
        avatarMemory[url]?.let { return it }
        if (!loading.add(url)) return null
        return try {
            val file = cacheFile(host, url)
            val cached = withContext(Dispatchers.IO) { decode(file) }
            if (cached != null) {
                avatarMemory[url] = cached
                cached
            } else {
                withContext(Dispatchers.IO) { file.delete() }
                download(host, url, file)?.also { avatarMemory[url] = it }
            }
        } finally {
            loading.remove(url)
        }
    }

    private suspend fun download(host: PluginHost, url: String, target: File): ImageBitmap? {
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        withContext(Dispatchers.IO) { temporary.delete() }
        val result = runCatching { host.network().download(url, temporary.absolutePath) }.getOrNull() ?: return null
        if (result.size <= 0L || temporary.length() > AVATAR_MAX_BYTES) {
            withContext(Dispatchers.IO) { temporary.delete() }
            return null
        }
        val decoded = withContext(Dispatchers.IO) { decode(temporary) }
        if (decoded == null) {
            withContext(Dispatchers.IO) { temporary.delete() }
            return null
        }
        withContext(Dispatchers.IO) {
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
        return decoded
    }

    private fun decode(file: File): ImageBitmap? {
        if (!file.isFile || file.length() <= 0L || file.length() > AVATAR_MAX_BYTES) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
    }

    private fun cacheFile(host: PluginHost, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            .take(32)
        return File(File(host.dataDir, "avatars"), "$digest.img")
    }
}
