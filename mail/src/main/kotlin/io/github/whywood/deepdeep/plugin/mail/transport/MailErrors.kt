package io.github.whywood.deepdeep.plugin.mail.transport

import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.localizedText
import java.io.IOException

internal class MailNetworkException(
    val target: String,
    cause: Throwable? = null,
) : IOException(target, cause)

internal class MailTimeoutException(
    val target: String,
    cause: Throwable? = null,
) : IOException(target, cause)

internal class MailTlsException(
    val target: String,
    cause: Throwable? = null,
) : IOException(target, cause)

internal class MailAuthenticationException(
    val detail: String?,
    cause: Throwable? = null,
) : IOException(detail, cause)

internal class MailServerException(
    val detail: String?,
    cause: Throwable? = null,
) : IOException(detail, cause)

internal class MailConnectionLostException(
    cause: Throwable? = null,
) : IOException("connection closed", cause)

internal class MailProtocolException(
    message: String,
) : IOException(message)

internal class MailStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class MailLocalizedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class MailTexts(
    private val host: PluginHost,
) {
    fun connectionFailed(target: String): String = text(
        "连不上 $target，请检查网络，或确认服务器地址和端口填写正确。",
        "Cannot reach $target. Check the network and the server address and port.",
    )

    fun timedOut(target: String): String = text(
        "连接 $target 超时，请稍后重试。",
        "Timed out connecting to $target. Try again later.",
    )

    fun tlsFailed(target: String): String = text(
        "$target 的安全连接没有建立成功，请确认服务器地址填写正确。",
        "Could not establish a secure connection to $target. Check the server address.",
    )

    fun imapLoginFailed(): String = text(
        "邮箱登录失败。请确认授权码或应用专用密码正确，并已在邮箱设置中开启 IMAP 服务。",
        "Mailbox sign-in failed. Check the authorization code or app password and make sure IMAP is enabled in the mailbox settings.",
    )

    fun smtpLoginFailed(): String = text(
        "发信登录失败。请确认授权码或应用专用密码正确，并已在邮箱设置中开启 SMTP 服务。",
        "Sending sign-in failed. Check the authorization code or app password and make sure SMTP is enabled in the mailbox settings.",
    )

    fun serverRejected(): String = text(
        "邮件服务器拒绝了这个操作，请稍后重试或检查邮箱设置。",
        "The mail server refused the action. Try again later or check the mailbox settings.",
    )

    fun connectionLost(): String = text(
        "与邮件服务器的连接断开了，请重试。",
        "The connection to the mail server was lost. Try again.",
    )

    fun missingAccount(): String = text(
        "找不到这个邮箱账号，请重新添加。",
        "This mailbox account was not found. Add it again.",
    )

    fun missingSecret(): String = text(
        "授权码已失效，请重新输入授权码。",
        "The authorization code is no longer available. Enter it again.",
    )

    fun missingServers(): String = text(
        "服务器地址没有填写完整，请检查邮箱设置。",
        "The server addresses are incomplete. Check the mailbox settings.",
    )

    fun missingMessage(): String = text(
        "这封邮件已不在本地缓存中，请刷新后再试。",
        "This message is no longer cached. Refresh and try again.",
    )

    fun missingArchiveFolder(): String = text(
        "这个邮箱没有归档文件夹，无法归档。",
        "This mailbox has no archive folder, so the message cannot be archived.",
    )

    fun missingTrashFolder(): String = text(
        "这个邮箱没有回收站文件夹，无法移到回收站。",
        "This mailbox has no trash folder, so the message cannot be moved to trash.",
    )

    fun invalidEmail(): String = text(
        "邮箱地址格式不正确，请检查后重试。",
        "That email address is not valid. Check it and try again.",
    )

    fun missingPassword(): String = text(
        "请填写授权码或应用专用密码。",
        "Enter the authorization code or app password.",
    )

    fun missingRecipients(): String = text(
        "收件人不能为空。",
        "Add at least one recipient.",
    )

    fun missingInbox(): String = text(
        "这个邮箱没有可用的收件箱文件夹，请检查邮箱设置。",
        "This mailbox has no usable inbox folder. Check the mailbox settings.",
    )

    fun missingDraft(): String = text(
        "草稿已不存在，可能已经发送或删除。",
        "This draft no longer exists. It may have been sent or deleted.",
    )

    fun attachmentUnavailable(): String = text(
        "无法打开这个附件，请稍后重试。",
        "This attachment could not be opened. Try again later.",
    )

    fun operationFailed(): String = text(
        "操作没有完成，请稍后重试。",
        "The action did not finish. Try again later.",
    )

    fun unknown(): String = text(
        "出了点问题，请稍后重试。",
        "Something went wrong. Try again later.",
    )

    fun messageTooLarge(): String = text(
        "这封邮件太大，无法完整加载。",
        "This message is too large to load in full.",
    )

    fun attachmentTooLarge(): String = text(
        "这个附件太大，无法下载。",
        "This attachment is too large to download.",
    )

    fun storageFailed(): String = text(
        "无法保存邮件内容，请检查手机存储空间。",
        "Could not save the message content. Check the device storage.",
    )

    fun describeImap(error: Throwable): String = describe(error, imapLoginFailed())

    fun describeSmtp(error: Throwable): String = describe(error, smtpLoginFailed())

    fun describe(error: Throwable, loginFailed: String = imapLoginFailed()): String = describeAny(error, loginFailed)

    fun describeAny(error: Throwable, loginFailed: String = imapLoginFailed()): String {
        runCatching {
            if (error !is MailLocalizedException) host.logs().warn("mail transport failure", error)
        }
        return runCatching {
            when (error) {
                is MailLocalizedException -> error.message.orEmpty()
                is MailTimeoutException -> timedOut(error.target)
                is MailTlsException -> tlsFailed(error.target)
                is MailNetworkException -> connectionFailed(error.target)
                is MailConnectionLostException -> connectionLost()
                is MailAuthenticationException -> loginFailed
                is MailServerException -> serverRejected()
                is MailStorageException -> storageFailed()
                else -> unknown()
            }
        }.getOrElse { fallbackText }
    }

    private val fallbackText: String = runCatching {
        text(
            "操作没有完成，请稍后重试。",
            "The action did not finish. Try again later.",
        )
    }.getOrDefault("")


    private fun text(zh: String, en: String): String =
        host.text(localizedText(default = zh, zh = zh, en = en))
}
