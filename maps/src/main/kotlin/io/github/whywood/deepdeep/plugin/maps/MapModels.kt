package io.github.whywood.deepdeep.plugin.maps

import kotlinx.serialization.Serializable

@Serializable
internal data class MapToolDetails(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val zoom: Int,
)
