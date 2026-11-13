package io.github.whywood.deepdeep.plugin.mail.store

import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class MailServerSettings(
    val accountId: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
)

@Serializable
internal data class MailFolderIndex(
    val role: MailFolderRole,
    val remoteName: String,
    val uidValidity: Long = 0,
    val lastUid: Long = 0,
)

@Serializable
internal data class MailAccountIndex(
    val accountId: String,
    val folders: List<MailFolderIndex> = emptyList(),
    val messages: List<MailMessageSummary> = emptyList(),
    val updatedAt: Long = 0,
)

internal class MailStore(
    val root: File,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun accounts(): List<MailAccount> = readJson(accountsFile(), emptyList())

    fun saveAccounts(accounts: List<MailAccount>) {
        writeJson(accountsFile(), json.encodeToString(accounts))
    }

    fun servers(): List<MailServerSettings> = readJson(serversFile(), emptyList())

    fun saveServers(servers: List<MailServerSettings>) {
        writeJson(serversFile(), json.encodeToString(servers))
    }

    fun drafts(): List<MailDraft> = readJson(draftsFile(), emptyList())

    fun saveDrafts(drafts: List<MailDraft>) {
        writeJson(draftsFile(), json.encodeToString(drafts))
    }

    fun index(accountId: String): MailAccountIndex =
        readJson(indexFile(accountId), MailAccountIndex(accountId = accountId))

    fun saveIndex(index: MailAccountIndex) {
        writeJson(indexFile(index.accountId), json.encodeToString(index))
    }

    fun deleteAccount(accountId: String) {
        indexFile(accountId).delete()
        bodiesDirectory(accountId).deleteRecursively()
        attachmentsDirectory(accountId).deleteRecursively()
    }

    fun bodyFile(accountId: String, messageKey: String): File =
        File(bodiesDirectory(accountId), messageKey + BODY_SUFFIX)

    fun readBody(accountId: String, messageKey: String): ByteArray? {
        val file = bodyFile(accountId, messageKey)
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (error: Exception) {
            log("mail store: cannot read cached body", error)
            null
        }
    }

    fun writeBody(accountId: String, messageKey: String, bytes: ByteArray) {
        val file = bodyFile(accountId, messageKey)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) throw IllegalStateException("cannot cache message body")
            }
        } catch (error: Exception) {
            temporary.delete()
            log("mail store: cannot write cached body", error)
            throw error
        }
    }

    fun deleteBody(accountId: String, messageKey: String) {
        bodyFile(accountId, messageKey).delete()
    }

    fun retainBodies(accountId: String, validKeys: Set<String>) {
        val files = bodiesDirectory(accountId).listFiles() ?: return
        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.endsWith(BODY_SUFFIX)) continue
            val key = file.name.removeSuffix(BODY_SUFFIX)
            if (key !in validKeys) file.delete()
        }
    }

    fun attachmentDirectory(accountId: String, messageKey: String): File =
        File(attachmentsDirectory(accountId), messageKey)

    fun attachmentFile(accountId: String, messageKey: String, attachmentId: String, name: String): File =
        File(attachmentDirectory(accountId, messageKey), attachmentId + "-" + safeFileName(name))

    fun deleteAttachmentDirectory(accountId: String, messageKey: String) {
        attachmentDirectory(accountId, messageKey).deleteRecursively()
    }

    fun retainAttachmentDirectories(accountId: String, validKeys: Set<String>) {
        val directories = attachmentsDirectory(accountId).listFiles() ?: return
        for (directory in directories) {
            if (directory.isDirectory && directory.name !in validKeys) directory.deleteRecursively()
        }
    }

    private fun accountsFile(): File = File(root, "accounts.json")

    private fun serversFile(): File = File(root, "servers.json")

    private fun draftsFile(): File = File(root, "drafts.json")

    private fun indexFile(accountId: String): File = File(root, "index-" + safeSegment(accountId) + ".json")

    private fun bodiesDirectory(accountId: String): File = File(File(root, "bodies"), safeSegment(accountId))

    private fun attachmentsDirectory(accountId: String): File =
        File(File(root, "attachments"), safeSegment(accountId))

    private fun writeJson(file: File, value: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(value.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                file.delete()
                if (!temporary.renameTo(file)) throw IllegalStateException("cannot write ${file.name}")
            }
        } catch (error: Exception) {
            temporary.delete()
            log("mail store: cannot write ${file.name}", error)
            throw error
        }
    }

    private inline fun <reified T> readJson(file: File, default: T): T {
        if (!file.isFile) return default
        return try {
            json.decodeFromString<T>(file.readText(Charsets.UTF_8))
        } catch (error: Exception) {
            log("mail store: cannot read ${file.name}", error)
            default
        }
    }

    private fun safeSegment(value: String): String {
        val cleaned = value.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return cleaned.ifEmpty { "unknown" }
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.map { if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('.', '_')
            .take(80)
        return cleaned.ifEmpty { "attachment" }
    }

    private companion object {
        const val BODY_SUFFIX = ".eml"
        const val TEMP_SUFFIX = ".tmp"
    }
}
