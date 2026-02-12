package com.tsubuzaki.circlesgo.ui.map

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.state.HighlightData
import com.tsubuzaki.circlesgo.state.Mapper
import kotlinx.coroutines.delay

@Composable
fun MapHighlightLayer(
    highlightData: HighlightData?,
    canvasWidth: Float,
    canvasHeight: Float,
    mapper: Mapper
) {
    var isVisible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (isVisible) 0.9f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "highlight_alpha"
    )

    LaunchedEffect(highlightData) {
        if (highlightData?.shouldBlink == true) {
            isVisible = true
            repeat(7) {
                delay(160)
                isVisible = !isVisible
            }
            mapper.setHighlightData(null)
            mapper.setHighlightTarget(null)
        } else {
            isVisible = true
        }
    }

    if (highlightData != null) {
        Canvas(
            modifier = Modifier
                .width(canvasWidth.dp)
                .height(canvasHeight.dp)
        ) {
            val rect = highlightData.sourceRect
            drawRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(rect.left.dp.toPx(), rect.top.dp.toPx()),
                size = Size(rect.width().dp.toPx(), rect.height().dp.toPx())
            )
        }
    }
}
