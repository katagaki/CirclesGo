package com.tsubuzaki.circlesgo.ui.map

import android.graphics.PointF
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun MapScrollableView(
    zoomScale: Float,
    onZoomChange: (Float) -> Unit,
    scrollToPosition: PointF?,
    onScrollCompleted: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var currentZoom by remember { mutableFloatStateOf(zoomScale) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(zoomScale) {
        currentZoom = zoomScale
    }

    // Handle scroll-to-position requests
    LaunchedEffect(scrollToPosition) {
        scrollToPosition?.let { position ->
            offsetX = -position.x * currentZoom
            offsetY = -position.y * currentZoom
            onScrollCompleted()
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newZoom = (currentZoom * zoom).coerceIn(0.5f, 3.0f)
                    currentZoom = newZoom
                    onZoomChange(newZoom)

                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer(
                    scaleX = currentZoom,
                    scaleY = currentZoom,
                    translationX = offsetX,
                    translationY = offsetY
                )
        ) {
            content()
        }
    }
}
