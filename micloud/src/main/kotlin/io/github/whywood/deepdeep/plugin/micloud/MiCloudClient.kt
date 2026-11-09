package io.github.whywood.deepdeep.plugin.micloud

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class MiCloudAuthException(message: String) : Exception(message)

internal class MiCloudClient(
    private val cookieProvider: suspend () -> String?,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val onAuthExpired: () -> Unit = {},
) {
    suspend fun userId(): String? = cookieProvider()?.let { parseCookie(it)[USER_ID] }

    suspend fun getJson(pathWithQuery: String): JsonElement = withContext(Dispatchers.IO) {
        val cookie = requireCookie()
        val connection = open(pathWithQuery)
        try {
            connection.setRequestProperty("Cookie", cookie)
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                authExpired()
            }
            if (status !in 200..299) throw IOException("HTTP $status")
            if (connection.url.host != baseHost()) authExpired()
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            val json = runCatching { Json.parseToJsonElement(body) }.getOrNull()
                ?: throw IOException("Mi Cloud returned non-JSON content")
            if (isMiCloudAuthFailure(json)) authExpired()
            json
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(pathWithQuery: String, dest: File): File = withContext(Dispatchers.IO) {
        val cookie = requireCookie()
        val connection = open(pathWithQuery, accept = "*/*")
        try {
            connection.setRequestProperty("Cookie", cookie)
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
                authExpired()
            }
            if (status !in 200..299) throw IOException("HTTP $status")
            if (connection.url.host != baseHost()) authExpired()
            val target = dest.absoluteFile
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "${target.name}.part")
            try {
                connection.inputStream.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            } finally {
                temp.delete()
            }
            target
        } finally {
            connection.disconnect()
        }
    }

    suspend fun profile(): MiCloudProfile {
        val json = getJson("/status/lite/profile?ts=${System.currentTimeMillis()}")
        return parseProfile(json) ?: throw IOException("Mi Cloud account response format is invalid")
    }

    private suspend fun requireCookie(): String = cookieProvider()?.takeIf { it.isNotBlank() }
        ?: throw MiCloudAuthException("Not signed in to Mi Cloud")

    private fun authExpired(): Nothing {
        onAuthExpired()
        throw MiCloudAuthException(AUTH_EXPIRED)
    }

    private fun baseHost(): String = runCatching { URL(baseUrl).host }.getOrDefault("i.mi.com")

    private fun open(pathWithQuery: String, accept: String = "application/json, text/plain, */*"): HttpURLConnection {
        val path = if (pathWithQuery.startsWith("/")) pathWithQuery else "/$pathWithQuery"
        val url = URL(baseUrl + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Referer", "$baseUrl/")
        }
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://i.mi.com"
        const val USER_ID = "userId"
        const val AUTH_EXPIRED = "Mi Cloud login expired; sign in again"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
