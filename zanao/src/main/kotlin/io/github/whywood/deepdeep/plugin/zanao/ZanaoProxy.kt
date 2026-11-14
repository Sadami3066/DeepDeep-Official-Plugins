package io.github.whywood.deepdeep.plugin.zanao

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal data class CapturedSession(
    val token: String,
    val device: String?,
    val alias: String?,
)

internal class ZanaoProxy(
    private val onSession: (CapturedSession) -> Unit,
) {
    private val captured = AtomicBoolean(false)
    private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
    @Volatile
    private var server: ServerSocket? = null
    @Volatile
    private var thread: Thread? = null

    val port: Int
        get() = server?.localPort ?: 0

    fun start(): Int {
        check(server == null) { "proxy already started" }
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = socket
        thread = Thread({
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    clients.add(client)
                    Thread({ handle(client) }, "zanao-proxy-client").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (_: Throwable) {
            }
        }, "zanao-proxy").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        thread?.interrupt()
        thread = null
        synchronized(clients) {
            clients.toList().forEach { runCatching { it.close() } }
            clients.clear()
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            if (lines.isEmpty()) return
            val request = lines.first().split(" ")
            if (request.size < 3) return
            val method = request[0]
            val target = request[1]
            val version = request[2]

            var host: String? = null
            var port = 80
            var token: String? = null
            var device: String? = null
            var alias: String? = null
            val headers = mutableListOf<String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                when (name.lowercase()) {
                    "host" -> host = value
                    "x-sc-token" -> token = value
                    "x-sc-device" -> device = value
                    "x-sc-alias" -> alias = value
                    "connection", "proxy-connection", "proxy-authorization" -> Unit
                    else -> headers.add(line)
                }
            }
            if (token != null && token.isNotBlank() && captured.compareAndSet(false, true)) {
                onSession(CapturedSession(token, device, alias))
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(client, host ?: target, input)
                return
            }

            val uri = runCatching {
                if (target.startsWith("http://") || target.startsWith("https://")) URI(target)
                else URI("http://${host ?: ""}$target")
            }.getOrNull() ?: return
            host = uri.host ?: host ?: return
            if (uri.port > 0) port = uri.port
            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append('?').append(it) }
            }

            var contentLength = 0L
            for (line in headers) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
            }
            val body = readN(input, contentLength.coerceAtMost(8L * 1024 * 1024).toInt())

            Socket().use { upstream ->
                upstream.connect(InetSocketAddress(host, port), 10_000)
                upstream.soTimeout = 15_000
                val out = upstream.getOutputStream()
                val builder = StringBuilder()
                builder.append(method).append(' ').append(path).append(' ').append(version).append("\r\n")
                for (line in headers) builder.append(line).append("\r\n")
                builder.append("Connection: close\r\n\r\n")
                out.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
                if (body.isNotEmpty()) out.write(body)
                out.flush()

                val upstreamIn = BufferedInputStream(upstream.getInputStream())
                val responseHead = readHead(upstreamIn) ?: return
                client.getOutputStream().apply {
                    write(responseHead.toByteArray(StandardCharsets.ISO_8859_1))
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = upstreamIn.read(buffer)
                        if (read < 0) break
                        write(buffer, 0, read)
                    }
                    flush()
                }
            }
        } catch (_: Throwable) {
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun handleConnect(client: Socket, authority: String, input: InputStream) {
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "443").toIntOrNull() ?: 443
        Socket().use { upstream ->
            upstream.connect(InetSocketAddress(host, port), 10_000)
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            client.getOutputStream().flush()
            val a = Thread({
                runCatching { input.copyTo(upstream.getOutputStream()) }
                runCatching { upstream.shutdownOutput() }
            }, "zanao-connect-up").apply { isDaemon = true; start() }
            runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
            runCatching { a.join(500) }
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                b == '\r'.code -> 1
                b == '\n'.code && state == 1 -> 2
                b == '\r'.code && state == 2 -> 3
                b == '\n'.code && state == 3 -> return out.toString("ISO-8859-1")
                else -> 0
            }
            if (out.size() > 256 * 1024) break
        }
        return null
    }

    private fun readN(input: InputStream, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(data, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) data else data.copyOf(offset)
    }
}
