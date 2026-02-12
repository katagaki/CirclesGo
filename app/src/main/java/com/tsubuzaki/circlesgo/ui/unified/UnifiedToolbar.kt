package com.tsubuzaki.circlesgo.ui.unified

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedTopBar(
    unifier: Unifier,
    database: CatalogDatabase,
    selections: UserSelections,
    onMyTapped: () -> Unit
) {
    TopAppBar(
        title = {
            UnifiedControl(
                database = database,
                selections = selections
            )
        },
        navigationIcon = {
            IconButton(onClick = onMyTapped) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "My"
                )
            }
        },
        actions = {
            UnifiedMoreMenu(unifier = unifier)
        }
    )
}

@Composable
fun UnifiedMoreMenu(unifier: Unifier) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "More"
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text("Database Admin") },
            onClick = {
                expanded = false
                unifier.append(com.tsubuzaki.circlesgo.state.UnifiedPath.MORE_DB_ADMIN)
            }
        )
        DropdownMenuItem(
            text = { Text("Licenses") },
            onClick = {
                expanded = false
                unifier.append(com.tsubuzaki.circlesgo.state.UnifiedPath.MORE_ATTRIBUTIONS)
            }
        )
        DropdownMenuItem(
            text = { Text("Sign Out") },
            onClick = {
                expanded = false
                unifier.setIsGoingToSignOut(true)
            }
        )
    }
}
