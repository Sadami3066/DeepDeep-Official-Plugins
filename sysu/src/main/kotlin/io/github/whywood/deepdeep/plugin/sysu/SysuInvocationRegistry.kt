package io.github.whywood.deepdeep.plugin.sysu

internal class SysuInvocationRegistry(private val maxEntries: Int = MAX_ENTRIES) {
    private data class Entry(
        val sessionId: String,
        val invocation: SysuInvocation,
    )

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun record(sessionId: String, callId: String, invocation: SysuInvocation) {
        if (callId.isBlank()) return
        val key = key(sessionId, callId)
        entries.remove(key)
        entries[key] = Entry(sessionId, invocation)
        while (entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            if (!oldest.hasNext()) return
            oldest.next()
            oldest.remove()
        }
    }

    @Synchronized
    fun take(sessionId: String, callId: String): SysuInvocation? =
        entries.remove(key(sessionId, callId))?.invocation

    @Synchronized
    fun clearSession(sessionId: String) {
        entries.entries.removeAll { it.value.sessionId == sessionId }
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun key(sessionId: String, callId: String): String = "$sessionId\u0000$callId"

    companion object {
        const val MAX_ENTRIES = 64
    }
}
