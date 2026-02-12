package com.tsubuzaki.circlesgo.ui.catalog

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.CatalogCache
import com.tsubuzaki.circlesgo.state.CircleDisplayMode
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.GridDisplayMode
import com.tsubuzaki.circlesgo.state.ListDisplayMode
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun CatalogView(
    database: CatalogDatabase,
    selections: UserSelections,
    favorites: FavoritesState,
    mapper: Mapper,
    unifier: Unifier,
    catalogCache: CatalogCache
) {
    val scope = rememberCoroutineScope()
    val isLoading by catalogCache.isLoading.collectAsState()
    val displayedCircles by catalogCache.displayedCircles.collectAsState()
    val searchedCircles by catalogCache.searchedCircles.collectAsState()

    val selectedGenres by selections.genres.collectAsState()
    val selectedMap by selections.map.collectAsState()
    val selectedDate by selections.date.collectAsState()

    // Display mode preferences (saved across recompositions)
    var displayMode by rememberSaveable { mutableStateOf(CircleDisplayMode.GRID) }
    var gridDisplayMode by rememberSaveable { mutableStateOf(GridDisplayMode.MEDIUM) }
    var listDisplayMode by rememberSaveable { mutableStateOf(ListDisplayMode.REGULAR) }

    // Search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf("") }

    // Reload circles when selection changes
    val catalogSelectionID = selections.catalogSelectionID
    LaunchedEffect(catalogSelectionID) {
        if (catalogCache.invalidationID != catalogSelectionID) {
            reloadDisplayedCircles(catalogCache, selections, database)
        }
    }

    // Search when term changes
    LaunchedEffect(searchTerm) {
        if (searchTerm.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val circleIDs = CatalogCache.searchCircles(searchTerm, database)
                if (circleIDs != null) {
                    val circles = database.circles(circleIDs)
                    catalogCache.setSearchedCircles(circles)
                } else {
                    catalogCache.setSearchedCircles(null)
                }
            }
        } else {
            catalogCache.setSearchedCircles(null)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Display mode switcher row + search toggle
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
            IconButton(onClick = {
                isSearchActive = !isSearchActive
                if (!isSearchActive) {
                    searchTerm = ""
                }
            }) {
                Icon(
                    imageVector = if (isSearchActive) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (isSearchActive) "Close search" else "Search"
                )
            }
        }

        // Search bar
        if (isSearchActive) {
            OutlinedTextField(
                value = searchTerm,
                onValueChange = { searchTerm = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search circles...") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchTerm.isNotEmpty()) {
                        IconButton(onClick = { searchTerm = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                }
            )
        }

        // Genre and block filter toolbar
        CatalogToolbar(
            database = database,
            selections = selections
        )

        // Content area
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                val circlesToShow = searchedCircles ?: displayedCircles
                val hasNoFilter = selectedGenres.isEmpty() && selectedMap == null && searchedCircles == null

                if (hasNoFilter) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select a hall and filters\nto browse circles.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    AnimatedContent(
                        targetState = displayMode,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "displayMode"
                    ) { mode ->
                        when (mode) {
                            CircleDisplayMode.GRID -> {
                                CircleGrid(
                                    circles = circlesToShow,
                                    displayMode = gridDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    onSelect = { circle ->
                                        unifier.showCircleDetail(circle)
                                    }
                                )
                            }
                            CircleDisplayMode.LIST -> {
                                CircleList(
                                    circles = circlesToShow,
                                    displayMode = listDisplayMode,
                                    database = database,
                                    favorites = favorites,
                                    onSelect = { circle ->
                                        unifier.showCircleDetail(circle)
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

private suspend fun reloadDisplayedCircles(
    catalogCache: CatalogCache,
    selections: UserSelections,
    database: CatalogDatabase
) {
    catalogCache.setIsLoading(true)
    catalogCache.invalidationID = selections.catalogSelectionID

    val selectedGenreIDs = selections.genres.value.let { genres ->
        if (genres.isEmpty()) null else genres.map { it.id }
    }
    val selectedMapID = selections.map.value?.id
    val selectedBlockIDs = selections.blocks.value.let { blocks ->
        if (blocks.isEmpty()) null else blocks.map { it.id }
    }
    val selectedDayID = selections.date.value?.id

    val circleIDs = CatalogCache.fetchCircles(
        genreIDs = selectedGenreIDs,
        mapID = selectedMapID,
        blockIDs = selectedBlockIDs,
        dayID = selectedDayID,
        database = database
    )

    val circles = database.circles(circleIDs)
    catalogCache.setDisplayedCircles(circles)
    catalogCache.setIsLoading(false)
}
