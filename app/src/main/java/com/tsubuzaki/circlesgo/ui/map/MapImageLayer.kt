package com.tsubuzaki.circlesgo.ui.map

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun MapImageLayer(
    bitmap: Bitmap,
    canvasWidth: Float,
    canvasHeight: Float
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Map",
        modifier = Modifier
            .width(canvasWidth.dp)
            .height(canvasHeight.dp),
        contentScale = ContentScale.Fit
    )
}
