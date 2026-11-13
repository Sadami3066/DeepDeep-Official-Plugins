package io.github.whywood.deepdeep.plugin.mail.transport

internal interface MailTransport {
    fun imap(host: String, port: Int, credentials: ImapCredentials): ImapClient

    fun smtp(host: String, port: Int, credentials: SmtpCredentials): SmtpClient
}

internal object SecureMailTransport : MailTransport {
    override fun imap(host: String, port: Int, credentials: ImapCredentials): ImapClient =
        ImapClient(ImapEndpoint(host, port), credentials)

    override fun smtp(host: String, port: Int, credentials: SmtpCredentials): SmtpClient =
        SmtpClient(SmtpEndpoint(host, port), credentials)
}
