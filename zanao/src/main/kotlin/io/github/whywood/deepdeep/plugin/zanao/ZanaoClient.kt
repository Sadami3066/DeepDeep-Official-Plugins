package io.github.whywood.deepdeep.plugin.zanao

import io.github.whywood.deepdeep.plugin.api.PluginHost
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ZanaoCategory(
    val id: String,
    val name: String,
    val summary: String,
)

internal data class ZanaoThread(
    val id: String,
    val title: String,
    val category: String,
    val time: String,
    val likes: String,
    val comments: String,
    val nickname: String,
    val content: String = "",
)

internal class ZanaoException(message: String) : Exception(message)

internal object ZanaoClient {
    private const val BASE = "http://api.app.zanao.com"
    private const val VERSION = "3.1.0"
    private const val CODEBOOK = "HySuiITldDszAQ9ZCx2pkePtE0qgUr_JYn1KNjO-afc4b7M658BFGLRVWXhmovw3"
    private const val AES_KEY = "6E7D41B229B5E8H4"
    private const val AES_IV = "A8Y9L2Z8N6A1H3Q2"
    private val random = SecureRandom()

    suspend fun feed(host: PluginHost, limit: Int = 20): List<ZanaoThread> {
        val data = request(host, "/thread/v2/list", linkedMapOf(
            "cate_id" to "latest",
            "from_time" to "0",
            "with_comment" to "true",
            "with_reply" to "true",
        ), "GET")
        val list = data.optJSONArray("list") ?: JSONArray()
        return buildList {
            for (index in 0 until minOf(list.length(), limit)) {
                val item = list.optJSONObject(index) ?: continue
                add(ZanaoThread(
                    id = item.optString("thread_id"),
                    title = item.optString("title"),
                    category = item.optString("cate_name"),
                    time = item.optString("post_time"),
                    likes = item.optString("l_count"),
                    comments = item.optString("c_count"),
                    nickname = item.optString("nickname"),
                ))
            }
        }
    }

    suspend fun detail(host: PluginHost, threadId: String): ZanaoThread {
        val data = request(host, "/thread/info", linkedMapOf(
            "id" to threadId,
            "from" to "post",
        ), "GET")
        val item = data.optJSONObject("detail") ?: data
        return ZanaoThread(
            id = item.optString("thread_id", threadId),
            title = item.optString("title"),
            category = item.optString("cate_name"),
            time = item.optString("post_time"),
            likes = item.optString("l_count"),
            comments = item.optString("c_count"),
            nickname = item.optString("nickname"),
            content = item.optString("content"),
        )
    }

    suspend fun categories(host: PluginHost): List<ZanaoCategory> {
        val data = request(host, "/catelist", linkedMapOf(
            "from" to "post",
            "is_cross" to "0",
            "cross_all" to "1",
        ), "POST")
        val list = data.optJSONArray("cate_list") ?: JSONArray()
        return buildList {
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                add(ZanaoCategory(
                    id = item.optString("cate_id"),
                    name = item.optString("name"),
                    summary = item.optString("summary"),
                ))
            }
        }
    }

    suspend fun post(
        host: PluginHost,
        title: String,
        content: String,
        anonymous: Boolean,
        cateId: String,
    ): String {
        val data = request(host, "/thread/post", linkedMapOf(
            "cate_id" to cateId,
            "contact_person" to "",
            "contact_phone" to "",
            "contact_qq" to "",
            "contact_wx" to "",
            "title" to title,
            "content" to content,
            "img_paths" to "",
            "cert_show" to "0",
            "is_comment_close" to "0",
            "use_anon" to if (anonymous) "1" else "0",
            "reward_price" to "0",
            "top_price" to "0",
            "post_price" to "0",
            "color_price" to "0",
        ), "POST")
        return data.optString("thread_id")
    }

    internal fun sign(params: Map<String, String>): String {
        val items = params.entries
            .filter { it.key != "key" }
            .sortedBy { it.key }
        val query = items.joinToString("&") { "${it.key}=${it.value}" }
        val secret = secretKey(
            params["randnum__"].orEmpty(),
            params["timestamp__"].orEmpty(),
        )
        return md5(query + "&secretkey=" + secret).uppercase()
    }

    private fun secretKey(randnum: String, timestamp: String): String {
        val encoded = codebookBase64((randnum + timestamp).toByteArray(Charsets.UTF_8))
        val digest = md5(encoded)
        val mixed = ByteArray(encoded.length) { index ->
            ((encoded[index].code + digest[index % digest.length].code) and 0xFF).toByte()
        }
        return Base64.getEncoder().encodeToString(mixed)
    }

    private fun codebookBase64(data: ByteArray): String {
        val out = StringBuilder()
        var index = 0
        while (index < data.size) {
            val remaining = minOf(3, data.size - index)
            var value = 0
            repeat(3) { offset ->
                value = value shl 8
                if (offset < remaining) value = value or (data[index + offset].toInt() and 0xFF)
            }
            val indexes = intArrayOf(
                (value shr 18) and 63,
                (value shr 12) and 63,
                (value shr 6) and 63,
                value and 63,
            )
            repeat(remaining + 1) { out.append(CODEBOOK[indexes[it]]) }
            repeat(3 - remaining) { out.append('=') }
            index += 3
        }
        return out.toString()
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private suspend fun request(
        host: PluginHost,
        path: String,
        data: LinkedHashMap<String, String>,
        method: String,
    ): JSONObject {
        val token = host.secrets().get(SECRET_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: throw ZanaoException(host.t("尚未导入集市账号", "Zanao account is not imported"))
        val device = host.secrets().get(SECRET_DEVICE).orEmpty()
        val alias = host.settings().get(SETTING_ALIAS)?.takeIf { it.isNotBlank() } ?: DEFAULT_ALIAS
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val randnum = buildString { repeat(20) { append(random.nextInt(10)) } }
        val signed = LinkedHashMap(data).apply {
            put("timestamp__", timestamp)
            put("randnum__", randnum)
        }
        val signature = sign(signed)
        val body = data.entries.joinToString("&") {
            "${encode(it.key)}=${encode(it.value)}"
        }
        val url = if (method == "GET") {
            BASE + path + if (body.isEmpty()) "" else "?$body"
        } else {
            BASE + path
        }
        val headers = linkedMapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sc-Alias" to alias,
            "X-Sc-Token" to token,
            "X-Sc-Device" to device,
            "X-Sc-Client" to "app",
            "X-Sc-Platform" to "Android",
            "X-Sc-Hb-V" to "210",
            "X-Sc-Nt-V" to "310",
            "X-Sc-Version" to VERSION,
            "X-Sc-Nd" to randnum,
            "X-Sc-Td" to timestamp,
            "X-Sc-Ah" to signature,
            "Content-Type" to "application/x-www-form-urlencoded",
        )
        val response = host.network().request(
            method = method,
            url = url,
            headers = headers,
            body = if (method == "GET") null else body,
        )
        if (response.statusCode !in 200..299) {
            throw ZanaoException(host.tf("接口 HTTP %d", "HTTP %d", response.statusCode))
        }
        val text = response.body.trim().takeIf { it.startsWith("{") }
            ?: decrypt(response.body.trim())
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw ZanaoException(host.t("接口返回非 JSON", "API returned non-JSON data"))
        }
        val errno = root.optInt("errno", 0)
        if (errno != 0) {
            throw ZanaoException(root.optString("errmsg").ifBlank { host.tf("接口错误 %d", "API error %d", errno) })
        }
        return root.optJSONObject("data") ?: JSONObject()
    }

    private fun decrypt(value: String): String {
        val payload = Base64.getDecoder().decode(value)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8)),
        )
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
