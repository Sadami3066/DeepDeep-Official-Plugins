package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.sync.MailMessageMapper
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCodec
import io.github.whywood.deepdeep.plugin.mail.transport.ImapLiteralHandler
import io.github.whywood.deepdeep.plugin.mail.transport.ImapParse
import io.github.whywood.deepdeep.plugin.mail.transport.ImapResponse
import io.github.whywood.deepdeep.plugin.mail.transport.ImapResponseReader
import io.github.whywood.deepdeep.plugin.mail.transport.ImapFolder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapProtocolTest {
    private fun reader(script: String): ImapResponseReader =
        ImapResponseReader(script.toByteArray(Charsets.ISO_8859_1).inputStream())

    @Test
    fun classifiesTaggedUntaggedAndContinuation() {
        val responseReader = reader(
            "* OK fake ready\r\n" +
                "+ go ahead\r\n" +
                "A0001 OK completed\r\n",
        )
        val greeting = responseReader.readResponse()
        assertTrue(greeting is ImapResponse.Untagged)
        val continuation = responseReader.readResponse()
        assertTrue(continuation is ImapResponse.Continuation)
        assertEquals("go ahead", (continuation as ImapResponse.Continuation).text)
        val tagged = responseReader.readResponse()
        assertTrue(tagged is ImapResponse.Tagged)
        assertEquals("A0001", (tagged as ImapResponse.Tagged).tag)
        assertEquals("OK", tagged.status)
    }

    @Test
    fun readsQuotedStringsWithEscapesAndLists() {
        val response = reader("* LIST (\\HasNoChildren \\Sent) \"/\" \"My \\\"Sent\\\" Folder\"\r\n").readResponse()
        val folder = ImapParse.folder(response as ImapResponse.Untagged)
        assertNotNull(folder)
        assertEquals("My \"Sent\" Folder", folder?.name)
        assertEquals("/", folder?.delimiter)
        assertTrue(folder?.flags?.contains("\\Sent") == true)
    }

    @Test
    fun readsLiteralFolderName() {
        val name = "FOLDER With Space"
        val bytes = name.toByteArray()
        val script = "* LIST (\\HasChildren) \"/\" {${bytes.size}}\r\n$name\r\n"
        val response = reader(script).readResponse()
        val folder = ImapParse.folder(response as ImapResponse.Untagged)
        assertEquals(name, folder?.name)
    }

    @Test
    fun decodesModifiedUtf7FolderNames() {
        val raw = "* LIST () \"/\" \"&XfJT0ZAB-\"\r\n"
        val response = reader(raw).readResponse()
        val folder = ImapParse.folder(response as ImapResponse.Untagged)
        assertEquals("已发送", folder?.name)
    }

    @Test
    fun modifiedUtf7MatchesRfcExampleAndRoundTrips() {
        val encoded = "~peter/mail/&U,BTFw-/&ZeVnLIqe-"
        val decoded = ImapCodec.decodeMailbox(encoded)
        assertEquals("~peter/mail/台北/日本語", decoded)
        assertEquals(encoded, ImapCodec.encodeMailbox(decoded))
        assertEquals("Sent & Drafts", ImapCodec.decodeMailbox(ImapCodec.encodeMailbox("Sent & Drafts")))
    }

    @Test
    fun quotesMailboxCommandsAndBuildsUidSets() {
        assertEquals("\"a\\\"b\\\\c\"", ImapCodec.quote("a\"b\\c"))
        assertEquals("1:3,5,7:8", ImapCodec.uidSet(listOf(3L, 1L, 2L, 5L, 7L, 8L)))
        assertEquals("", ImapCodec.uidSet(emptyList()))
    }

    @Test
    fun parsesSelectStatus() {
        val responseReader = reader(
            "* 3 EXISTS\r\n" +
                "* OK [UIDVALIDITY 1234] UIDVALIDITY\r\n" +
                "* OK [UIDNEXT 99] UIDNEXT\r\n" +
                "* FLAGS (\\Seen \\Flagged)\r\n" +
                "A1 OK [READ-ONLY] done\r\n",
        )
        val all = (0 until 5).map { responseReader.readResponse() }
        val status = ImapParse.status(all)
        assertEquals(3L, status.exists)
        assertEquals(1234L, status.uidValidity)
        assertEquals(99L, status.uidNext)
        assertTrue(status.flags.contains("\\Seen"))
    }

    @Test
    fun parsesSearchResults() {
        val response = reader("* SEARCH 11 12 45\r\nA1 OK done\r\n").readResponse()
        assertEquals(listOf(11L, 12L, 45L), ImapParse.search(response as ImapResponse.Untagged))
    }

    @Test
    fun parsesCapabilitiesFromGreetingAndCommand() {
        val greeting = reader("* OK [CAPABILITY IMAP4rev1 AUTH=PLAIN] ready\r\n").readResponse()
        val capabilities = ImapParse.capabilities(greeting)
        assertTrue(capabilities.contains("IMAP4REV1"))
        assertTrue(capabilities.contains("AUTH=PLAIN"))
        val command = reader("* CAPABILITY IMAP4rev1 MOVE UIDPLUS\r\nA1 OK done\r\n").readResponse()
        assertTrue(ImapParse.capabilities(command).contains("MOVE"))
    }

    @Test
    fun detectsAuthenticationFailures() {
        val response = reader("A1 NO [AUTHENTICATIONFAILED] invalid credentials\r\n").readResponse()
        val tagged = response as ImapResponse.Tagged
        assertTrue(ImapParse.isAuthenticationFailure(tagged))
        assertTrue(ImapParse.errorDetail(tagged)!!.contains("AUTHENTICATIONFAILED"))
        val rejected = reader("A1 BAD unknown command\r\n").readResponse() as ImapResponse.Tagged
        assertFalse(ImapParse.isAuthenticationFailure(rejected))
    }

    @Test
    fun parsesFetchResponseWithEnvelopeAndHeaderLiteral() {
        val headerBlock = "References: <root@example.com>\r\nIn-Reply-To: <root@example.com>\r\n\r\n"
        val headerBytes = headerBlock.toByteArray(Charsets.ISO_8859_1)
        val script = buildString {
            append("* 1 FETCH (UID 42 FLAGS (\\Seen) INTERNALDATE \"12-Nov-2026 10:00:00 +0800\" ")
            append("RFC822.SIZE 2048 ENVELOPE (\"12-Nov-2026 10:00:00 +0800\" ")
            append("\"=?UTF-8?B?5rWL6K+V?=\" ")
            append("((\"Alice\" NIL \"alice\" \"example.com\")) ")
            append("((NIL NIL \"alice\" \"example.com\")) ")
            append("((NIL NIL \"alice\" \"example.com\")) ")
            append("((\"Bob\" NIL \"bob\" \"example.com\")) ")
            append("((NIL NIL \"bob\" \"example.com\")) NIL NIL ")
            append("\"<m1@example.com>\") ")
            append("BODY[HEADER.FIELDS (DATE FROM TO CC SUBJECT MESSAGE-ID REFERENCES IN-REPLY-TO CONTENT-TYPE)] {${headerBytes.size}}\r\n")
        }.toByteArray(Charsets.ISO_8859_1) + headerBytes + ")\r\n".toByteArray(Charsets.ISO_8859_1)
        val response = ImapResponseReader(script.inputStream()).readResponse()
        val fetch = ImapParse.fetch(response as ImapResponse.Untagged)
        assertNotNull(fetch)
        assertEquals(42L, fetch?.uid)
        assertEquals(setOf("\\Seen"), fetch?.flags)
        assertEquals(2048L, fetch?.size)
        val summary = MailMessageMapper.toSummary("acc", MailFolderRole.INBOX, fetch!!)
        assertEquals("acc:inbox:42", summary.id)
        assertEquals("测试", summary.subject)
        assertEquals("Alice", summary.fromName)
        assertEquals("alice@example.com", summary.fromAddress)
        assertEquals(listOf("Bob <bob@example.com>"), summary.to)
        assertFalse(summary.unread)
        assertEquals("root@example.com", summary.threadId)
        val expected = SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US)
            .parse("12-Nov-2026 10:00:00 +0800")!!.time
        assertEquals(expected, summary.dateMillis)
        assertEquals("<m1@example.com>", fetch.envelope?.messageId)
    }

    @Test
    fun parsesFetchFlagsOnlyResponse() {
        val response = reader("* 2 FETCH (UID 7 FLAGS (\\Flagged))\r\nA1 OK done\r\n").readResponse()
        val fetch = ImapParse.fetch(response as ImapResponse.Untagged)
        assertEquals(7L, fetch?.uid)
        assertEquals(setOf("\\Flagged"), fetch?.flags)
        assertNull(fetch?.body)
    }

    @Test
    fun streamsBodyLiteralIntoHandler() {
        val raw = "Subject: hi\r\n\r\nbody bytes".toByteArray(Charsets.ISO_8859_1)
        val script = "* 1 FETCH (UID 9 BODY[] {${raw.size}}\r\n".toByteArray() + raw + ")\r\n".toByteArray()
        val output = ByteArrayOutputStream()
        val reader = ImapResponseReader(script.inputStream()) {
            ImapLiteralHandler { _, preceding ->
                if (preceding.contains("BODY[]")) output else null
            }
        }
        val response = reader.readResponse()
        val fetch = ImapParse.fetch(response as ImapResponse.Untagged)
        assertEquals(9L, fetch?.uid)
        assertEquals(raw.size, fetch?.streamedBodySize)
        assertNull(fetch?.body)
        assertEquals("Subject: hi\r\n\r\nbody bytes", output.toString(Charsets.ISO_8859_1.name()))
    }

    @Test
    fun rejectsBufferedLiteralAboveLimit() {
        val size = 3 * 1024 * 1024
        val script = "* 1 FETCH (UID 1 BODY[] {$size}\r\n".toByteArray() + ByteArray(size) + ")\r\n".toByteArray()
        try {
            ImapResponseReader(script.inputStream()).readResponse()
            throw AssertionError("expected literal limit rejection")
        } catch (error: io.github.whywood.deepdeep.plugin.mail.transport.MailProtocolException) {
            assertNotNull(error.message)
        }
    }

    @Test
    fun parsesFetchBodyLiteral() {
        val raw = "Subject: hi\r\n\r\nbody".toByteArray(Charsets.ISO_8859_1)
        val script = "* 1 FETCH (UID 9 BODY[] {${raw.size}}\r\n".toByteArray() + raw + ")\r\n".toByteArray()
        val response = ImapResponseReader(script.inputStream()).readResponse()
        val fetch = ImapParse.fetch(response as ImapResponse.Untagged)
        assertEquals(9L, fetch?.uid)
        assertEquals("Subject: hi\r\n\r\nbody", fetch?.body?.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun parsesInternalDate() {
        val expected = SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US)
            .parse("12-Nov-2026 10:00:00 +0800")!!.time
        assertEquals(expected, ImapCodec.parseInternalDate("12-Nov-2026 10:00:00 +0800"))
        assertNull(ImapCodec.parseInternalDate("not a date"))
    }

    @Test
    fun parsesEmptyEnvelopeAsNullFields() {
        val response = reader(
            "* 1 FETCH (UID 1 ENVELOPE (NIL NIL NIL NIL NIL NIL NIL NIL NIL NIL))\r\n",
        ).readResponse()
        val fetch = ImapParse.fetch(response as ImapResponse.Untagged)
        val envelope = fetch?.envelope
        assertNotNull(envelope)
        assertEquals(emptyList<Any>(), envelope?.from)
        assertNull(envelope?.subject)
        assertNull(envelope?.messageId)
    }

    @Test
    fun exposesFolderDelimiterAndNoselect() {
        assertEquals("/", ImapFolder("INBOX", "/", emptySet()).delimiter)
        assertFalse(ImapFolder("Parent", "/", setOf("\\Noselect")).selectable)
        assertTrue(ImapFolder("INBOX", "/", emptySet()).selectable)
    }
}
