package com.tsubuzaki.circlesgo.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

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

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Map",
        modifier = Modifier
            .width(canvasWidth.dp)
            .height(canvasHeight.dp),
        contentScale = ContentScale.Fit,
        colorFilter = if (isDarkTheme) ColorFilter.colorMatrix(invertColorMatrix) else null
    )
}
