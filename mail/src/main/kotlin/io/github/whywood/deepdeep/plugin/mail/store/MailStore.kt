package io.github.whywood.deepdeep.plugin.mail.store

import io.github.whywood.deepdeep.plugin.mail.DraftAttachment
import io.github.whywood.deepdeep.plugin.mail.MailAccount
import io.github.whywood.deepdeep.plugin.mail.MailDraft
import io.github.whywood.deepdeep.plugin.mail.MailFolderRole
import io.github.whywood.deepdeep.plugin.mail.MailMessageSummary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
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

internal enum class DraftAttachmentFailure {
    SOURCE_MISSING,
    SOURCE_UNREADABLE,
    TOO_LARGE,
    TOTAL_TOO_LARGE,
}

internal class DraftAttachmentException(
    val failure: DraftAttachmentFailure,
    val attachmentName: String? = null,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

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

    fun importDraftAttachment(
        draftId: String,
        sourcePath: String,
        name: String,
        mimeType: String,
        maxBytes: Long,
        maxTotalBytes: Long,
    ): DraftAttachment {
        val source = File(sourcePath)
        if (!source.isFile) throw DraftAttachmentException(DraftAttachmentFailure.SOURCE_MISSING, name)
        val displayName = name.trim().ifBlank { source.name }.ifBlank { "attachment" }
        val size = source.length()
        if (size > maxBytes) throw DraftAttachmentException(DraftAttachmentFailure.TOO_LARGE, displayName)
        if (draftAttachmentTotalBytes(draftId) + size > maxTotalBytes) {
            throw DraftAttachmentException(DraftAttachmentFailure.TOTAL_TOO_LARGE, displayName)
        }
        val directory = draftAttachmentsDirectory(draftId)
        directory.mkdirs()
        val target = uniqueAttachmentFile(directory, displayName)
        val temporary = File(directory, target.name + DRAFT_PART_SUFFIX)
        temporary.delete()
        try {
            var copied = 0L
            source.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) throw DraftAttachmentException(DraftAttachmentFailure.TOO_LARGE, displayName)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            target.delete()
            if (!temporary.renameTo(target)) throw DraftAttachmentException(DraftAttachmentFailure.SOURCE_UNREADABLE, displayName)
            return DraftAttachment(
                path = target.absolutePath,
                name = displayName,
                mimeType = mimeType.trim().ifBlank { guessMimeType(displayName) },
                sizeBytes = copied,
            )
        } catch (error: DraftAttachmentException) {
            temporary.delete()
            throw error
        } catch (error: Exception) {
            temporary.delete()
            log("mail store: cannot import draft attachment", error)
            throw DraftAttachmentException(DraftAttachmentFailure.SOURCE_UNREADABLE, displayName, error)
        }
    }

    fun draftAttachmentTotalBytes(draftId: String): Long =
        draftAttachmentsDirectory(draftId).listFiles().orEmpty().filter { it.isFile }.sumOf { it.length() }

    fun removeDraftAttachmentFile(path: String) {
        val target = File(path)
        if (!isInsideDrafts(target)) return
        target.delete()
        target.parentFile?.let { parent ->
            if (parent.isDirectory && parent.listFiles().orEmpty().isEmpty()) parent.delete()
        }
    }

    fun pruneDraftAttachments(draftId: String, validPaths: Set<String>) {
        val directory = draftAttachmentsDirectory(draftId)
        val valid = validPaths.map { File(it).canonicalFile }.toSet()
        val dirty = directory.listFiles().orEmpty().any { file ->
            val keep = file.isFile && file.canonicalFile in valid
            if (!keep) file.delete()
            !keep
        }
        if (dirty && directory.isDirectory && directory.listFiles().orEmpty().isEmpty()) directory.delete()
    }

    fun deleteDraftData(draftId: String) {
        draftDirectory(draftId).deleteRecursively()
    }

    fun retainDraftData(validDraftIds: Set<String>) {
        val valid = validDraftIds.map { safeSegment(it) }.toSet()
        val now = System.currentTimeMillis()
        draftsDirectory().listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory) {
                directory.delete()
                return@forEach
            }
            if (directory.name !in valid) {
                directory.deleteRecursively()
                return@forEach
            }
            directory.walkTopDown().filter { it.isFile && it.name.endsWith(DRAFT_PART_SUFFIX) }.forEach { part ->
                if (now - part.lastModified() > STALE_PART_MILLIS) part.delete()
            }
        }
    }

    fun outgoingTempFile(): File {
        val directory = tempDirectory()
        directory.mkdirs()
        return File(directory, "outgoing-" + UUID.randomUUID().toString() + TEMP_SUFFIX)
    }

    fun cleanupOutgoingTemp(maxAgeMillis: Long = STALE_PART_MILLIS) {
        val now = System.currentTimeMillis()
        tempDirectory().listFiles().orEmpty().forEach { file ->
            if (file.isFile && now - file.lastModified() > maxAgeMillis) file.delete()
        }
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
        val now = System.currentTimeMillis()
        for (file in files) {
            if (!file.isFile) continue
            if (file.name.endsWith(PART_SUFFIX)) {
                if (now - file.lastModified() > STALE_PART_MILLIS) file.delete()
                continue
            }
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

    fun inlineImageTempFile(): File {
        val directory = inlineImageDirectory()
        directory.mkdirs()
        return File(directory, "tmp-" + UUID.randomUUID().toString() + ".part")
    }

    fun inlineImageFile(messageKey: String, contentId: String, name: String): File =
        File(inlineImageDirectory(), inlineImageKey(messageKey, contentId) + inlineImageExtension(name))

    fun touchInlineImage(file: File) {
        runCatching { file.setLastModified(System.currentTimeMillis()) }
    }

    fun commitInlineImage(source: File, target: File, maxTotalBytes: Long = INLINE_CACHE_MAX_BYTES): File? {
        target.parentFile?.mkdirs()
        target.delete()
        val moved = runCatching {
            if (source.renameTo(target)) {
                true
            } else {
                source.copyTo(target, overwrite = true)
                source.delete()
                true
            }
        }.getOrDefault(false)
        if (!moved || !target.isFile) {
            source.delete()
            return null
        }
        touchInlineImage(target)
        evictInlineImages(maxTotalBytes)
        return target
    }

    fun evictInlineImages(maxTotalBytes: Long = INLINE_CACHE_MAX_BYTES) {
        val files = inlineImageDirectory().listFiles().orEmpty()
            .filter { it.isFile && !it.name.endsWith(".part") }
        var total = files.sumOf { it.length() }
        if (total <= maxTotalBytes) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxTotalBytes) return@forEach
            total -= file.length()
            file.delete()
        }
    }

    fun clearInlineImages() {
        inlineImageDirectory().deleteRecursively()
    }

    fun retainAttachmentDirectories(accountId: String, validKeys: Set<String>) {
        val directories = attachmentsDirectory(accountId).listFiles() ?: return
        for (directory in directories) {
            if (directory.isDirectory && directory.name !in validKeys) directory.deleteRecursively()
        }
    }

    private fun inlineImageDirectory(): File = File(File(root, "cache"), "inline")

    private fun inlineImageKey(messageKey: String, contentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$messageKey|${contentId.lowercase()}".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun inlineImageExtension(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        return if (extension.isEmpty()) ".img" else ".$extension"
    }

    private fun draftsDirectory(): File = File(root, "drafts")

    private fun draftDirectory(draftId: String): File = File(draftsDirectory(), safeSegment(draftId))

    private fun draftAttachmentsDirectory(draftId: String): File = File(draftDirectory(draftId), "attachments")

    private fun tempDirectory(): File = File(root, "tmp")

    private fun uniqueAttachmentFile(directory: File, name: String): File {
        val base = safeFileName(name)
        val extension = base.substringAfterLast('.', "").let { if (it.isEmpty() || it == base) "" else ".$it" }
        val stem = if (extension.isEmpty()) base else base.dropLast(extension.length)
        var index = 1
        while (true) {
            val candidate = File(directory, if (index == 1) base else "$stem-$index$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun guessMimeType(name: String): String =
        MIME_TYPES[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun isInsideDrafts(file: File): Boolean = try {
        val base = draftsDirectory().canonicalFile
        file.canonicalFile.toPath().startsWith(base.toPath())
    } catch (error: Exception) {
        false
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
        const val PART_SUFFIX = ".eml.part"
        const val DRAFT_PART_SUFFIX = ".part"
        const val TEMP_SUFFIX = ".tmp"
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val STALE_PART_MILLIS = 60L * 60 * 1000
        const val INLINE_CACHE_MAX_BYTES = 64L * 1024 * 1024
        val MIME_TYPES = mapOf(
            "pdf" to "application/pdf",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "webp" to "image/webp",
            "txt" to "text/plain",
            "csv" to "text/csv",
            "json" to "application/json",
            "xml" to "application/xml",
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "mp3" to "audio/mpeg",
            "mp4" to "video/mp4",
            "ics" to "text/calendar",
            "vcf" to "text/vcard",
        )
    }
}
