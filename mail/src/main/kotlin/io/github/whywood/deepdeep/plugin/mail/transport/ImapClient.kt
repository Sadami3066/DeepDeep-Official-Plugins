package io.github.whywood.deepdeep.plugin.mail.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import javax.net.SocketFactory
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal data class ImapEndpoint(
    val host: String,
    val port: Int,
    val tls: Boolean = true,
)

internal data class ImapCredentials(
    val email: String,
    val password: String,
)

internal data class ImapBodyTarget(
    val uid: Long,
    val file: File,
    val declaredSize: Long = 0L,
)

internal sealed interface ImapBodyOutcome {
    data class Saved(val uid: Long, val size: Long) : ImapBodyOutcome

    data class Skipped(
        val uid: Long,
        val size: Long,
        val reason: ImapBodySkip,
    ) : ImapBodyOutcome
}

internal enum class ImapBodySkip {
    TOO_LARGE,
    NOT_FOUND,
    EMPTY,
}

internal class ImapClient(
    private val endpoint: ImapEndpoint,
    private val credentials: ImapCredentials,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val socketFactory: SocketFactory = defaultSocketFactory(endpoint.tls),
) : Closeable {
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var reader: ImapResponseReader? = null
    private var tagIndex = 0
    private var capabilities: Set<String>? = null
    private var authenticated = false
    private var literalHandler: ImapLiteralHandler? = null

    fun connect() {
        if (socket != null) return
        openSocket()
        readGreeting()
    }

    fun login() {
        if (authenticated) return
        val advertised = capability()
        if (advertised.contains("LOGINDISABLED")) {
            if (!advertised.contains("AUTH=PLAIN")) {
                throw MailAuthenticationException("login disabled")
            }
            authenticatePlain()
        } else {
            val responses = execute("LOGIN ${ImapCodec.quote(credentials.email)} ${ImapCodec.quote(credentials.password)}")
            val tagged = responses.filterIsInstance<ImapResponse.Tagged>().lastOrNull()
                ?: throw MailProtocolException("LOGIN: no response")
            if (tagged.status != "OK") {
                if (ImapParse.isAuthenticationFailure(tagged)) {
                    throw MailAuthenticationException(ImapParse.errorDetail(tagged))
                }
                throw MailServerException(ImapParse.errorDetail(tagged))
            }
        }
        authenticated = true
        capabilities = null
    }

    fun capability(): Set<String> {
        capabilities?.let { return it }
        val responses = execute("CAPABILITY")
        val advertised = responses.filterIsInstance<ImapResponse.Untagged>()
            .flatMap { ImapParse.capabilities(it) }
            .toSet()
        capabilities = advertised
        return advertised
    }

    fun listFolders(): List<ImapFolder> {
        val responses = execute("LIST \"\" \"*\"")
        requireOk(responses, "LIST")
        return responses.filterIsInstance<ImapResponse.Untagged>().mapNotNull { ImapParse.folder(it) }
    }

    fun select(folder: String, readOnly: Boolean): ImapMailboxStatus {
        val responses = execute("${if (readOnly) "EXAMINE" else "SELECT"} ${ImapCodec.quoteMailbox(folder)}")
        requireOk(responses, if (readOnly) "EXAMINE" else "SELECT")
        return ImapParse.status(responses)
    }

    fun uidSearch(criteria: String): List<Long> {
        val responses = execute("UID SEARCH $criteria")
        requireOk(responses, "SEARCH")
        return responses.filterIsInstance<ImapResponse.Untagged>().flatMap { ImapParse.search(it) }
    }

    fun searchAll(): List<Long> = uidSearch("ALL").sorted()

    fun searchNewUids(lastUid: Long): List<Long> {
        val uids = uidSearch("UID ${lastUid + 1}:*")
        return uids.filter { it > lastUid }.sorted()
    }

    fun fetchHeaders(uids: Collection<Long>): List<ImapFetchResult> = fetch(
        uids,
        "UID FLAGS INTERNALDATE RFC822.SIZE ENVELOPE " +
            "BODY.PEEK[HEADER.FIELDS (DATE FROM TO CC SUBJECT MESSAGE-ID REFERENCES IN-REPLY-TO CONTENT-TYPE)]",
    )

    fun fetchBodiesToFiles(targets: List<ImapBodyTarget>, maxBytesPerMessage: Long = DEFAULT_BODY_LIMIT_BYTES): List<ImapBodyOutcome> {
        if (targets.isEmpty()) return emptyList()
        val outcomes = mutableListOf<ImapBodyOutcome>()
        for (target in targets.sortedBy { it.uid }) {
            if (maxBytesPerMessage > 0 && target.declaredSize > maxBytesPerMessage) {
                outcomes += ImapBodyOutcome.Skipped(target.uid, target.declaredSize, ImapBodySkip.TOO_LARGE)
                continue
            }
            outcomes += fetchBodyToFile(target, maxBytesPerMessage)
        }
        return outcomes
    }

    fun fetchFlags(uids: Collection<Long>): Map<Long, Set<String>> = fetch(uids, "UID FLAGS")
        .mapNotNull { result ->
            val uid = result.uid ?: return@mapNotNull null
            val flags = result.flags ?: return@mapNotNull null
            uid to flags
        }
        .toMap()

    fun storeSeen(uid: Long, seen: Boolean) {
        val operation = if (seen) "+FLAGS" else "-FLAGS"
        requireOk(execute("UID STORE $uid $operation (\\Seen)"), "STORE")
    }

    fun move(uid: Long, targetFolder: String) {
        if (capability().contains("MOVE")) {
            requireOk(execute("UID MOVE $uid ${ImapCodec.quoteMailbox(targetFolder)}"), "MOVE")
            return
        }
        requireOk(execute("UID COPY $uid ${ImapCodec.quoteMailbox(targetFolder)}"), "COPY")
        delete(uid)
    }

    fun delete(uid: Long) {
        requireOk(execute("UID STORE $uid +FLAGS (\\Deleted)"), "STORE")
        if (capability().contains("UIDPLUS")) {
            requireOk(execute("UID EXPUNGE $uid"), "EXPUNGE")
        } else {
            requireOk(execute("EXPUNGE"), "EXPUNGE")
        }
    }

    fun append(folder: String, flags: List<String>, message: ByteArray) {
        val tag = nextTag()
        val flagSection = if (flags.isEmpty()) "" else " (${flags.joinToString(" ")})"
        writeLine("$tag APPEND ${ImapCodec.quoteMailbox(folder)}$flagSection {${message.size}}")
        while (true) {
            when (val response = readResponse()) {
                is ImapResponse.Continuation -> break
                is ImapResponse.Tagged -> {
                    requireOk(listOf(response), "APPEND")
                    return
                }
                is ImapResponse.Untagged -> if (isBye(response)) throw MailConnectionLostException()
            }
        }
        val stream = outputStream()
        stream.write(message)
        stream.write(CRLF)
        stream.flush()
        requireOk(readUntilTagged(tag), "APPEND")
    }

    fun logout() {
        if (socket == null) return
        runCatching { requireOk(execute("LOGOUT"), "LOGOUT") }
        close()
    }

    override fun close() {
        val target = socket
        socket = null
        output = null
        reader = null
        authenticated = false
        capabilities = null
        if (target != null) runCatching { target.close() }
    }

    private fun fetchBodyToFile(target: ImapBodyTarget, maxBytesPerMessage: Long): ImapBodyOutcome {
        val file = target.file
        val part = File(file.parentFile, file.name + PART_SUFFIX)
        file.parentFile?.mkdirs()
        part.delete()
        var stream: OutputStream? = null
        var saved = false
        literalHandler = ImapLiteralHandler { _, preceding ->
            if (stream == null && preceding.contains("BODY[]", ignoreCase = true)) {
                BufferedOutputStream(FileOutputStream(part), BODY_WRITE_BUFFER_SIZE).also { stream = it }
            } else {
                null
            }
        }
        try {
            val responses = try {
                execute("UID FETCH ${target.uid} (UID BODY.PEEK[])")
            } catch (error: MailServerException) {
                return ImapBodyOutcome.Skipped(target.uid, target.declaredSize, ImapBodySkip.NOT_FOUND)
            }
            requireOk(responses, "FETCH")
            stream?.flush()
            stream?.close()
            stream = null
            val size = part.length()
            if (size <= 0L) return ImapBodyOutcome.Skipped(target.uid, size, ImapBodySkip.EMPTY)
            if (maxBytesPerMessage > 0 && size > maxBytesPerMessage) {
                return ImapBodyOutcome.Skipped(target.uid, size, ImapBodySkip.TOO_LARGE)
            }
            file.delete()
            if (!part.renameTo(file)) throw MailStorageException("cannot cache message body")
            saved = true
            return ImapBodyOutcome.Saved(target.uid, size)
        } finally {
            literalHandler = null
            runCatching { stream?.close() }
            if (!saved) part.delete()
        }
    }

    private fun fetch(uids: Collection<Long>, attributes: String): List<ImapFetchResult> {
        if (uids.isEmpty()) return emptyList()
        val merged = LinkedHashMap<String, ImapFetchResult>()
        for (page in uids.toSortedSet().chunked(FETCH_PAGE_SIZE)) {
            val responses = execute("UID FETCH ${ImapCodec.uidSet(page)} ($attributes)")
            requireOk(responses, "FETCH")
            for (response in responses.filterIsInstance<ImapResponse.Untagged>()) {
                val result = ImapParse.fetch(response) ?: continue
                val key = result.uid?.toString() ?: result.sequence?.toString() ?: continue
                merged[key] = ImapParse.merge(merged[key], result)
            }
        }
        return merged.values.toList()
    }

    private fun authenticatePlain() {
        val tag = nextTag()
        writeLine("$tag AUTHENTICATE PLAIN")
        var continuation = false
        while (!continuation) {
            when (val response = readResponse()) {
                is ImapResponse.Continuation -> continuation = true
                is ImapResponse.Tagged -> {
                    if (ImapParse.isAuthenticationFailure(response)) {
                        throw MailAuthenticationException(ImapParse.errorDetail(response))
                    }
                    throw MailServerException(ImapParse.errorDetail(response))
                }
                is ImapResponse.Untagged -> if (isBye(response)) throw MailConnectionLostException()
            }
        }
        val secret = "\u0000${credentials.email}\u0000${credentials.password}".toByteArray(Charsets.UTF_8)
        writeLine(Base64.getEncoder().encodeToString(secret))
        val tagged = readUntilTagged(tag).filterIsInstance<ImapResponse.Tagged>().lastOrNull()
            ?: throw MailProtocolException("AUTHENTICATE: no response")
        if (tagged.status != "OK") {
            if (ImapParse.isAuthenticationFailure(tagged)) {
                throw MailAuthenticationException(ImapParse.errorDetail(tagged))
            }
            throw MailServerException(ImapParse.errorDetail(tagged))
        }
    }

    private fun execute(command: String): List<ImapResponse> {
        val tag = nextTag()
        writeLine("$tag $command")
        return readUntilTagged(tag)
    }

    private fun readUntilTagged(tag: String): List<ImapResponse> {
        val responses = mutableListOf<ImapResponse>()
        while (true) {
            when (val response = readResponse()) {
                is ImapResponse.Tagged -> {
                    responses += response
                    if (response.tag == tag) return responses
                }
                is ImapResponse.Untagged -> {
                    responses += response
                    if (isBye(response)) throw MailConnectionLostException()
                }
                is ImapResponse.Continuation -> throw MailProtocolException("unexpected continuation")
            }
        }
    }

    private fun requireOk(responses: List<ImapResponse>, operation: String) {
        val tagged = responses.filterIsInstance<ImapResponse.Tagged>().lastOrNull()
            ?: throw MailProtocolException("$operation: no response")
        if (tagged.status == "OK") return
        throw MailServerException(ImapParse.errorDetail(tagged) ?: operation)
    }

    private fun readGreeting() {
        val response = readResponse()
        if (response !is ImapResponse.Untagged) throw MailProtocolException("unexpected greeting")
        val keyword = (response.tokens.firstOrNull() as? ImapToken.Atom)?.value?.uppercase()
        when (keyword) {
            "PREAUTH" -> authenticated = true
            "OK" -> Unit
            "BYE" -> throw MailConnectionLostException()
            else -> throw MailProtocolException("unexpected greeting")
        }
        val advertised = ImapParse.capabilities(response)
        if (advertised.isNotEmpty()) capabilities = advertised
    }

    private fun openSocket() {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            plain.soTimeout = timeoutMs
            plain.tcpNoDelay = true
            val connected = if (endpoint.tls) secure(plain) else plain
            socket = connected
            val stream = BufferedInputStream(connected.getInputStream())
            output = BufferedOutputStream(connected.getOutputStream())
            reader = ImapResponseReader(stream) { literalHandler }
        } catch (error: SocketTimeoutException) {
            closeQuietly(plain)
            throw MailTimeoutException(endpoint.host, error)
        } catch (error: MailTlsException) {
            closeQuietly(plain)
            throw error
        } catch (error: SSLException) {
            closeQuietly(plain)
            throw MailTlsException(endpoint.host, error)
        } catch (error: IOException) {
            closeQuietly(plain)
            throw MailNetworkException(endpoint.host, error)
        }
    }

    private fun secure(plain: Socket): Socket {
        val factory = socketFactory as? SSLSocketFactory
            ?: throw MailTlsException(endpoint.host)
        val ssl = factory.createSocket(plain, endpoint.host, endpoint.port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.soTimeout = timeoutMs
        val parameters = ssl.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = parameters
        ssl.startHandshake()
        return ssl
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

    private fun readResponse(): ImapResponse {
        val source = reader ?: throw MailConnectionLostException()
        return try {
            source.readResponse()
        } catch (error: SocketTimeoutException) {
            throw MailTimeoutException(endpoint.host, error)
        } catch (error: MailConnectionLostException) {
            throw error
        } catch (error: java.net.SocketException) {
            throw MailConnectionLostException(error)
        }
    }

    private fun responseReader(): ImapResponseReader =
        reader ?: throw MailConnectionLostException()

    private fun outputStream(): BufferedOutputStream =
        output ?: throw MailConnectionLostException()

    private fun nextTag(): String {
        tagIndex += 1
        return "A%04d".format(tagIndex)
    }

    private fun isBye(response: ImapResponse.Untagged): Boolean =
        (response.tokens.firstOrNull() as? ImapToken.Atom)?.value?.equals("BYE", ignoreCase = true) == true

    private fun closeQuietly(target: Socket) {
        runCatching { target.close() }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val FETCH_PAGE_SIZE = 50
        const val BODY_WRITE_BUFFER_SIZE = 64 * 1024
        const val PART_SUFFIX = ".part"
        const val DEFAULT_BODY_LIMIT_BYTES = 64L * 1024 * 1024
        val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())

        fun defaultSocketFactory(tls: Boolean): SocketFactory =
            if (tls) SSLSocketFactory.getDefault() as SocketFactory else SocketFactory.getDefault()
    }
}
