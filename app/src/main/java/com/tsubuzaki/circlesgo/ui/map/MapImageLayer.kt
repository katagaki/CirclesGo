package com.tsubuzaki.circlesgo.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize

private val invertColorMatrix = ColorMatrix(
    floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )
)

@Composable
fun MapImageLayer(
    bitmap: Bitmap,
    canvasWidth: Float,
    canvasHeight: Float
) {
    val isDarkTheme = isSystemInDarkTheme()
    val imageBitmap = bitmap.asImageBitmap()

    val colorFilter = if (isDarkTheme) ColorFilter.colorMatrix(invertColorMatrix) else null

    with(LocalDensity.current) {
        Canvas(modifier = Modifier.size(canvasWidth.toDp(), canvasHeight.toDp())) {
            val scaledWidth = imageBitmap.width * density
            val scaledHeight = imageBitmap.height * density

            drawImage(
                image = imageBitmap,
                dstSize = IntSize(scaledWidth.toInt(), scaledHeight.toInt()),
                colorFilter = colorFilter
            )
        }
    }
}
