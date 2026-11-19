package io.github.whywood.superpi.plugin.themes

import io.github.whywood.superpi.plugin.api.SuperPIPlugin
import io.github.whywood.superpi.plugin.api.PluginAvailability
import io.github.whywood.superpi.plugin.api.PluginHost
import io.github.whywood.superpi.plugin.api.available

class ThemesPlugin : SuperPIPlugin {
    override suspend fun availability(host: PluginHost): PluginAvailability = available()
}
