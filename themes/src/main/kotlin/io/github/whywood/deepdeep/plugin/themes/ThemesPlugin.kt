package io.github.whywood.deepdeep.plugin.themes

import io.github.whywood.deepdeep.plugin.api.DeepDeepPlugin
import io.github.whywood.deepdeep.plugin.api.PluginAvailability
import io.github.whywood.deepdeep.plugin.api.PluginHost
import io.github.whywood.deepdeep.plugin.api.available

class ThemesPlugin : DeepDeepPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()
}
