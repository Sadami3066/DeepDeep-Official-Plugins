package io.github.whywood.deepdeep.plugin.ride

import io.github.whywood.deepdeep.plugin.api.PluginSettingsService
import io.github.whywood.deepdeep.plugin.api.SecretService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

internal class FakeSettingsService(initial: Map<String, String> = emptyMap()) : PluginSettingsService {
    private val values = MutableStateFlow(initial.toMap())

    val snapshotValues: Map<String, String> get() = values.value

    override suspend fun get(key: String): String? = values.value[key]

    override suspend fun put(key: String, value: String) {
        values.value = values.value + (key to value)
    }

    override suspend fun remove(key: String) {
        values.value = values.value - key
    }

    override suspend fun clear() {
        values.value = emptyMap()
    }

    override suspend fun keys(): Set<String> = values.value.keys

    override suspend fun snapshot(): Map<String, String> = values.value

    override suspend fun getJson(key: String): JsonObject? = null

    override suspend fun putJson(key: String, value: JsonObject) = Unit

    override fun observe(key: String?): Flow<Map<String, String>> =
        if (key == null) values else values.map { map -> map.filterKeys { it == key } }
}

internal class FakeSecretService(initial: Map<String, String> = emptyMap()) : SecretService {
    private val values = LinkedHashMap(initial)

    val snapshotValues: Map<String, String> get() = values.toMap()

    override suspend fun get(key: String): String? = values[key]

    override suspend fun put(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }

    override suspend fun clear() {
        values.clear()
    }
}
