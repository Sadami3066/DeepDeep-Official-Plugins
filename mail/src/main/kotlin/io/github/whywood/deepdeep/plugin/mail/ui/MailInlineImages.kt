package io.github.whywood.deepdeep.plugin.mail.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.whywood.deepdeep.design.DdHtmlImageLoader
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.mail.MailRepository
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MemoryCacheBytes = 16 * 1024 * 1024
private const val MaxRemoteImageBytes = 5L * 1024L * 1024L
private const val MaxDiskCacheBytes = 128L * 1024L * 1024L
private const val MaxDecodedEdge = 2048

private object MailInlineBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(MemoryCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) { cache.put(key, bitmap) }
    }
}

internal class MailInlineImageLoader(
    private val host: PluginHost,
    private val repository: MailRepository,
    private val accountId: String,
    private val messageId: String,
) : DdHtmlImageLoader {
    private val failedSources = ConcurrentHashMap.newKeySet<String>()

    override suspend fun load(source: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$accountId/$messageId|$source"
        MailInlineBitmapCache.get(key)?.let { return@withContext it.asImageBitmap() }
        if (source in failedSources) return@withContext null
        val resolved = resolve(source)
        val bitmap = resolved?.let(::decode)
        if (bitmap == null) {
            failedSources += source
            return@withContext null
        }
        MailInlineBitmapCache.put(key, bitmap)
        bitmap.asImageBitmap()
    }

    private suspend fun resolve(source: String): Resolved? {
        val trimmed = source.trim().trim('"', '\'')
        if (trimmed.isEmpty()) return null
        return when {
            trimmed.startsWith("cid:", ignoreCase = true) -> resolveContentId(trimmed.substring(4))
            trimmed.startsWith("data:", ignoreCase = true) -> resolveDataUri(trimmed)
            isRemote(trimmed) -> resolveRemote(if (trimmed.startsWith("//")) "https:$trimmed" else trimmed)
            else -> resolveContentId(trimmed)
        }
    }

    private suspend fun resolveContentId(raw: String): Resolved? {
        val contentId = decodeContentId(raw)
        if (contentId.isBlank()) return null
        val path = runCatching { repository.loadInlineImage(accountId, messageId, contentId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Resolved.Path(path)
    }

    private fun decodeContentId(raw: String): String {
        val stripped = raw.trim().trim('<', '>')
        return runCatching { URLDecoder.decode(stripped, Charsets.UTF_8.name()) }
            .getOrDefault(stripped)
            .trim()
    }

    private fun resolveDataUri(source: String): Resolved? {
        val comma = source.indexOf(',')
        if (comma <= 5) return null
        val meta = source.substring(5, comma).lowercase()
        val payload = source.substring(comma + 1)
        if (!meta.contains(";base64")) return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }
            .recoverCatching { Base64.decode(payload, Base64.URL_SAFE) }
            .getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return Resolved.Bytes(bytes)
    }

    private suspend fun resolveRemote(url: String): Resolved? {
        val directory = ensureDiskCache()
        val target = File(directory, "${sha256(url)}.img")
        if (target.isFile && target.length() > 0L) return Resolved.Path(target.absolutePath)
        val temporary = File(directory, "${target.name}.part")
        val result = runCatching { host.network().download(url, temporary.absolutePath) }.getOrNull()
        if (result == null || result.size <= 0L || result.size > MaxRemoteImageBytes) {
            temporary.delete()
            return null
        }
        if (!temporary.renameTo(target)) {
            if (!target.isFile) {
                temporary.delete()
                return null
            }
            temporary.delete()
        }
        if (!target.isFile || target.length() <= 0L) return null
        return Resolved.Path(target.absolutePath)
    }

    private fun ensureDiskCache(): File {
        val directory = File(File(host.dataDir, "cache"), "inline-images")
        directory.mkdirs()
        pruneDiskCache(directory)
        return directory
    }

    private fun pruneDiskCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return
        files.filter { it.name.endsWith(".part") && System.currentTimeMillis() - it.lastModified() > PartMaxAgeMillis }
            .forEach { it.delete() }
        var total = files.filterNot { it.name.endsWith(".part") }.sumOf { it.length() }
        if (total <= MaxDiskCacheBytes) return
        files.filterNot { it.name.endsWith(".part") }
            .sortedBy { it.lastModified() }
            .forEach { file ->
                if (total <= MaxDiskCacheBytes) return
                total -= file.length()
                file.delete()
            }
    }

    private fun decode(resolved: Resolved): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeInto(resolved, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MaxDecodedEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return decodeInto(resolved, options)
    }

    private fun decodeInto(resolved: Resolved, options: BitmapFactory.Options): Bitmap? = when (resolved) {
        is Resolved.Path -> BitmapFactory.decodeFile(resolved.path, options)
        is Resolved.Bytes -> BitmapFactory.decodeByteArray(resolved.bytes, 0, resolved.bytes.size, options)
    }

    private sealed interface Resolved {
        data class Path(val path: String) : Resolved

        data class Bytes(val bytes: ByteArray) : Resolved
    }

    private companion object {
        const val PartMaxAgeMillis = 60L * 60L * 1000L

        fun isRemote(source: String): Boolean =
            source.startsWith("http://", ignoreCase = true) ||
                source.startsWith("https://", ignoreCase = true) ||
                source.startsWith("//")

        fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
    }
}
