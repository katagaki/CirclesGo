package com.tsubuzaki.circlesgo.ui.unified

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.tsubuzaki.circlesgo.state.CatalogCache
import com.tsubuzaki.circlesgo.state.Events
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.UnifiedPath
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections
import com.tsubuzaki.circlesgo.ui.catalog.CatalogView
import com.tsubuzaki.circlesgo.ui.circledetail.CircleDetailView
import com.tsubuzaki.circlesgo.ui.favorites.FavoritesView

@Composable
fun UnifiedPanel(
    unifier: Unifier,
    events: Events,
    database: CatalogDatabase,
    favorites: FavoritesState,
    selections: UserSelections,
    mapper: Mapper,
    catalogCache: CatalogCache
) {
    val currentPath by unifier.currentPath.collectAsState()
    val sheetPath by unifier.sheetPath.collectAsState()
    val selectedCircle by unifier.selectedCircle.collectAsState()

    // Check if circle detail is showing (in sheet path stack)
    val isShowingCircleDetail = sheetPath.lastOrNull() == UnifiedPath.CIRCLE_DETAIL && selectedCircle != null

    Column(modifier = Modifier.fillMaxSize()) {
        if (isShowingCircleDetail) {
            // Circle detail view (pushed on top)
            CircleDetailView(
                circle = selectedCircle!!,
                database = database,
                favorites = favorites,
                mapper = mapper,
                unifier = unifier
            )
        } else {
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
                UnifiedPath.CIRCLES -> CatalogView(
                    database = database,
                    selections = selections,
                    favorites = favorites,
                    mapper = mapper,
                    unifier = unifier,
                    catalogCache = catalogCache
                )
                UnifiedPath.FAVORITES -> FavoritesView(
                    database = database,
                    favorites = favorites,
                    selections = selections,
                    unifier = unifier
                )
                else -> CatalogView(
                    database = database,
                    selections = selections,
                    favorites = favorites,
                    mapper = mapper,
                    unifier = unifier,
                    catalogCache = catalogCache
                )
            }
        }
    }
}
