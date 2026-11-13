package io.github.whywood.deepdeep.plugin.mail.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal enum class SmtpSecurity {
    IMPLICIT_TLS,
    STARTTLS,
    PLAIN,
}

internal data class SmtpEndpoint(
    val host: String,
    val port: Int,
    val security: SmtpSecurity? = null,
) {
    val effectiveSecurity: SmtpSecurity
        get() = security ?: if (port == 465) SmtpSecurity.IMPLICIT_TLS else SmtpSecurity.STARTTLS
}

internal data class SmtpCredentials(
    val email: String,
    val password: String,
)

internal data class SmtpResponse(
    val code: Int,
    val lines: List<String>,
) {
    val text: String get() = lines.joinToString(" ").trim()
}

internal class SmtpClient(
    private val endpoint: SmtpEndpoint,
    private val credentials: SmtpCredentials,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : Closeable {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var capabilities: List<String>? = null

    fun connect() {
        if (socket != null) return
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            plain.soTimeout = timeoutMs
            plain.tcpNoDelay = true
        } catch (error: SocketTimeoutException) {
            closeQuietly(plain)
            throw MailTimeoutException(endpoint.host, error)
        } catch (error: IOException) {
            closeQuietly(plain)
            throw MailNetworkException(endpoint.host, error)
        }
        val connected = if (endpoint.effectiveSecurity == SmtpSecurity.IMPLICIT_TLS) secure(plain) else plain
        socket = connected
        input = BufferedInputStream(connected.getInputStream())
        output = BufferedOutputStream(connected.getOutputStream())
        expect(readResponse(), 220)
        ehlo()
        if (endpoint.effectiveSecurity == SmtpSecurity.STARTTLS) {
            if (!hasCapability("STARTTLS")) throw MailServerException("starttls is not offered")
            expect(command("STARTTLS"), 220)
            socket = secure(connected)
            val upgraded = socket ?: throw MailConnectionLostException()
            input = BufferedInputStream(upgraded.getInputStream())
            output = BufferedOutputStream(upgraded.getOutputStream())
            capabilities = null
            ehlo()
        }
    }

    fun login() {
        val mechanisms = authMechanisms()
        when {
            mechanisms.contains("PLAIN") -> authPlain()
            mechanisms.contains("LOGIN") -> authLogin()
            else -> throw MailAuthenticationException("no supported auth mechanism")
        }
    }

    fun send(from: String, recipients: List<String>, message: ByteArray): String =
        sendData(from, recipients) { output -> output.write(message) }

    fun sendFile(from: String, recipients: List<String>, file: File): String =
        sendData(from, recipients) { output ->
            try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            } catch (error: IOException) {
                if (error is java.net.SocketException) throw error
                throw MailStorageException("cannot read outgoing message", error)
            }
        }

    private fun sendData(from: String, recipients: List<String>, produce: (OutputStream) -> Unit): String {
        expect(command("MAIL FROM:<$from>"), 250)
        for (recipient in recipients) {
            val response = command("RCPT TO:<$recipient>")
            if (response.code != 250 && response.code != 251) {
                throw MailServerException("recipient rejected: ${response.text}")
            }
        }
        expect(command("DATA"), 354)
        val stream = outputStream()
        val stuffing = DotStuffingOutputStream(stream)
        try {
            produce(stuffing)
            stuffing.finish()
        } catch (error: java.net.SocketException) {
            throw MailConnectionLostException(error)
        }
        val response = readResponse()
        expect(response, 250)
        return response.text
    }

    fun quit() {
        if (socket == null) return
        runCatching { command("QUIT") }
        close()
    }

    override fun close() {
        val target = socket
        socket = null
        input = null
        output = null
        capabilities = null
        if (target != null) runCatching { target.close() }
    }

    private fun ehlo() {
        val domain = credentials.email.substringAfter('@', "localhost").ifBlank { "localhost" }
        val response = command("EHLO $domain")
        expect(response, 250)
        capabilities = response.lines.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun hasCapability(name: String): Boolean =
        capabilities.orEmpty().any { it.equals(name, ignoreCase = true) || it.startsWith("$name ", ignoreCase = true) }

    private fun authMechanisms(): Set<String> {
        val result = mutableSetOf<String>()
        for (line in capabilities.orEmpty()) {
            val normalized = when {
                line.startsWith("AUTH=", ignoreCase = true) -> line.substring(5)
                line.startsWith("AUTH ", ignoreCase = true) -> line.substring(5)
                else -> continue
            }
            result += normalized.split(' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        }
        return result
    }

    private fun authPlain() {
        val secret = "\u0000${credentials.email}\u0000${credentials.password}".toByteArray(Charsets.UTF_8)
        val token = Base64.getEncoder().encodeToString(secret)
        val response = command("AUTH PLAIN $token")
        if (response.code == 334) {
            expect(command(token), 235)
        } else {
            expect(response, 235)
        }
    }

    private fun authLogin() {
        expect(command("AUTH LOGIN"), 334)
        expect(command(Base64.getEncoder().encodeToString(credentials.email.toByteArray(Charsets.UTF_8))), 334)
        expect(
            command(Base64.getEncoder().encodeToString(credentials.password.toByteArray(Charsets.UTF_8))),
            235,
        )
    }

    private fun command(line: String): SmtpResponse {
        writeLine(line)
        return readResponse()
    }

    private fun expect(response: SmtpResponse, vararg codes: Int) {
        if (response.code in codes) return
        if (response.code == 530 || response.code == 534 || response.code == 535) {
            throw MailAuthenticationException(response.text)
        }
        throw MailServerException(response.text)
    }

    private fun readResponse(): SmtpResponse {
        val source = input
        if (source == null) throw MailConnectionLostException()
        return try {
            readResponseFrom(source)
        } catch (error: SocketTimeoutException) {
            throw MailTimeoutException(endpoint.host, error)
        } catch (error: java.net.SocketException) {
            throw MailConnectionLostException(error)
        }
    }

    private fun readResponseFrom(source: BufferedInputStream): SmtpResponse {
        val lines = mutableListOf<String>()
        var code = 0
        while (true) {
            val line = readLineFrom(source) ?: throw MailConnectionLostException()
            if (line.length < 3) throw MailProtocolException("malformed smtp response")
            code = line.substring(0, 3).toIntOrNull() ?: throw MailProtocolException("malformed smtp response")
            lines += line.drop(4)
            if (line.getOrNull(3) != '-') break
        }
        return SmtpResponse(code, lines)
    }

    private fun readLineFrom(source: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = source.read()
            if (byte == -1) {
                if (buffer.size() == 0) return null
                throw MailConnectionLostException()
            }
            if (byte == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return decodeText(bytes.copyOf(length))
            }
            buffer.write(byte)
            if (buffer.size() > MAX_LINE_BYTES) throw MailProtocolException("smtp line too long")
        }
    }

    private fun writeLine(line: String) {
        val stream = outputStream()
        try {
            stream.write(line.toByteArray(Charsets.UTF_8))
            stream.write(CRLF)
            stream.flush()
        } catch (error: java.net.SocketException) {
            throw MailConnectionLostException(error)
        }
    }

    private class DotStuffingOutputStream(private val sink: BufferedOutputStream) : OutputStream() {
        private var lineStart = true
        private var lastByte = -1

        override fun write(byte: Int) {
            val value = byte and 0xff
            if (lineStart && value == DOT) sink.write(DOT)
            sink.write(value)
            lineStart = value == LF
            lastByte = value
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var index = offset
            val end = offset + length
            while (index < end) {
                if (lineStart) {
                    if ((bytes[index].toInt() and 0xff) == DOT) sink.write(DOT)
                    lineStart = false
                }
                var newline = -1
                var cursor = index
                while (cursor < end) {
                    if ((bytes[cursor].toInt() and 0xff) == LF) {
                        newline = cursor
                        break
                    }
                    cursor += 1
                }
                if (newline < 0) {
                    sink.write(bytes, index, end - index)
                    lastByte = bytes[end - 1].toInt() and 0xff
                    index = end
                } else {
                    sink.write(bytes, index, newline - index + 1)
                    lastByte = LF
                    lineStart = true
                    index = newline + 1
                }
            }
        }

        fun finish() {
            if (lastByte != LF) sink.write(CR)
            sink.write(DOT)
            sink.write(CR)
            sink.write(LF)
            sink.flush()
        }
    }

    private fun secure(plain: Socket): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        try {
            val ssl = factory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
            ssl.useClientMode = true
            ssl.soTimeout = timeoutMs
            val parameters = ssl.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = parameters
            ssl.startHandshake()
            return ssl
        } catch (error: SSLException) {
            closeQuietly(plain)
            throw MailTlsException(endpoint.host, error)
        } catch (error: IOException) {
            closeQuietly(plain)
            throw MailNetworkException(endpoint.host, error)
        }
    }

    private fun outputStream(): BufferedOutputStream =
        output ?: throw MailConnectionLostException()

    private fun closeQuietly(target: Socket) {
        runCatching { target.close() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val MAX_LINE_BYTES = 64 * 1024
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val DOT = '.'.code
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    }
}
