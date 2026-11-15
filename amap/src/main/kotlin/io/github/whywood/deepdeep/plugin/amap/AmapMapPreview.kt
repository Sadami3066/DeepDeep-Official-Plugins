package io.github.whywood.deepdeep.plugin.amap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.whywood.deepdeep.design.DdIcon
import io.github.whywood.deepdeep.design.DdIcons
import io.github.whywood.deepdeep.design.DdImage
import io.github.whywood.deepdeep.design.DdImageViewer
import io.github.whywood.deepdeep.design.DdPressable
import io.github.whywood.deepdeep.design.DdShapes
import io.github.whywood.deepdeep.design.DdSpacing
import io.github.whywood.deepdeep.design.DdSpinner
import io.github.whywood.deepdeep.design.DdText
import io.github.whywood.deepdeep.design.DdTextAction
import io.github.whywood.deepdeep.design.DdType
import io.github.whywood.deepdeep.design.deepDeepColors
import io.github.whywood.deepdeep.plugin.api.PluginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AmapMapPreviewActions(
    val showRetry: Boolean,
    val showOpenInMaps: Boolean,
    val imageTapOpensMaps: Boolean,
)

internal fun mapPreviewActions(
    load: AmapMapLoad,
    openUrl: String?,
    imageFailed: Boolean,
): AmapMapPreviewActions = when (load) {
    AmapMapLoad.Loading -> AmapMapPreviewActions(
        showRetry = false,
        showOpenInMaps = false,
        imageTapOpensMaps = false,
    )
    is AmapMapLoad.Ready -> if (imageFailed) {
        AmapMapPreviewActions(
            showRetry = true,
            showOpenInMaps = openUrl != null,
            imageTapOpensMaps = false,
        )
    } else {
        AmapMapPreviewActions(
            showRetry = false,
            showOpenInMaps = false,
            imageTapOpensMaps = openUrl != null,
        )
    }
    is AmapMapLoad.Unavailable -> AmapMapPreviewActions(
        showRetry = load.retryable,
        showOpenInMaps = openUrl != null,
        imageTapOpensMaps = false,
    )
}

@Composable
internal fun AmapMapPreview(
    host: PluginHost,
    spec: AmapMapSpec,
    openUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    shape: Shape = DdShapes.Medium,
) {
    val colors = deepDeepColors()
    val loader = remember(host) {
        AmapMapLoader(
            dataDir = host.dataDir,
            network = runCatching { host.network() }.getOrNull(),
            keyProvider = { AmapKeyStore(host.settings(), host.secrets()).currentKey() },
            localizer = host.localizer(),
        )
    }
    var attempt by remember(spec) { mutableIntStateOf(0) }
    var imageFailed by remember(spec, attempt) { mutableStateOf(false) }
    val load by produceState<AmapMapLoad>(AmapMapLoad.Loading, spec, attempt) {
        value = withContext(Dispatchers.IO) { loader.load(spec, force = attempt > 0) }
    }
    var viewerPath by remember(spec) { mutableStateOf<String?>(null) }
    val actions = mapPreviewActions(load, openUrl, imageFailed)

    fun openInMaps() {
        if (openUrl != null) runCatching { host.ui().openUrl(openUrl) }
    }

    val retry: () -> Unit = { attempt++ }
    val openLabel = if (actions.showOpenInMaps) host.t("在高德地图中打开", "Open in Amap Maps") else null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(AMAP_MAP_ASPECT_RATIO)
            .clip(shape)
            .background(colors.wash),
    ) {
        when (val current = load) {
            AmapMapLoad.Loading -> MapLoading(host)
            is AmapMapLoad.Ready -> if (imageFailed) {
                MapStatus(
                    host = host,
                    reason = host.t("地图图片无法显示。", "The map image cannot be displayed."),
                    showRetry = actions.showRetry,
                    onRetry = retry,
                    openLabel = openLabel,
                    onOpen = ::openInMaps,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    DdPressable(
                        onClick = ::openInMaps,
                        modifier = Modifier.fillMaxSize(),
                        shape = shape,
                        enabled = actions.imageTapOpensMaps,
                        contentDescription = contentDescription,
                    ) {
                        DdImage(
                            path = current.path,
                            contentDescription = contentDescription,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            shape = shape,
                            failure = {
                                LaunchedEffect(Unit) { imageFailed = true }
                            },
                        )
                    }
                    DdPressable(
                        onClick = { viewerPath = current.path },
                        modifier = Modifier.align(Alignment.TopEnd).padding(DdSpacing.Sm).size(44.dp),
                        shape = CircleShape,
                        background = colors.raised,
                        contentDescription = host.t("全屏查看地图", "View map full screen"),
                    ) {
                        DdIcon(
                            icon = DdIcons.Expand,
                            tint = colors.inkSecondary,
                            modifier = Modifier.align(Alignment.Center),
                            size = 16.dp,
                        )
                    }
                }
            }
            is AmapMapLoad.Unavailable -> MapStatus(
                host = host,
                reason = current.reason,
                showRetry = actions.showRetry,
                onRetry = retry,
                openLabel = openLabel,
                onOpen = ::openInMaps,
            )
        }
    }

    viewerPath?.let { path ->
        DdImageViewer(
            path = path,
            onDismiss = { viewerPath = null },
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun MapLoading(host: PluginHost) {
    Column(
        modifier = Modifier.fillMaxSize().padding(DdSpacing.Md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DdSpinner(color = deepDeepColors().inkTertiary, size = 16.dp, strokeWidth = 1.5.dp)
        Spacer(Modifier.height(DdSpacing.Sm))
        DdText(
            text = host.t("正在加载地图…", "Loading the map…"),
            style = DdType.micro,
            color = deepDeepColors().inkTertiary,
        )
    }
}

@Composable
private fun MapStatus(
    host: PluginHost,
    reason: String,
    showRetry: Boolean,
    onRetry: () -> Unit,
    openLabel: String?,
    onOpen: () -> Unit,
) {
    val colors = deepDeepColors()
    Column(
        modifier = Modifier.fillMaxSize().padding(DdSpacing.Md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            DdText(
                text = reason,
                style = DdType.micro,
                color = colors.inkTertiary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showRetry || openLabel != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(DdSpacing.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showRetry) {
                    DdTextAction(
                        text = host.t("重试", "Retry"),
                        onClick = onRetry,
                    )
                }
                if (openLabel != null) {
                    DdTextAction(
                        text = openLabel,
                        onClick = onOpen,
                        color = colors.inkSecondary,
                    )
                }
            }
        }
    }
}

private const val AMAP_MAP_ASPECT_RATIO = 16f / 9f
