package com.tsubuzaki.circlesgo.ui.map

import android.graphics.PointF
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    val horizontalScrollState = rememberScrollState()
    val verticalScrollState = rememberScrollState()
    var currentZoom by remember { mutableFloatStateOf(zoomScale) }

    LaunchedEffect(zoomScale) {
        currentZoom = zoomScale
    }

    // Handle scroll-to-position requests
    LaunchedEffect(scrollToPosition) {
        scrollToPosition?.let { position ->
            val scaledX = (position.x * currentZoom).toInt()
            val scaledY = (position.y * currentZoom).toInt()

            val halfWidth = horizontalScrollState.viewportSize / 2
            val halfHeight = verticalScrollState.viewportSize / 2

            val targetX = maxOf(0, scaledX - halfWidth)
            val targetY = maxOf(0, scaledY - halfHeight)

            horizontalScrollState.animateScrollTo(targetX)
            verticalScrollState.animateScrollTo(targetY)
            onScrollCompleted()
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val newZoom = (currentZoom * zoom).coerceIn(0.5f, 3.0f)
                    currentZoom = newZoom
                    onZoomChange(newZoom)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .horizontalScroll(horizontalScrollState)
                .verticalScroll(verticalScrollState)
                .graphicsLayer(
                    scaleX = currentZoom,
                    scaleY = currentZoom
                )
        ) {
            content()
        }
    }
}
