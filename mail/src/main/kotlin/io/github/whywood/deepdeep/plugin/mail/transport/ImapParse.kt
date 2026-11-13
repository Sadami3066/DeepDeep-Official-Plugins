package io.github.whywood.deepdeep.plugin.mail.transport

internal data class ImapFolder(
    val name: String,
    val delimiter: String?,
    val flags: Set<String>,
) {
    val selectable: Boolean get() = flags.none { it.equals("\\Noselect", ignoreCase = true) }
}

internal data class ImapMailboxStatus(
    val exists: Long,
    val uidValidity: Long?,
    val uidNext: Long?,
    val flags: Set<String>,
)

internal data class ImapAddress(
    val name: String?,
    val mailbox: String?,
    val host: String?,
) {
    val address: String
        get() = when {
            mailbox.isNullOrBlank() -> ""
            host.isNullOrBlank() -> mailbox
            else -> "$mailbox@$host"
        }
}

internal data class ImapEnvelope(
    val date: String?,
    val subject: String?,
    val from: List<ImapAddress>,
    val to: List<ImapAddress>,
    val cc: List<ImapAddress>,
    val inReplyTo: String?,
    val messageId: String?,
)

internal data class ImapFetchResult(
    val sequence: Long?,
    val uid: Long?,
    val flags: Set<String>?,
    val internalDate: String?,
    val size: Long?,
    val envelope: ImapEnvelope?,
    val headerBlock: ByteArray?,
    val body: ByteArray?,
    val streamedBodySize: Int? = null,
)

internal object ImapParse {
    fun folder(response: ImapResponse.Untagged): ImapFolder? {
        val tokens = response.tokens
        if (!tokens.isKeyword("LIST") && !tokens.isKeyword("LSUB")) return null
        val flags = tokens.getOrNull(1).groupItems()
            .mapNotNull { (it as? ImapToken.Atom)?.value }
            .toSet()
        val delimiter = tokens.getOrNull(2)?.asString()
        val rawName = tokens.getOrNull(3)?.asString() ?: return null
        return ImapFolder(ImapCodec.decodeMailbox(rawName), delimiter, flags)
    }

    fun status(responses: List<ImapResponse>): ImapMailboxStatus {
        var exists = 0L
        var uidValidity: Long? = null
        var uidNext: Long? = null
        val flags = mutableSetOf<String>()
        for (response in responses) {
            if (response !is ImapResponse.Untagged) continue
            val tokens = response.tokens
            when {
                tokens.getOrNull(1).isKeyword("EXISTS") -> exists = tokens.getOrNull(0)?.asLong() ?: exists
                tokens.getOrNull(0).isKeyword("FLAGS") -> {
                    flags += tokens.getOrNull(1).groupItems().mapNotNull { (it as? ImapToken.Atom)?.value }
                }
            }
            responseCode(response.text, "UIDVALIDITY")?.let { uidValidity = it }
            responseCode(response.text, "UIDNEXT")?.let { uidNext = it }
        }
        return ImapMailboxStatus(exists, uidValidity, uidNext, flags)
    }

    fun search(response: ImapResponse.Untagged): List<Long> {
        if (!response.tokens.isKeyword("SEARCH")) return emptyList()
        return response.tokens.drop(1).mapNotNull { it.asLong() }
    }

    fun capabilities(response: ImapResponse): Set<String> {
        if (response is ImapResponse.Untagged && response.tokens.isKeyword("CAPABILITY")) {
            return response.tokens.drop(1).mapNotNull { (it as? ImapToken.Atom)?.value?.uppercase() }.toSet()
        }
        val text = when (response) {
            is ImapResponse.Untagged -> response.text
            is ImapResponse.Tagged -> response.text
            is ImapResponse.Continuation -> response.text
        }
        val code = bracketedCode(text, "CAPABILITY") ?: return emptySet()
        return code.trim().split(' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet()
    }

    fun fetch(response: ImapResponse.Untagged): ImapFetchResult? {
        val tokens = response.tokens
        val sequence = tokens.getOrNull(0)?.asLong() ?: return null
        if (!tokens.getOrNull(1).isKeyword("FETCH")) return null
        val items = tokens.getOrNull(2).groupItems()
        var uid: Long? = null
        var flags: Set<String>? = null
        var internalDate: String? = null
        var size: Long? = null
        var envelope: ImapEnvelope? = null
        var headerBlock: ByteArray? = null
        var body: ByteArray? = null
        var streamedBodySize: Int? = null
        var index = 0
        while (index + 1 < items.size) {
            val name = (items[index] as? ImapToken.Atom)?.value ?: break
            val value = items[index + 1]
            when {
                name.equals("UID", ignoreCase = true) -> uid = value.asLong()
                name.equals("FLAGS", ignoreCase = true) -> {
                    flags = value.groupItems().mapNotNull { (it as? ImapToken.Atom)?.value }.toSet()
                }
                name.equals("INTERNALDATE", ignoreCase = true) -> internalDate = value.asString()
                name.equals("RFC822.SIZE", ignoreCase = true) -> size = value.asLong()
                name.equals("ENVELOPE", ignoreCase = true) -> envelope = envelope(value)
                name.startsWith("BODY[HEADER", ignoreCase = true) ||
                    name.equals("RFC822.HEADER", ignoreCase = true) -> headerBlock = bytes(value)
                name.equals("BODY[]", ignoreCase = true) ||
                    name.equals("BODY", ignoreCase = true) ||
                    name.equals("RFC822", ignoreCase = true) -> {
                    val streamed = value as? ImapToken.Streamed
                    if (streamed != null) streamedBodySize = streamed.size else body = bytes(value)
                }
            }
            index += 2
        }
        return ImapFetchResult(sequence, uid, flags, internalDate, size, envelope, headerBlock, body, streamedBodySize)
    }

    fun envelope(token: ImapToken): ImapEnvelope? {
        val items = token.groupItems()
        if (items.isEmpty()) return null
        return ImapEnvelope(
            date = items.getOrNull(0).asString(),
            subject = items.getOrNull(1).asString(),
            from = addresses(items.getOrNull(2)),
            to = addresses(items.getOrNull(5)),
            cc = addresses(items.getOrNull(6)),
            inReplyTo = items.getOrNull(8).asString(),
            messageId = items.getOrNull(9).asString(),
        )
    }

    fun merge(current: ImapFetchResult?, incoming: ImapFetchResult): ImapFetchResult {
        if (current == null) return incoming
        return ImapFetchResult(
            sequence = incoming.sequence ?: current.sequence,
            uid = incoming.uid ?: current.uid,
            flags = incoming.flags ?: current.flags,
            internalDate = incoming.internalDate ?: current.internalDate,
            size = incoming.size ?: current.size,
            envelope = incoming.envelope ?: current.envelope,
            headerBlock = incoming.headerBlock ?: current.headerBlock,
            body = incoming.body ?: current.body,
            streamedBodySize = incoming.streamedBodySize ?: current.streamedBodySize,
        )
    }

    fun errorDetail(response: ImapResponse.Tagged): String? {
        val code = firstBracketedCode(response.text)
        val detail = response.text.trim()
        return when {
            code != null && detail.isNotEmpty() -> "$code $detail"
            detail.isNotEmpty() -> detail
            else -> null
        }
    }

    fun isAuthenticationFailure(response: ImapResponse.Tagged): Boolean {
        val detail = errorDetail(response)?.uppercase() ?: return false
        return detail.contains("AUTHENTICATIONFAILED") ||
            detail.contains("AUTHORIZATIONFAILED") ||
            detail.contains("INVALID CREDENTIALS") ||
            detail.contains("AUTHENTICATE") ||
            detail.contains("LOGIN FAILED")
    }

    private fun addresses(token: ImapToken?): List<ImapAddress> {
        val items = token.groupItems()
        return items.mapNotNull { entry ->
            val fields = entry.groupItems()
            if (fields.isEmpty()) return@mapNotNull null
            val address = ImapAddress(
                name = fields.getOrNull(0).asString(),
                mailbox = fields.getOrNull(2).asString(),
                host = fields.getOrNull(3).asString(),
            )
            if (address.address.isBlank()) null else address
        }
    }

    private fun bytes(token: ImapToken): ByteArray? = when (token) {
        is ImapToken.Literal -> token.bytes
        is ImapToken.Quoted -> token.value.toByteArray(Charsets.ISO_8859_1)
        else -> null
    }

    private fun responseCode(text: String, name: String): Long? {
        val marker = "[" + name
        var index = text.indexOf(marker, ignoreCase = true)
        while (index >= 0) {
            val start = index + marker.length
            val end = text.indexOf(']', start)
            if (end > start) {
                val value = text.substring(start, end).trim()
                if (value.isNotEmpty() && value.all { it.isDigit() }) return value.toLongOrNull()
            }
            index = text.indexOf(marker, index + 1, ignoreCase = true)
        }
        return null
    }

    private fun List<ImapToken>.isKeyword(keyword: String): Boolean =
        (getOrNull(0) as? ImapToken.Atom)?.value?.equals(keyword, ignoreCase = true) == true

    private fun ImapToken?.isKeyword(keyword: String): Boolean =
        (this as? ImapToken.Atom)?.value?.equals(keyword, ignoreCase = true) == true

    private fun bracketedCode(text: String, name: String): String? {
        val marker = "[" + name
        val start = text.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val from = start + marker.length
        val end = text.indexOf(']', from)
        if (end < 0) return null
        return text.substring(from, end)
    }

    private fun firstBracketedCode(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null
        val end = text.indexOf(']', start)
        if (end <= start) return null
        return text.substring(start, end + 1)
    }
}
