package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.SecretService

internal class MiCloudCookieStore(private val secrets: SecretService) {
    suspend fun read(): String? = secrets.get(COOKIE_KEY)?.takeIf { it.isNotBlank() }

    suspend fun save(cookie: String) {
        secrets.put(COOKIE_KEY, cookie.trim())
    }

    suspend fun clear() {
        secrets.remove(COOKIE_KEY)
    }

    suspend fun userId(): String? = read()?.let { parseCookie(it)[USER_ID] }

    suspend fun serviceToken(): String? = read()?.let { parseCookie(it)[SERVICE_TOKEN] }

    private companion object {
        const val COOKIE_KEY = "cookie"
        const val USER_ID = "userId"
        const val SERVICE_TOKEN = "serviceToken"
    }
}
