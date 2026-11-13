package io.github.whywood.deepdeep.plugin.mail.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal sealed interface ImapChunk {
    data class Text(val value: String) : ImapChunk

    data class Literal(val bytes: ByteArray) : ImapChunk
}

internal sealed interface ImapToken {
    data class Atom(val value: String) : ImapToken

    data class Quoted(val value: String) : ImapToken

    data class Literal(val bytes: ByteArray) : ImapToken

    data class Group(val items: List<ImapToken>) : ImapToken
}

internal fun ImapToken?.asString(): String? = when (this) {
    null -> null
    is ImapToken.Atom -> if (value.equals("NIL", ignoreCase = true)) null else value
    is ImapToken.Quoted -> value
    is ImapToken.Literal -> decodeText(bytes)
    is ImapToken.Group -> null
}

internal fun ImapToken?.asLong(): Long? = when (this) {
    null -> null
    is ImapToken.Atom -> value.toLongOrNull()
    is ImapToken.Quoted -> value.toLongOrNull()
    else -> null
}

internal fun ImapToken?.groupItems(): List<ImapToken> = (this as? ImapToken.Group)?.items.orEmpty()

internal fun decodeText(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: CharacterCodingException) {
        bytes.toString(Charsets.ISO_8859_1)
    }
}

internal sealed interface ImapResponse {
    data class Tagged(
        val tag: String,
        val status: String,
        val tokens: List<ImapToken>,
        val text: String,
    ) : ImapResponse

    data class Untagged(
        val tokens: List<ImapToken>,
        val text: String,
    ) : ImapResponse

    data class Continuation(val text: String) : ImapResponse
}

internal class ImapResponseReader(
    private val input: InputStream,
) {
    fun readResponse(): ImapResponse {
        val chunks = mutableListOf<ImapChunk>()
        while (true) {
            val line = readLine() ?: throw MailConnectionLostException()
            val literalStart = literalMarkerStart(line)
            if (literalStart == null) {
                chunks += ImapChunk.Text(line)
                chunks += ImapChunk.Text("\r\n")
                break
            }
            chunks += ImapChunk.Text(line.substring(0, literalStart))
            val size = literalSize(line) ?: throw MailProtocolException("invalid literal size")
            if (size > MAX_LITERAL_BYTES) throw MailProtocolException("literal too large")
            chunks += ImapChunk.Literal(readExactly(size))
        }
        return classify(ImapTokenizer(chunks).tokens())
    }

    private fun classify(tokens: List<ImapToken>): ImapResponse {
        val first = (tokens.firstOrNull() as? ImapToken.Atom)?.value
            ?: throw MailProtocolException("response does not start with a tag")
        if (first == "*") {
            val rest = tokens.drop(1)
            return ImapResponse.Untagged(rest, render(rest))
        }
        if (first == "+") return ImapResponse.Continuation(render(tokens.drop(1)))
        val status = (tokens.getOrNull(1) as? ImapToken.Atom)?.value?.uppercase()
            ?: throw MailProtocolException("tagged response without status")
        return ImapResponse.Tagged(first, status, tokens.drop(2), render(tokens.drop(2)))
    }

    private fun render(tokens: List<ImapToken>): String = tokens.joinToString(" ") { render(it) }

    private fun render(token: ImapToken): String = when (token) {
        is ImapToken.Atom -> token.value
        is ImapToken.Quoted -> "\"${token.value}\""
        is ImapToken.Literal -> ""
        is ImapToken.Group -> token.items.joinToString(prefix = "(", postfix = ")") { render(it) }
    }

    private fun literalMarkerStart(line: String): Int? {
        val close = line.lastIndexOf('}')
        if (close != line.length - 1) return null
        val open = line.lastIndexOf('{', close)
        if (open < 0) return null
        var digits = line.substring(open + 1, close)
        if (digits.endsWith("+")) digits = digits.dropLast(1)
        return if (digits.isNotEmpty() && digits.all { it.isDigit() }) open else null
    }

    private fun literalSize(line: String): Int? {
        val close = line.lastIndexOf('}')
        val open = line.lastIndexOf('{', close)
        if (open < 0 || close <= open) return null
        var digits = line.substring(open + 1, close)
        if (digits.endsWith("+")) digits = digits.dropLast(1)
        return digits.toIntOrNull()
    }

    private fun readLine(): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
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
            if (buffer.size() > MAX_LINE_BYTES) throw MailProtocolException("response line too long")
        }
    }

    private fun readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) throw MailConnectionLostException()
            read += count
        }
        return bytes
    }

    private companion object {
        const val MAX_LINE_BYTES = 256 * 1024
        const val MAX_LITERAL_BYTES = 64 * 1024 * 1024
    }
}

internal class ImapTokenizer(
    private val chunks: List<ImapChunk>,
) {
    private var chunkIndex = 0
    private var offset = 0

    fun tokens(): List<ImapToken> {
        val result = mutableListOf<ImapToken>()
        while (true) {
            result += nextValue() ?: break
        }
        return result
    }

    private fun nextValue(): ImapToken? {
        if (!skipWhitespace()) return null
        val current = chunks[chunkIndex]
        if (current is ImapChunk.Literal) {
            chunkIndex++
            offset = 0
            return ImapToken.Literal(current.bytes)
        }
        val text = (current as ImapChunk.Text).value
        return when (text[offset]) {
            '(' -> {
                offset++
                readGroup()
            }
            ')' -> throw MailProtocolException("unexpected closing parenthesis")
            '"' -> readQuoted()
            else -> readAtom()
        }
    }

    private fun skipWhitespace(): Boolean {
        while (chunkIndex < chunks.size) {
            val current = chunks[chunkIndex]
            if (current is ImapChunk.Literal) return true
            val text = (current as ImapChunk.Text).value
            var index = offset
            while (index < text.length && text[index].isImapWhitespace()) index++
            offset = index
            if (index < text.length) return true
            chunkIndex++
            offset = 0
        }
        return false
    }

    private fun readGroup(): ImapToken.Group {
        val items = mutableListOf<ImapToken>()
        while (true) {
            if (!skipWhitespace()) throw MailProtocolException("unterminated list")
            val current = chunks[chunkIndex]
            if (current is ImapChunk.Text && current.value[offset] == ')') {
                offset++
                return ImapToken.Group(items)
            }
            items += nextValue() ?: throw MailProtocolException("unterminated list")
        }
    }

    private fun readQuoted(): ImapToken.Quoted {
        val current = chunks[chunkIndex] as? ImapChunk.Text
            ?: throw MailProtocolException("quoted string starts inside a literal")
        val text = current.value
        offset++
        val builder = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            when {
                char == '\\' -> {
                    if (offset >= text.length) throw MailProtocolException("invalid escape in quoted string")
                    builder.append(text[offset++])
                }
                char == '"' -> return ImapToken.Quoted(builder.toString())
                else -> builder.append(char)
            }
        }
        throw MailProtocolException("unterminated quoted string")
    }

    private fun readAtom(): ImapToken.Atom {
        val builder = StringBuilder()
        var bracketDepth = 0
        while (chunkIndex < chunks.size) {
            val current = chunks[chunkIndex]
            if (current is ImapChunk.Literal) throw MailProtocolException("literal inside atom")
            val text = (current as ImapChunk.Text).value
            while (offset < text.length) {
                val char = text[offset]
                if (bracketDepth == 0) {
                    if (char.isImapWhitespace() || char == '(' || char == ')') {
                        return ImapToken.Atom(builder.toString())
                    }
                    if (char == '[') bracketDepth = 1 else bracketDepth = 0
                    builder.append(char)
                    offset++
                } else {
                    if (char == '[') bracketDepth++ else if (char == ']') bracketDepth--
                    builder.append(char)
                    offset++
                }
            }
            chunkIndex++
            offset = 0
        }
        return ImapToken.Atom(builder.toString())
    }
}

private fun Char.isImapWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'
