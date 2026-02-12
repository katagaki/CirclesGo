package com.tsubuzaki.circlesgo.ui.favorites

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.database.tables.ComiketCircle
import com.tsubuzaki.circlesgo.state.CircleDisplayMode
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.GridDisplayMode
import com.tsubuzaki.circlesgo.state.ListDisplayMode
import com.tsubuzaki.circlesgo.state.UserSelections
import com.tsubuzaki.circlesgo.ui.catalog.CircleGrid
import com.tsubuzaki.circlesgo.ui.catalog.CircleList
import com.tsubuzaki.circlesgo.ui.catalog.ColorGroupedCircleGrid
import com.tsubuzaki.circlesgo.ui.catalog.ColorGroupedCircleList
import com.tsubuzaki.circlesgo.ui.catalog.DisplayModeSwitcher
import com.tsubuzaki.circlesgo.ui.catalog.GridModeSwitcher
import com.tsubuzaki.circlesgo.ui.catalog.ListModeSwitcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun FavoritesView(
    database: CatalogDatabase,
    favorites: FavoritesState,
    selections: UserSelections
) {
    val scope = rememberCoroutineScope()
    val favoriteCircles by favorites.circles.collectAsState()
    val favoriteItems by favorites.items.collectAsState()
    val isGroupedByColor by favorites.isGroupedByColor.collectAsState()
    val selectedDate by selections.date.collectAsState()

    var displayMode by rememberSaveable { mutableStateOf(CircleDisplayMode.GRID) }
    var gridDisplayMode by rememberSaveable { mutableStateOf(GridDisplayMode.MEDIUM) }
    var listDisplayMode by rememberSaveable { mutableStateOf(ListDisplayMode.REGULAR) }

    // Prepare circles when favorite items or selected date changes
    LaunchedEffect(favoriteItems, selectedDate) {
        val items = favoriteItems ?: return@LaunchedEffect
        scope.launch(Dispatchers.IO) {
            prepareCircles(items, favorites, selections, database)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar row: display mode switchers + group by color toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DisplayModeSwitcher(
                mode = displayMode,
                onModeChanged = { displayMode = it }
            )
            when (displayMode) {
                CircleDisplayMode.GRID -> {
                    GridModeSwitcher(
                        mode = gridDisplayMode,
                        onModeChanged = { gridDisplayMode = it }
                    )
                }
                CircleDisplayMode.LIST -> {
                    ListModeSwitcher(
                        mode = listDisplayMode,
                        onModeChanged = { listDisplayMode = it }
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { favorites.toggleGroupByColor() }) {
                Icon(
                    imageVector = Icons.Filled.Palette,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(18.dp)
                )
                Text(
                    text = "Group by Color",
                    fontSize = 13.sp
                )
            }
        }

        // Content area
        Box(modifier = Modifier.fillMaxSize()) {
            val circles = favoriteCircles
            if (circles == null) {
                // Loading or no favorites loaded yet
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (favoriteItems != null) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else {
                        Text(
                            text = "No favorites loaded.\nSign in and sync to view favorites.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else if (circles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No favorites yet.\nAdd favorites from the web catalog.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                AnimatedContent(
                    targetState = Triple(displayMode, isGroupedByColor, circles),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "favoritesContent"
                ) { (mode, grouped, circleGroups) ->
                    if (grouped) {
                        when (mode) {
                            CircleDisplayMode.GRID -> {
                                ColorGroupedCircleGrid(
                                    groups = circleGroups,
                                    displayMode = gridDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    showsOverlayWhenEmpty = false,
                                    onSelect = { circle ->
                                        // TODO: Navigate to circle detail
                                    }
                                )
                            }
                            CircleDisplayMode.LIST -> {
                                ColorGroupedCircleList(
                                    groups = circleGroups,
                                    displayMode = listDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    showsOverlayWhenEmpty = false,
                                    onSelect = { circle ->
                                        // TODO: Navigate to circle detail
                                    }
                                )
                            }
                        }
                    } else {
                        val flatCircles = circleGroups.values
                            .flatten()
                            .sortedBy { it.id }
                        when (mode) {
                            CircleDisplayMode.GRID -> {
                                CircleGrid(
                                    circles = flatCircles,
                                    displayMode = gridDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    showsOverlayWhenEmpty = false,
                                    onSelect = { circle ->
                                        // TODO: Navigate to circle detail
                                    }
                                )
                            }
                            CircleDisplayMode.LIST -> {
                                CircleList(
                                    circles = flatCircles,
                                    displayMode = listDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    showsOverlayWhenEmpty = false,
                                    onSelect = { circle ->
                                        // TODO: Navigate to circle detail
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun prepareCircles(
    favoriteItems: List<com.tsubuzaki.circlesgo.api.catalog.UserFavorites.Response.FavoriteItem>,
    favorites: FavoritesState,
    selections: UserSelections,
    database: CatalogDatabase
) {
    val favoriteCircleIdentifiers = FavoritesState.mapped(favoriteItems, database)

    val favoriteCircles = mutableMapOf<String, List<ComiketCircle>>()
    for (colorKey in favoriteCircleIdentifiers.keys.sorted()) {
        val circleIDs = favoriteCircleIdentifiers[colorKey] ?: continue
        var circles = database.circles(circleIDs).sortedBy { it.id }
        val selectedDate = selections.date.value
        if (selectedDate != null) {
            circles = circles.filter { it.day == selectedDate.id }
        }
        favoriteCircles[colorKey.toString()] = circles
    }
    favorites.setCircles(favoriteCircles)
}
