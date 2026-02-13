package com.tsubuzaki.circlesgo.ui.map

import android.graphics.PointF
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize

import androidx.compose.foundation.layout.requiredSize

@Composable
fun MapScrollableView(
    zoomScale: Float,
    onZoomChange: (Float) -> Unit,
    scrollToPosition: PointF?,
    onScrollCompleted: () -> Unit,
    canvasWidth: Dp,
    canvasHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var currentZoom by remember { mutableFloatStateOf(zoomScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(zoomScale) {
        currentZoom = zoomScale
    }

    val density = LocalDensity.current
    fun getCoercedOffset(offset: Offset): Offset {
        val zoomedWidth = with(density) { (canvasWidth * currentZoom).toPx() }
        val zoomedHeight = with(density) { (canvasHeight * currentZoom).toPx() }

        val minX = (viewportSize.width - zoomedWidth).coerceAtMost(0f)
        val minY = (viewportSize.height - zoomedHeight).coerceAtMost(0f)

        return Offset(offset.x.coerceIn(minX, 0f), offset.y.coerceIn(minY, 0f))
    }

    LaunchedEffect(scrollToPosition, currentZoom, viewportSize) {
        scrollToPosition?.let {
            if (viewportSize != IntSize.Zero) {
                val newOffset = Offset(
                    x = (viewportSize.width / 2) - (it.x * currentZoom),
                    y = (viewportSize.height / 2) - (it.y * currentZoom)
                )
                offset = getCoercedOffset(newOffset)
                onScrollCompleted()
            }
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val oldZoom = currentZoom
                    val newZoom = (currentZoom * zoom).coerceIn(0.8f, 3.0f)
                    onZoomChange(newZoom)
                    currentZoom = newZoom

                    val gestureZoom = newZoom / oldZoom
                    val newOffset =
                        offset * gestureZoom + centroid * (1 - gestureZoom) + pan

                    offset = getCoercedOffset(newOffset)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .requiredSize(canvasWidth, canvasHeight)
                .graphicsLayer(
                    scaleX = currentZoom,
                    scaleY = currentZoom,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                )
        ) {
            content()
        }
    }
}
