package io.github.whywood.deepdeep.plugin.mail.ui

import java.util.concurrent.atomic.AtomicReference

internal object MailCardNavigation {
    internal sealed interface Request {
        data class OpenThread(
            val accountId: String,
            val messageId: String,
            val threadId: String,
        ) : Request

        data class EditDraft(val draftId: String) : Request
    }

    private const val FreshMillis = 60_000L

    private val pending = AtomicReference<Pair<Request, Long>?>(null)

    fun openThread(accountId: String, messageId: String, threadId: String) {
        if (accountId.isBlank() || messageId.isBlank()) return
        pending.set(Request.OpenThread(accountId, messageId, threadId) to System.currentTimeMillis())
    }

    fun editDraft(draftId: String) {
        if (draftId.isBlank()) return
        pending.set(Request.EditDraft(draftId) to System.currentTimeMillis())
    }

    fun consume(): Request? {
        val entry = pending.getAndSet(null) ?: return null
        val (request, requestedAt) = entry
        return request.takeIf { System.currentTimeMillis() - requestedAt <= FreshMillis }
    }
}
