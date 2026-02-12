package com.tsubuzaki.circlesgo.ui.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tsubuzaki.circlesgo.state.CircleDisplayMode
import com.tsubuzaki.circlesgo.state.GridDisplayMode
import com.tsubuzaki.circlesgo.state.ListDisplayMode

@Composable
fun DisplayModeSwitcher(
    mode: CircleDisplayMode,
    onModeChanged: (CircleDisplayMode) -> Unit
) {
    IconButton(onClick = {
        val newMode = when (mode) {
            CircleDisplayMode.GRID -> CircleDisplayMode.LIST
            CircleDisplayMode.LIST -> CircleDisplayMode.GRID
        }
        onModeChanged(newMode)
    }) {
        when (mode) {
            CircleDisplayMode.GRID -> Icon(
                imageVector = Icons.AutoMirrored.Filled.ViewList,
                contentDescription = "Switch to list"
            )
            CircleDisplayMode.LIST -> Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = "Switch to grid"
            )
        }
    }
}

@Composable
fun GridModeSwitcher(
    mode: GridDisplayMode,
    onModeChanged: (GridDisplayMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        when (mode) {
            GridDisplayMode.BIG -> Icon(
                imageVector = Icons.Filled.ViewModule,
                contentDescription = "Grid size: Big"
            )
            GridDisplayMode.MEDIUM -> Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = "Grid size: Medium"
            )
            GridDisplayMode.SMALL -> Icon(
                imageVector = Icons.Filled.ViewComfy,
                contentDescription = "Grid size: Small"
            )
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Big") },
            leadingIcon = { Icon(Icons.Filled.ViewModule, contentDescription = null) },
            onClick = {
                onModeChanged(GridDisplayMode.BIG)
                expanded = false
            }
        )
        DropdownMenuItem(
            text = { Text("Medium") },
            leadingIcon = { Icon(Icons.Filled.GridView, contentDescription = null) },
            onClick = {
                onModeChanged(GridDisplayMode.MEDIUM)
                expanded = false
            }
        )
        DropdownMenuItem(
            text = { Text("Small") },
            leadingIcon = { Icon(Icons.Filled.ViewComfy, contentDescription = null) },
            onClick = {
                onModeChanged(GridDisplayMode.SMALL)
                expanded = false
            }
        )
    }
}

@Composable
fun ListModeSwitcher(
    mode: ListDisplayMode,
    onModeChanged: (ListDisplayMode) -> Unit
) {
    IconButton(onClick = {
        val newMode = when (mode) {
            ListDisplayMode.REGULAR -> ListDisplayMode.COMPACT
            ListDisplayMode.COMPACT -> ListDisplayMode.REGULAR
        }
        onModeChanged(newMode)
    }) {
        when (mode) {
            ListDisplayMode.REGULAR -> Icon(
                imageVector = Icons.Filled.ViewCompact,
                contentDescription = "Switch to compact list"
            )
            ListDisplayMode.COMPACT -> Icon(
                imageVector = Icons.AutoMirrored.Filled.ViewList,
                contentDescription = "Switch to regular list"
            )
        }
    }
}
