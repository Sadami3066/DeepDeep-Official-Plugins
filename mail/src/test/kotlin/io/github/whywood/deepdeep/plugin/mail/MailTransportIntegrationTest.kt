package io.github.whywood.deepdeep.plugin.mail

import io.github.whywood.deepdeep.plugin.mail.mime.MailComposer
import io.github.whywood.deepdeep.plugin.mail.mime.OutgoingMail
import io.github.whywood.deepdeep.plugin.mail.sync.FolderMapping
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodyOutcome
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodySkip
import io.github.whywood.deepdeep.plugin.mail.transport.ImapBodyTarget
import io.github.whywood.deepdeep.plugin.mail.transport.ImapClient
import io.github.whywood.deepdeep.plugin.mail.transport.ImapCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.ImapEndpoint
import io.github.whywood.deepdeep.plugin.mail.transport.MailAuthenticationException
import io.github.whywood.deepdeep.plugin.mail.transport.MailServerException
import io.github.whywood.deepdeep.plugin.mail.transport.MailTimeoutException
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpClient
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpCredentials
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpEndpoint
import io.github.whywood.deepdeep.plugin.mail.transport.SmtpSecurity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MailTransportIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun imapTimesOutWhenServerStopsResponding() {
        val server = FakeImapServer(mutableListOf(FakeFolder("INBOX")), stallAfterGreeting = true)
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
                timeoutMs = 300,
            )
            try {
                client.connect()
                try {
                    client.login()
                    fail("login should time out")
                } catch (error: MailTimeoutException) {
                    assertTrue(error.target.isNotBlank())
                }
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpTimesOutWhenServerStopsResponding() {
        val server = FakeSmtpServer(stallAfterGreeting = true)
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port, SmtpSecurity.PLAIN),
                SmtpCredentials("user@example.com", "secret"),
                timeoutMs = 300,
            )
            try {
                try {
                    client.connect()
                    fail("connect should time out")
                } catch (error: MailTimeoutException) {
                    assertTrue(error.target.isNotBlank())
                }
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    private fun rawMessage(
        uid: Long,
        subject: String,
        from: String,
        to: String,
        body: String,
        messageId: String = "<$uid@example.com>",
    ): ByteArray = (
        "From: $from\r\n" +
            "To: $to\r\n" +
            "Subject: $subject\r\n" +
            "Date: Tue, 12 Nov 2026 10:00:00 +0800\r\n" +
            "Message-ID: $messageId\r\n" +
            "Content-Type: text/plain; charset=UTF-8\r\n" +
            "\r\n" +
            "$body\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun message(uid: Long, subject: String, body: String, unread: Boolean = true): FakeMessage = FakeMessage(
        uid = uid,
        flags = if (unread) mutableSetOf() else mutableSetOf("\\Seen"),
        subject = subject,
        fromName = "Sender $uid",
        fromAddress = "sender$uid@example.com",
        toName = "User",
        toAddress = "user@example.com",
        messageId = "<$uid@example.com>",
        raw = rawMessage(uid, subject, "Sender $uid <sender$uid@example.com>", "User <user@example.com>", body),
    )

    @Test
    fun imapSessionCoversListFetchStoreMoveAndAppend() {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(message(1, "first", "hello"), message(2, "second", "world", unread = false)),
        )
        val sent = FakeFolder("已发送", flags = setOf("\\Sent"))
        val archive = FakeFolder("Archive", flags = setOf("\\Archive"))
        val server = FakeImapServer(mutableListOf(inbox, sent, archive))
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                assertTrue(client.capability().contains("MOVE"))
                val folders = client.listFolders()
                assertEquals(3, folders.size)
                val roles = FolderMapping.resolve(folders)
                assertEquals("INBOX", roles[MailFolderRole.INBOX])
                assertEquals("已发送", roles[MailFolderRole.SENT])
                assertEquals("Archive", roles[MailFolderRole.ARCHIVE])

                val status = client.select("INBOX", readOnly = true)
                assertEquals(2L, status.exists)
                assertEquals(100L, status.uidValidity)
                assertEquals(3L, status.uidNext)

                val allUids = client.searchAll()
                assertEquals(listOf(1L, 2L), allUids)
                assertEquals(listOf(2L), client.searchNewUids(1))

                val headers = client.fetchHeaders(allUids)
                assertEquals(2, headers.size)
                val first = headers.first { it.uid == 1L }
                assertTrue(first.flags.orEmpty().isEmpty())
                assertEquals("<1@example.com>", first.envelope?.messageId)
                assertNotNull(first.envelope?.subject)
                assertNotNull(first.headerBlock)

                val bodyFile = File(temporaryFolder.newFolder("bodies"), "inbox-1.eml")
                val outcomes = client.fetchBodiesToFiles(listOf(ImapBodyTarget(1L, bodyFile)), 1024 * 1024)
                val saved = outcomes.single()
                assertTrue(saved is ImapBodyOutcome.Saved)
                assertEquals(bodyFile.length(), (saved as ImapBodyOutcome.Saved).size)
                assertEquals(
                    inbox.messages.first { it.uid == 1L }.raw.toString(Charsets.UTF_8),
                    bodyFile.readBytes().toString(Charsets.UTF_8),
                )

                val flags = client.fetchFlags(listOf(1L, 2L))
                assertTrue(flags.getValue(1L).isEmpty())
                assertTrue(flags.getValue(2L).contains("\\Seen"))

                client.storeSeen(1L, seen = true)
                assertTrue(inbox.messages.first { it.uid == 1L }.flags.contains("\\Seen"))

                client.move(2L, "Archive")
                assertTrue(inbox.messages.none { it.uid == 2L })
                assertEquals(1, archive.messages.size)

                val newRaw = rawMessage(3, "appended", "User <user@example.com>", "other@example.com", "appended body")
                client.append("已发送", listOf("\\Seen"), newRaw)
                assertEquals(1, sent.messages.size)
                assertEquals(newRaw.toString(Charsets.UTF_8), sent.messages.single().raw.toString(Charsets.UTF_8))
                client.logout()
            } finally {
                client.close()
            }
            assertTrue(server.commands.any { it.contains("UID FETCH") })
            assertTrue(server.commands.any { it.contains("UID MOVE") })
        } finally {
            server.close()
        }
    }

    @Test
    fun imapStreamsBodyToFileAndSkipsOversizedMessage() {
        val inbox = FakeFolder(
            "INBOX",
            messages = mutableListOf(
                message(1, "small", "small body"),
                message(2, "large", "large body".repeat(200_000)),
            ),
        )
        val server = FakeImapServer(mutableListOf(inbox))
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                client.select("INBOX", readOnly = true)
                val directory = temporaryFolder.newFolder("streamed")
                val small = File(directory, "one.eml")
                val large = File(directory, "two.eml")
                val outcomes = client.fetchBodiesToFiles(
                    listOf(ImapBodyTarget(1L, small), ImapBodyTarget(2L, large)),
                    1024 * 1024,
                )
                assertTrue(outcomes.first { it is ImapBodyOutcome.Saved } is ImapBodyOutcome.Saved)
                val skipped = outcomes.first { it is ImapBodyOutcome.Skipped } as ImapBodyOutcome.Skipped
                assertEquals(2L, skipped.uid)
                assertEquals(ImapBodySkip.TOO_LARGE, skipped.reason)
                assertFalse(large.exists())
                assertFalse(File(directory, "two.eml.part").exists())
                assertTrue(small.readBytes().toString(Charsets.UTF_8).contains("small body"))
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun imapSkipsBodyByDeclaredSizeWithoutNetworkRoundTrip() {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(message(1, "first", "body")))
        val server = FakeImapServer(mutableListOf(inbox))
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                client.select("INBOX", readOnly = true)
                val commandsBefore = server.commands.size
                val file = File(temporaryFolder.newFolder("declared"), "one.eml")
                val outcomes = client.fetchBodiesToFiles(
                    listOf(ImapBodyTarget(1L, file, declaredSize = 200L * 1024 * 1024)),
                    1024 * 1024,
                )
                val skipped = outcomes.single() as ImapBodyOutcome.Skipped
                assertEquals(ImapBodySkip.TOO_LARGE, skipped.reason)
                assertEquals(commandsBefore, server.commands.size)
                assertFalse(file.exists())
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun imapFallsBackToCopyStoreExpungeWithoutMoveCapability() {
        val inbox = FakeFolder("INBOX", messages = mutableListOf(message(1, "first", "hello")))
        val trash = FakeFolder("Trash", flags = setOf("\\Trash"))
        val server = FakeImapServer(mutableListOf(inbox, trash), supportMove = false, supportUidPlus = true)
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                assertTrue(!client.capability().contains("MOVE"))
                client.select("INBOX", readOnly = false)
                client.move(1L, "Trash")
            } finally {
                client.close()
            }
            assertTrue(inbox.messages.isEmpty())
            assertEquals(1, trash.messages.size)
            assertTrue(server.commands.any { it.startsWith("A") && it.contains("UID COPY") })
            assertTrue(server.commands.any { it.contains("UID STORE") && it.contains("\\Deleted") })
            assertTrue(server.commands.any { it.contains("UID EXPUNGE") })
        } finally {
            server.close()
        }
    }

    @Test
    fun imapRejectsWrongPasswordWithAuthenticationError() {
        val server = FakeImapServer(mutableListOf(FakeFolder("INBOX")), loginPassword = "other")
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "wrong"),
            )
            try {
                client.connect()
                client.login()
                fail("login should fail")
            } catch (error: MailAuthenticationException) {
                assertTrue(error.detail.orEmpty().contains("AUTHENTICATIONFAILED"))
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun imapUsesAuthenticatePlainWhenLoginDisabled() {
        val server = FakeImapServer(mutableListOf(FakeFolder("INBOX")), loginDisabled = true)
        try {
            val client = ImapClient(
                ImapEndpoint("127.0.0.1", server.port, tls = false),
                ImapCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                client.select("INBOX", readOnly = true)
                assertEquals(emptyList<Long>(), client.searchAll())
            } finally {
                client.close()
            }
            assertTrue(server.commands.any { it.contains("AUTHENTICATE PLAIN") })
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpSendsMessageThroughFakeServer() {
        val server = FakeSmtpServer()
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port, SmtpSecurity.PLAIN),
                SmtpCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                val message = MailComposer.compose(
                    OutgoingMail(
                        fromAddress = "user@example.com",
                        fromName = "User",
                        to = listOf("a@example.com", "b@example.com"),
                        cc = listOf("c@example.com"),
                        subject = "测试邮件",
                        body = "正文内容",
                    ),
                )
                client.send("user@example.com", listOf("a@example.com", "b@example.com", "c@example.com"), message)
                client.quit()
                assertEquals(1, server.messages.size)
                assertEquals(
                    message.toString(Charsets.ISO_8859_1),
                    server.messages.single().toString(Charsets.ISO_8859_1),
                )
            } finally {
                client.close()
            }
            assertTrue(server.commands.any { it.startsWith("AUTH PLAIN") })
            assertTrue(server.commands.any { it.startsWith("MAIL FROM:<user@example.com>") })
            assertEquals(3, server.commands.count { it.startsWith("RCPT TO:") })
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpUsesLoginMechanismWhenPlainIsNotAdvertised() {
        val server = FakeSmtpServer(advertisedMechanisms = "AUTH LOGIN")
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port, SmtpSecurity.PLAIN),
                SmtpCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
            } finally {
                client.close()
            }
            assertTrue(server.commands.any { it.startsWith("AUTH LOGIN") })
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpDotStuffsMessageData() {
        val server = FakeSmtpServer()
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port, SmtpSecurity.PLAIN),
                SmtpCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                client.login()
                val raw = "Subject: dot\r\n\r\nline1\r\n.\r\n..line2\r\n".toByteArray()
                client.send("user@example.com", listOf("a@example.com"), raw)
                client.quit()
                assertEquals(
                    raw.toString(Charsets.ISO_8859_1),
                    server.messages.single().toString(Charsets.ISO_8859_1),
                )
            } finally {
                client.close()
            }
            assertTrue(server.commands.any { it.contains("DATA") })
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpRejectsWrongPasswordWithAuthenticationError() {
        val server = FakeSmtpServer(expectedPassword = "other")
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port, SmtpSecurity.PLAIN),
                SmtpCredentials("user@example.com", "wrong"),
            )
            try {
                client.connect()
                client.login()
                fail("login should fail")
            } catch (error: MailAuthenticationException) {
                assertNotNull(error)
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun smtpStartTlsIsRequiredWhenNotImplicit() {
        val server = FakeSmtpServer(advertisedMechanisms = "AUTH PLAIN")
        try {
            val client = SmtpClient(
                SmtpEndpoint("127.0.0.1", server.port),
                SmtpCredentials("user@example.com", "secret"),
            )
            try {
                client.connect()
                fail("connect should fail without STARTTLS")
            } catch (error: MailServerException) {
                assertNotNull(error)
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }
}
