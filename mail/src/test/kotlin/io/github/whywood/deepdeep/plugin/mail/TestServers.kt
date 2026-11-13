package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.transport.ImapCodec
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

internal data class FakeMessage(
    val uid: Long,
    var flags: MutableSet<String> = mutableSetOf(),
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val toName: String,
    val toAddress: String,
    val messageId: String,
    val references: String? = null,
    val internalDate: String = "12-Nov-2026 10:00:00 +0800",
    val raw: ByteArray,
)

internal class FakeFolder(
    val name: String,
    val flags: Set<String> = emptySet(),
    var uidValidity: Long = 100,
    val messages: MutableList<FakeMessage> = mutableListOf(),
)

internal class FakeImapServer(
    val folders: MutableList<FakeFolder>,
    private val supportMove: Boolean = true,
    private val supportUidPlus: Boolean = true,
    private val loginUser: String = "user@example.com",
    private val loginPassword: String = "secret",
    private val loginDisabled: Boolean = false,
    private val stallAfterGreeting: Boolean = false,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    private val connections = CopyOnWriteArrayList<Socket>()
    val commands = CopyOnWriteArrayList<String>()
    val port: Int get() = serverSocket.localPort

    private val acceptor = thread(isDaemon = true, name = "fake-imap-accept") {
        try {
            while (true) {
                val socket = serverSocket.accept()
                connections += socket
                thread(isDaemon = true, name = "fake-imap-client") { handle(socket) }
            }
        } catch (error: Exception) {
            Unit
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        connections.forEach { socket -> runCatching { socket.close() } }
    }

    private fun preAuthCapabilities(): String =
        if (loginDisabled) "IMAP4rev1 AUTH=PLAIN LOGINDISABLED" else "IMAP4rev1 AUTH=PLAIN"

    private fun capabilityList(): String = buildString {
        append("IMAP4rev1")
        if (supportMove) append(" MOVE")
        if (supportUidPlus) append(" UIDPLUS")
    }

    private fun handle(socket: Socket) {
        val state = ConnectionState()
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        write(output, "* OK [CAPABILITY ${preAuthCapabilities()}] fake ready\r\n")
        if (stallAfterGreeting) {
            while (!socket.isClosed) Thread.sleep(20)
            return
        }
        while (true) {
            val line = readLine(input) ?: break
            commands += line
            val tag = line.substringBefore(' ', "")
            val command = line.substringAfter(' ', "")
            when {
                command.startsWith("CAPABILITY", ignoreCase = true) -> {
                    val caps = if (state.authenticated) capabilityList() else preAuthCapabilities()
                    write(output, "* CAPABILITY $caps\r\n$tag OK capability done\r\n")
                }
                command.startsWith("LOGIN", ignoreCase = true) -> {
                    val user = quotedArgument(command, 0).orEmpty()
                    val password = quotedArgument(command, 1).orEmpty()
                    if (user == loginUser && password == loginPassword) {
                        state.authenticated = true
                        write(output, "$tag OK login done\r\n")
                    } else {
                        write(output, "$tag NO [AUTHENTICATIONFAILED] invalid credentials\r\n")
                    }
                }
                command.startsWith("AUTHENTICATE PLAIN", ignoreCase = true) -> {
                    write(output, "+ \r\n")
                    val token = readLine(input).orEmpty()
                    val decoded = runCatching { String(Base64.getDecoder().decode(token)) }.getOrNull()
                    if (decoded == "\u0000$loginUser\u0000$loginPassword") {
                        state.authenticated = true
                        write(output, "$tag OK authenticated\r\n")
                    } else {
                        write(output, "$tag NO [AUTHENTICATIONFAILED] invalid credentials\r\n")
                    }
                }
                command.startsWith("LIST", ignoreCase = true) -> {
                    for (folder in folders) {
                        write(output, listResponse(folder))
                    }
                    write(output, "$tag OK list done\r\n")
                }
                command.startsWith("EXAMINE", ignoreCase = true) ||
                    command.startsWith("SELECT", ignoreCase = true) -> {
                    val name = ImapCodec.decodeMailbox(quotedArgument(command, 0).orEmpty())
                    val folder = folders.firstOrNull { it.name == name }
                    if (folder == null) {
                        write(output, "$tag NO no such folder\r\n")
                    } else {
                        state.selected = folder
                        write(
                            output,
                            "* ${folder.messages.size} EXISTS\r\n" +
                                "* OK [UIDVALIDITY ${folder.uidValidity}] uidvalidity\r\n" +
                                "* OK [UIDNEXT ${nextUid(folder)}] uidnext\r\n" +
                                "* FLAGS (\\Seen \\Flagged \\Deleted)\r\n" +
                                "$tag OK [READ-ONLY] selected\r\n",
                        )
                    }
                }
                command.startsWith("UID SEARCH", ignoreCase = true) -> {
                    val folder = state.selected
                    if (folder == null) {
                        write(output, "$tag NO no folder selected\r\n")
                    } else {
                        val uids = when {
                            command.endsWith("ALL", ignoreCase = true) -> folder.messages.map { it.uid }
                            else -> searchRange(command, folder.messages.map { it.uid })
                        }
                        write(output, "* SEARCH ${uids.joinToString(" ")}\r\n$tag OK search done\r\n")
                    }
                }
                command.startsWith("UID FETCH", ignoreCase = true) -> {
                    val folder = state.selected
                    if (folder == null) {
                        write(output, "$tag NO no folder selected\r\n")
                    } else {
                        val body = command.substringAfter("UID FETCH ")
                        val uidSet = body.substringBefore(' ')
                        val attributes = body.substringAfter('(', "")
                        val wanted = expandUidSet(uidSet)
                        val requested = attributes.substringBeforeLast(')')
                        folder.messages.forEachIndexed { index, message ->
                            if (message.uid in wanted) {
                                write(output, fetchResponse(index + 1, message, requested))
                            }
                        }
                        write(output, "$tag OK fetch done\r\n")
                    }
                }
                command.startsWith("UID STORE", ignoreCase = true) -> {
                    val folder = state.selected
                    val uid = command.substringAfter("UID STORE ").substringBefore(' ').toLongOrNull()
                    val message = folder?.messages?.firstOrNull { it.uid == uid }
                    if (folder == null || message == null) {
                        write(output, "$tag NO no such message\r\n")
                    } else {
                        val flags = command.substringAfter('(', "").substringBeforeLast(')')
                        val operation = command.substringAfter("UID STORE ").substringAfter(' ').substringBefore(' ')
                        val values = flags.split(' ').filter { it.isNotBlank() }
                        if (operation.equals("+FLAGS", ignoreCase = true)) {
                            message.flags += values
                        } else {
                            message.flags -= values.toSet()
                        }
                        write(output, "$tag OK store done\r\n")
                    }
                }
                command.startsWith("UID MOVE", ignoreCase = true) -> {
                    val folder = state.selected
                    val rest = command.substringAfter("UID MOVE ")
                    val uid = rest.substringBefore(' ').toLongOrNull()
                    val targetName = ImapCodec.decodeMailbox(quotedArgument(rest, 0).orEmpty())
                    val target = folders.firstOrNull { it.name == targetName }
                    val message = folder?.messages?.firstOrNull { it.uid == uid }
                    if (!supportMove || folder == null || target == null || message == null) {
                        write(output, "$tag NO move not allowed\r\n")
                    } else {
                        folder.messages.remove(message)
                        target.messages += message.copy(uid = nextUid(target))
                        write(output, "$tag OK move done\r\n")
                    }
                }
                command.startsWith("UID COPY", ignoreCase = true) -> {
                    val folder = state.selected
                    val rest = command.substringAfter("UID COPY ")
                    val uid = rest.substringBefore(' ').toLongOrNull()
                    val targetName = ImapCodec.decodeMailbox(quotedArgument(rest, 0).orEmpty())
                    val target = folders.firstOrNull { it.name == targetName }
                    val message = folder?.messages?.firstOrNull { it.uid == uid }
                    if (folder == null || target == null || message == null) {
                        write(output, "$tag NO copy failed\r\n")
                    } else {
                        target.messages += message.copy(uid = nextUid(target))
                        write(output, "$tag OK copy done\r\n")
                    }
                }
                command.startsWith("UID EXPUNGE", ignoreCase = true) -> {
                    val folder = state.selected
                    if (folder == null) {
                        write(output, "$tag NO no folder selected\r\n")
                    } else {
                        folder.messages.removeAll { it.flags.contains("\\Deleted") }
                        write(output, "$tag OK expunge done\r\n")
                    }
                }
                command.startsWith("EXPUNGE", ignoreCase = true) -> {
                    val folder = state.selected
                    if (folder == null) {
                        write(output, "$tag NO no folder selected\r\n")
                    } else {
                        val removed = folder.messages.count { it.flags.contains("\\Deleted") }
                        folder.messages.removeAll { it.flags.contains("\\Deleted") }
                        for (position in 1..removed) write(output, "* $position EXPUNGE\r\n")
                        write(output, "$tag OK expunge done\r\n")
                    }
                }
                command.startsWith("APPEND", ignoreCase = true) -> {
                    val size = LITERAL_SIZE.find(command)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    write(output, "+ ready for literal\r\n")
                    val literal = readExactly(input, size)
                    readLine(input)
                    val targetName = ImapCodec.decodeMailbox(quotedArgument(command, 0).orEmpty())
                    val target = folders.firstOrNull { it.name == targetName }
                    if (target == null) {
                        write(output, "$tag NO no such folder\r\n")
                    } else {
                        target.messages += appendedMessage(target, literal)
                        write(output, "$tag OK append done\r\n")
                    }
                }
                command.startsWith("LOGOUT", ignoreCase = true) -> {
                    write(output, "* BYE logging out\r\n$tag OK logout done\r\n")
                    break
                }
                else -> write(output, "$tag BAD unknown command\r\n")
            }
        }
        runCatching { socket.close() }
    }

    private fun appendedMessage(target: FakeFolder, literal: ByteArray): FakeMessage {
        val headerText = literal.toString(Charsets.ISO_8859_1)
        val rows = headerText.split("\r\n")
        fun header(name: String): String = rows.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        return FakeMessage(
            uid = nextUid(target),
            subject = header("Subject"),
            fromName = "",
            fromAddress = header("From"),
            toName = "",
            toAddress = header("To"),
            messageId = header("Message-ID"),
            raw = literal,
        )
    }

    private fun listResponse(folder: FakeFolder): String {
        val flags = " (${folder.flags.joinToString(" ")})"
        val name = ImapCodec.encodeMailbox(folder.name)
        return if (folder.name.contains("With Space")) {
            val bytes = name.toByteArray()
            "* LIST$flags \"/\" {${bytes.size}}\r\n${String(bytes)}\r\n"
        } else {
            "* LIST$flags \"/\" \"$name\"\r\n"
        }
    }

    private fun fetchResponse(sequence: Int, message: FakeMessage, requested: String): String {
        val attributes = mutableListOf<String>()
        val normalized = requested.uppercase()
        if (normalized.contains("UID")) attributes += "UID ${message.uid}"
        if (normalized.contains("FLAGS")) {
            attributes += "FLAGS (${message.flags.joinToString(" ")})"
        }
        if (normalized.contains("INTERNALDATE")) attributes += "INTERNALDATE \"${message.internalDate}\""
        if (normalized.contains("RFC822.SIZE")) attributes += "RFC822.SIZE ${message.raw.size}"
        if (normalized.contains("ENVELOPE")) attributes += "ENVELOPE ${envelope(message)}"
        val headerBlock = message.raw.toString(Charsets.ISO_8859_1)
            .substringBefore("\r\n\r\n")
            .toByteArray(Charsets.ISO_8859_1)
        val bodyRequested = Regex("BODY(?:\\.PEEK)?\\[\\]").containsMatchIn(normalized)
        val headerRequested = normalized.contains("HEADER.FIELDS")
        val literal: Pair<String, ByteArray>? = when {
            bodyRequested -> "BODY[]" to message.raw
            headerRequested -> "BODY[HEADER.FIELDS (DATE FROM TO CC SUBJECT MESSAGE-ID REFERENCES IN-REPLY-TO CONTENT-TYPE)]" to headerBlock
            else -> null
        }
        val builder = StringBuilder("* $sequence FETCH (")
        builder.append(attributes.joinToString(" "))
        if (literal != null) {
            if (attributes.isNotEmpty()) builder.append(' ')
            builder.append(literal.first).append(" {").append(literal.second.size).append("}\r\n")
            builder.append(String(literal.second, Charsets.ISO_8859_1))
        }
        builder.append(")\r\n")
        return builder.toString()
    }

    private fun envelope(message: FakeMessage): String {
        val from = "((\"${escape(message.fromName)}\" NIL \"${message.fromAddress.substringBefore('@')}\" \"${message.fromAddress.substringAfter('@')}\"))"
        val sender = "((NIL NIL \"${message.fromAddress.substringBefore('@')}\" \"${message.fromAddress.substringAfter('@')}\"))"
        val to = "((\"${escape(message.toName)}\" NIL \"${message.toAddress.substringBefore('@')}\" \"${message.toAddress.substringAfter('@')}\"))"
        val nil = "NIL"
        val references = message.references?.let { "\"$it\"" } ?: nil
        return "(\"${message.internalDate}\" \"${escape(message.subject)}\" $from $sender $from $to $to $nil $references \"${message.messageId}\")"
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun nextUid(folder: FakeFolder): Long = (folder.messages.maxOfOrNull { it.uid } ?: 0L) + 1

    private fun searchRange(command: String, uids: List<Long>): List<Long> {
        val match = Regex("UID (\\d+):\\*").find(command) ?: return emptyList()
        val start = match.groupValues[1].toLong()
        if (uids.isEmpty()) return emptyList()
        val maximum = uids.max()
        if (start > maximum) return listOf(maximum)
        return uids.filter { it >= start }.sorted()
    }

    private fun expandUidSet(value: String): Set<Long> {
        val result = mutableSetOf<Long>()
        for (part in value.split(',')) {
            if (part.isBlank()) continue
            val range = part.split(':')
            if (range.size == 2) {
                val start = range[0].toLongOrNull() ?: continue
                val end = range[1].toLongOrNull() ?: continue
                for (uid in start..end) result += uid
            } else {
                part.toLongOrNull()?.let { result += it }
            }
        }
        return result
    }

    private fun quotedArgument(command: String, index: Int): String? {
        val matches = Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(command).toList()
        val value = matches.getOrNull(index)?.groupValues?.get(1) ?: return null
        return value.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private data class ConnectionState(
        var authenticated: Boolean = false,
        var selected: FakeFolder? = null,
    )

    companion object {
        private val LITERAL_SIZE = Regex("\\{(\\d+)}")

        fun write(output: OutputStream, value: String) {
            output.write(value.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }

        fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
                if (byte == '\n'.code) {
                    val text = buffer.toString("ISO-8859-1")
                    return text.removeSuffix("\r")
                }
                buffer.write(byte)
            }
        }

        fun readExactly(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(bytes, read, size - read)
                if (count < 0) break
                read += count
            }
            return bytes
        }
    }
}

internal class FakeSmtpServer(
    private val expectedUser: String = "user@example.com",
    private val expectedPassword: String = "secret",
    private val advertisedMechanisms: String = "AUTH PLAIN LOGIN",
    private val stallAfterGreeting: Boolean = false,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
    private val connections = CopyOnWriteArrayList<Socket>()
    val commands = CopyOnWriteArrayList<String>()
    val messages = CopyOnWriteArrayList<ByteArray>()
    val port: Int get() = serverSocket.localPort

    private val acceptor = thread(isDaemon = true, name = "fake-smtp-accept") {
        try {
            while (true) {
                val socket = serverSocket.accept()
                connections += socket
                thread(isDaemon = true, name = "fake-smtp-client") { handle(socket) }
            }
        } catch (error: Exception) {
            Unit
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        connections.forEach { socket -> runCatching { socket.close() } }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        FakeImapServer.write(output, "220 fake smtp ready\r\n")
        if (stallAfterGreeting) {
            while (!socket.isClosed) Thread.sleep(20)
            return
        }
        var authenticated = false
        while (true) {
            val line = FakeImapServer.readLine(input) ?: break
            commands += line
            when {
                line.startsWith("EHLO", ignoreCase = true) -> {
                    FakeImapServer.write(
                        output,
                        "250-fake\r\n250-$advertisedMechanisms\r\n250 SIZE 10485760\r\n",
                    )
                }
                line.startsWith("AUTH PLAIN", ignoreCase = true) -> {
                    val token = line.substringAfter("AUTH PLAIN").trim()
                    authenticated = validate(token)
                    if (authenticated) {
                        FakeImapServer.write(output, "235 authenticated\r\n")
                    } else {
                        FakeImapServer.write(output, "535 authentication failed\r\n")
                    }
                }
                line.startsWith("AUTH LOGIN", ignoreCase = true) -> {
                    FakeImapServer.write(output, "334 ${Base64.getEncoder().encodeToString("Username:".toByteArray())}\r\n")
                    val user = runCatching {
                        String(Base64.getDecoder().decode(FakeImapServer.readLine(input).orEmpty()))
                    }.getOrNull()
                    FakeImapServer.write(output, "334 ${Base64.getEncoder().encodeToString("Password:".toByteArray())}\r\n")
                    val password = runCatching {
                        String(Base64.getDecoder().decode(FakeImapServer.readLine(input).orEmpty()))
                    }.getOrNull()
                    authenticated = user == expectedUser && password == expectedPassword
                    if (authenticated) {
                        FakeImapServer.write(output, "235 authenticated\r\n")
                    } else {
                        FakeImapServer.write(output, "535 authentication failed\r\n")
                    }
                }
                line.startsWith("MAIL FROM", ignoreCase = true) -> {
                    if (!authenticated) {
                        FakeImapServer.write(output, "530 authentication required\r\n")
                    } else {
                        FakeImapServer.write(output, "250 sender ok\r\n")
                    }
                }
                line.startsWith("RCPT TO", ignoreCase = true) -> FakeImapServer.write(output, "250 recipient ok\r\n")
                line.startsWith("DATA", ignoreCase = true) -> {
                    FakeImapServer.write(output, "354 end with <CRLF>.<CRLF>\r\n")
                    val data = ByteArrayOutputStream()
                    while (true) {
                        val dataLine = FakeImapServer.readLine(input) ?: break
                        if (dataLine == ".") break
                        val unstuffed = if (dataLine.startsWith("..")) dataLine.substring(1) else dataLine
                        data.write(unstuffed.toByteArray(Charsets.ISO_8859_1))
                        data.write("\r\n".toByteArray(Charsets.ISO_8859_1))
                    }
                    messages += data.toByteArray()
                    FakeImapServer.write(output, "250 queued\r\n")
                }
                line.startsWith("QUIT", ignoreCase = true) -> {
                    FakeImapServer.write(output, "221 bye\r\n")
                    break
                }
                else -> FakeImapServer.write(output, "500 unknown command\r\n")
            }
        }
        runCatching { socket.close() }
    }

    private fun validate(token: String): Boolean {
        val decoded = runCatching { String(Base64.getDecoder().decode(token)) }.getOrNull() ?: return false
        return decoded == "\u0000$expectedUser\u0000$expectedPassword"
    }
}
