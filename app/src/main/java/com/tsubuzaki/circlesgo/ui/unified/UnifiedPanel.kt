package com.tsubuzaki.circlesgo.ui.unified

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.Events
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.UnifiedPath
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections

@Composable
fun UnifiedPanel(
    unifier: Unifier,
    events: Events,
    database: CatalogDatabase,
    favorites: FavoritesState,
    selections: UserSelections,
    mapper: Mapper
) {
    val currentPath by unifier.currentPath.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Segmented picker: Circles / Favorites
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                SegmentedButton(
                    selected = currentPath == UnifiedPath.CIRCLES,
                    onClick = { unifier.setCurrentPath(UnifiedPath.CIRCLES) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text("Circles")
                }
                SegmentedButton(
                    selected = currentPath == UnifiedPath.FAVORITES,
                    onClick = { unifier.setCurrentPath(UnifiedPath.FAVORITES) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = events.isActiveEventLatest
                ) {
                    Text("Favorites")
                }
            }
        }

        // Content based on current path
        when (currentPath) {
            UnifiedPath.CIRCLES -> CatalogPlaceholder()
            UnifiedPath.FAVORITES -> FavoritesPlaceholder()
            else -> CatalogPlaceholder()
        }
    }
}

@Composable
private fun CatalogPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Circles",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Circle catalog will be displayed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FavoritesPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Favorites",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Favorited circles will be displayed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
