package com.tsubuzaki.circlesgo.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.database.tables.ComiketCircle
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.GridDisplayMode
import com.tsubuzaki.circlesgo.ui.shared.CircleCutImage

@Composable
fun CircleGrid(
    circles: List<ComiketCircle>,
    displayMode: GridDisplayMode,
    database: CatalogDatabase,
    favorites: FavoritesState,
    showSpaceName: Boolean = false,
    showDay: Boolean = false,
    showsOverlayWhenEmpty: Boolean = true,
    onSelect: (ComiketCircle) -> Unit
) {
    val minSize = when (displayMode) {
        GridDisplayMode.BIG -> 110.dp
        GridDisplayMode.MEDIUM -> 76.dp
        GridDisplayMode.SMALL -> 48.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = circles,
                key = { _, circle -> circle.id }
            ) { _, circle ->
                Box(
                    modifier = Modifier
                        .padding(0.5.dp)
                        .clickable { onSelect(circle) }
                ) {
                    CircleCutImage(
                        circle = circle,
                        database = database,
                        favorites = favorites,
                        displayMode = displayMode,
                        showSpaceName = showSpaceName,
                        showDay = showDay
                    )
                }
            }
        }

        if (circles.isEmpty() && showsOverlayWhenEmpty) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No circles found.\nTry adjusting filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
