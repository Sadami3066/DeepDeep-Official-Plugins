package io.github.whywood.deepdeep.plugin.hotel

internal data class HotelSnapshot(
    val installedVersion: String? = null,
    val loggedIn: Boolean = false,
    val account: String? = null,
    val checkedAt: Long = 0L,
    val linuxAvailable: Boolean = true,
)
