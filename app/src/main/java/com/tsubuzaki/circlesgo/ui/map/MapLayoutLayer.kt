package com.tsubuzaki.circlesgo.ui.map

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.tables.LayoutCatalogMapping
import com.tsubuzaki.circlesgo.database.types.LayoutType
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.PopoverData

@Composable
fun MapLayoutLayer(
    layouts: Map<LayoutCatalogMapping, List<Int>>,
    spaceSize: Int,
    canvasWidth: Dp,
    canvasHeight: Dp,
    popoverData: PopoverData?,
    mapper: Mapper
) {
    val currentDensity = androidx.compose.ui.platform.LocalDensity.current.density
    Box(
        modifier = Modifier
            .width(canvasWidth)
            .height(canvasHeight)
    ) {
        // Draw selection highlight
        Canvas(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasHeight)
        ) {
            popoverData?.let { data ->
                val rect = data.sourceRect
                drawRect(
                    color = Color.Black.copy(alpha = 0.3f),
                    topLeft = Offset(rect.left.dp.toPx(), rect.top.dp.toPx()),
                    size = Size(rect.width().dp.toPx(), rect.height().dp.toPx())
                )
            }
        }

        // Tap interaction layer
        Box(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasHeight)
                .pointerInput(layouts, currentDensity) {
                    detectTapGestures { offset ->
                        val x = (offset.x / currentDensity).toInt()
                        val y = (offset.y / currentDensity).toInt()

                        for ((layout, webCatalogIDs) in layouts) {
                            val xMin = layout.positionX
                            val xMax = layout.positionX + spaceSize
                            val yMin = layout.positionY
                            val yMax = layout.positionY + spaceSize

                            if (x in xMin until xMax && y in yMin until yMax) {
                                val newPopover = PopoverData(
                                    layout = layout,
                                    webCatalogIDs = webCatalogIDs,
                                    reversed = layout.layoutType == LayoutType.A_ON_BOTTOM ||
                                            layout.layoutType == LayoutType.A_ON_RIGHT,
                                    sourceRect = RectF(
                                        xMin.toFloat(), yMin.toFloat(),
                                        xMax.toFloat(), yMax.toFloat()
                                    )
                                )
                                if (popoverData?.id == newPopover.id) {
                                    mapper.setPopoverData(null)
                                } else {
                                    mapper.setPopoverData(newPopover)
                                }
                                return@detectTapGestures
                            }
                        }
                        // Tapped outside any layout block
                        mapper.setPopoverData(null)
                    }
                }
        )
    }
}
