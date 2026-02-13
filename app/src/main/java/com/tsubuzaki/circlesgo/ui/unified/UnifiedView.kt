package com.tsubuzaki.circlesgo.ui.unified

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.CatalogCache
import com.tsubuzaki.circlesgo.state.Events
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.Oasis
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections
import com.tsubuzaki.circlesgo.ui.map.MapView
import com.tsubuzaki.circlesgo.ui.shared.ProgressOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedView(
    unifier: Unifier,
    mapper: Mapper,
    database: CatalogDatabase,
    selections: UserSelections,
    events: Events,
    favorites: FavoritesState,
    catalogCache: CatalogCache,
    oasis: Oasis,
    onLogout: () -> Unit
) {
    val isGoingToSignOut by unifier.isGoingToSignOut.collectAsState()

    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.PartiallyExpanded,
        skipHiddenState = true
    )
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = bottomSheetState
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // Sign out dialog
        if (isGoingToSignOut) {
            AlertDialog(
                onDismissRequest = { unifier.setIsGoingToSignOut(false) },
                title = { Text("Sign Out") },
                text = { Text("Are you sure you want to sign out? All downloaded data will be deleted.") },
                confirmButton = {
                    TextButton(onClick = {
                        unifier.setIsGoingToSignOut(false)
                        onLogout()
                    }) {
                        Text("Sign Out")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { unifier.setIsGoingToSignOut(false) }) {
                        Text("Cancel")
                    }
                }
            )
        }

        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = 360.dp,
            sheetDragHandle = {
                val isExpanded = scaffoldState.bottomSheetState.targetValue == SheetValue.Expanded
                BottomSheetDefaults.DragHandle(
                    modifier = if (isExpanded) Modifier.statusBarsPadding() else Modifier
                )
            },
            topBar = {
                UnifiedTopBar(
                    unifier = unifier,
                    database = database,
                    selections = selections,
                    events = events,
                )
            },
            sheetContent = {
                UnifiedPanel(
                    unifier = unifier,
                    events = events,
                    database = database,
                    favorites = favorites,
                    selections = selections,
                    mapper = mapper,
                    catalogCache = catalogCache
                )
            },
            sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            sheetShadowElevation = 16.dp
        ) { innerPadding ->
            // Main map view content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                MapView(
                    database = database,
                    mapper = mapper,
                    selections = selections,
                    favorites = favorites,
                    onCircleTapped = { circleID ->
                        // Navigate to circle detail when tapped
                        println(circleID)
                        unifier.show()
                    }
                )
            }
        }

        // Progress overlay (shown during database download/loading)
        ProgressOverlay(oasis = oasis)

    } // end outer Box
}
