package io.github.whywood.superpi.plugin.amap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapMapPreviewTest {
    private val ready = AmapMapLoad.Ready("/data/map-cache/a.png")
    private val retryableFailure = AmapMapLoad.Unavailable("load failed", retryable = true)
    private val permissionFailure = AmapMapLoad.Unavailable("static map service is not enabled", retryable = false)
    private val mapUrl = "https://uri.amap.com/marker?position=120.1,30.2"

    @Test
    fun loadingOffersNoActions() {
        val actions = mapPreviewActions(AmapMapLoad.Loading, openUrl = mapUrl, imageFailed = false)

        assertFalse(actions.showRetry)
        assertFalse(actions.showOpenInMaps)
        assertFalse(actions.imageTapOpensMaps)
    }

    @Test
    fun readyMapOpensOnImageTapWithoutRetry() {
        val actions = mapPreviewActions(ready, openUrl = mapUrl, imageFailed = false)

        assertTrue(actions.imageTapOpensMaps)
        assertFalse(actions.showRetry)
        assertFalse(actions.showOpenInMaps)
    }

    @Test
    fun readyMapWithoutUrlIsNotTappable() {
        val actions = mapPreviewActions(ready, openUrl = null, imageFailed = false)

        assertFalse(actions.imageTapOpensMaps)
        assertFalse(actions.showOpenInMaps)
        assertFalse(actions.showRetry)
    }

    @Test
    fun retryableFailureShowsRetryOutsideImageTap() {
        val actions = mapPreviewActions(retryableFailure, openUrl = mapUrl, imageFailed = false)

        assertTrue(actions.showRetry)
        assertTrue(actions.showOpenInMaps)
        assertFalse(actions.imageTapOpensMaps)
    }

    @Test
    fun permissionFailureOpensInMapsWithoutRetry() {
        val actions = mapPreviewActions(permissionFailure, openUrl = mapUrl, imageFailed = false)

        assertFalse(actions.showRetry)
        assertTrue(actions.showOpenInMaps)
        assertFalse(actions.imageTapOpensMaps)
    }

    @Test
    fun decodeFailureShowsRetryAndOpenInsteadOfImageTap() {
        val actions = mapPreviewActions(ready, openUrl = mapUrl, imageFailed = true)

        assertTrue(actions.showRetry)
        assertTrue(actions.showOpenInMaps)
        assertFalse(actions.imageTapOpensMaps)
    }

    @Test
    fun decodeFailureWithoutUrlShowsRetryOnly() {
        val actions = mapPreviewActions(ready, openUrl = null, imageFailed = true)

        assertTrue(actions.showRetry)
        assertFalse(actions.showOpenInMaps)
        assertFalse(actions.imageTapOpensMaps)
    }
}
