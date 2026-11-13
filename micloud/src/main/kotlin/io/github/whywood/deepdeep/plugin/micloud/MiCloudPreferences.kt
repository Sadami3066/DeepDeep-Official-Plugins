package io.github.whywood.deepdeep.plugin.micloud

import io.github.whywood.deepdeep.plugin.api.PluginHost
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal enum class MiCloudFeature {
    NOTES,
    TODOS,
    SMS,
    CONTACTS,
    GALLERY,
    RECORDINGS,
    ACCOUNT,
}

internal data class MiCloudCapabilities(
    val notesRead: Boolean = false,
    val notesWrite: Boolean = false,
    val todosRead: Boolean = false,
    val todosWrite: Boolean = false,
    val smsRead: Boolean = false,
    val contactsRead: Boolean = false,
    val galleryRead: Boolean = false,
    val recordingsRead: Boolean = false,
    val accountRead: Boolean = false,
)

internal val MiCloudFeature.writable: Boolean
    get() = this == MiCloudFeature.NOTES || this == MiCloudFeature.TODOS

internal fun MiCloudCapabilities.readEnabled(feature: MiCloudFeature): Boolean = when (feature) {
    MiCloudFeature.NOTES -> notesRead
    MiCloudFeature.TODOS -> todosRead
    MiCloudFeature.SMS -> smsRead
    MiCloudFeature.CONTACTS -> contactsRead
    MiCloudFeature.GALLERY -> galleryRead
    MiCloudFeature.RECORDINGS -> recordingsRead
    MiCloudFeature.ACCOUNT -> accountRead
}

internal fun MiCloudCapabilities.writeEnabled(feature: MiCloudFeature): Boolean = when (feature) {
    MiCloudFeature.NOTES -> notesWrite
    MiCloudFeature.TODOS -> todosWrite
    else -> false
}

private fun MiCloudCapabilities.withRead(feature: MiCloudFeature, enabled: Boolean): MiCloudCapabilities =
    when (feature) {
        MiCloudFeature.NOTES -> if (enabled) {
            copy(notesRead = true)
        } else {
            copy(notesRead = false, notesWrite = false)
        }

        MiCloudFeature.TODOS -> if (enabled) {
            copy(todosRead = true)
        } else {
            copy(todosRead = false, todosWrite = false)
        }

        MiCloudFeature.SMS -> copy(smsRead = enabled)
        MiCloudFeature.CONTACTS -> copy(contactsRead = enabled)
        MiCloudFeature.GALLERY -> copy(galleryRead = enabled)
        MiCloudFeature.RECORDINGS -> copy(recordingsRead = enabled)
        MiCloudFeature.ACCOUNT -> copy(accountRead = enabled)
    }

private fun MiCloudCapabilities.withWrite(feature: MiCloudFeature, enabled: Boolean): MiCloudCapabilities =
    when (feature) {
        MiCloudFeature.NOTES -> if (enabled) {
            copy(notesRead = true, notesWrite = true)
        } else {
            copy(notesWrite = false)
        }

        MiCloudFeature.TODOS -> if (enabled) {
            copy(todosRead = true, todosWrite = true)
        } else {
            copy(todosWrite = false)
        }

        else -> this
    }

internal interface MiCloudSettings {
    val capabilities: StateFlow<MiCloudCapabilities>

    fun snapshot(): MiCloudCapabilities = capabilities.value

    suspend fun setRead(feature: MiCloudFeature, enabled: Boolean)

    suspend fun setWrite(feature: MiCloudFeature, enabled: Boolean)

    suspend fun setFeature(feature: MiCloudFeature, enabled: Boolean)

    suspend fun clear()
}

internal class MiCloudPreferences(private val host: PluginHost) : MiCloudSettings {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(MiCloudCapabilities())

    @Volatile
    private var loaded = false

    override val capabilities: StateFlow<MiCloudCapabilities> = mutable.asStateFlow()

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            mutable.value = readStored()
            loaded = true
        }
    }

    override suspend fun setRead(feature: MiCloudFeature, enabled: Boolean) {
        ensureLoaded()
        update { current -> current.withRead(feature, enabled) }
    }

    override suspend fun setWrite(feature: MiCloudFeature, enabled: Boolean) {
        ensureLoaded()
        update { current -> current.withWrite(feature, enabled) }
    }

    override suspend fun setFeature(feature: MiCloudFeature, enabled: Boolean) {
        ensureLoaded()
        update { current ->
            if (feature.writable) {
                if (enabled) {
                    current.withRead(feature, true).withWrite(feature, true)
                } else {
                    current.withRead(feature, false)
                }
            } else {
                current.withRead(feature, enabled)
            }
        }
    }

    override suspend fun clear() {
        ensureLoaded()
        mutex.withLock {
            mutable.value = MiCloudCapabilities()
            persist(MiCloudCapabilities())
        }
    }

    private suspend fun update(transform: (MiCloudCapabilities) -> MiCloudCapabilities) {
        mutex.withLock {
            val current = mutable.value
            val next = transform(current)
            if (next == current) return
            mutable.value = next
            persist(next)
        }
    }

    private suspend fun readStored(): MiCloudCapabilities {
        val stored = runCatching { host.settings().getJson(KEY) }.getOrNull()
        if (stored != null) return runCatching { decode(stored) }.getOrDefault(MiCloudCapabilities())
        val legacy = File(host.dataDir, LEGACY_FILE)
        if (!legacy.isFile) return MiCloudCapabilities()
        val migrated = runCatching {
            decode(Json.parseToJsonElement(legacy.readText()).jsonObject)
        }.getOrDefault(MiCloudCapabilities())
        persist(migrated)
        runCatching { legacy.delete() }
        return migrated
    }

    private suspend fun persist(capabilities: MiCloudCapabilities) {
        runCatching { host.settings().putJson(KEY, encode(capabilities)) }
    }

    private fun encode(capabilities: MiCloudCapabilities): JsonObject = buildJsonObject {
        put("notesRead", capabilities.notesRead)
        put("notesWrite", capabilities.notesWrite)
        put("todosRead", capabilities.todosRead)
        put("todosWrite", capabilities.todosWrite)
        put("smsRead", capabilities.smsRead)
        put("contactsRead", capabilities.contactsRead)
        put("galleryRead", capabilities.galleryRead)
        put("recordingsRead", capabilities.recordingsRead)
        put("accountRead", capabilities.accountRead)
    }

    private fun decode(obj: JsonObject): MiCloudCapabilities {
        fun flag(key: String): Boolean = (obj[key] as? JsonPrimitive)?.booleanOrNull ?: false
        val notes = flag("notesRead") || flag("notesWrite")
        val todos = flag("todosRead") || flag("todosWrite")
        return MiCloudCapabilities(
            notesRead = notes,
            notesWrite = notes,
            todosRead = todos,
            todosWrite = todos,
            smsRead = flag("smsRead"),
            contactsRead = flag("contactsRead"),
            galleryRead = flag("galleryRead"),
            recordingsRead = flag("recordingsRead"),
            accountRead = flag("accountRead"),
        )
    }

    private companion object {
        const val KEY = "capabilities"
        const val LEGACY_FILE = "capabilities.json"
    }
}
