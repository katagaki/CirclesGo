package com.tsubuzaki.circlesgo.ui.unified

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedTopBar(
    unifier: Unifier,
    database: CatalogDatabase,
    selections: UserSelections,
    events: com.tsubuzaki.circlesgo.state.Events,
    onMyTapped: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier
            .weight(1f)
            .align(Alignment.CenterVertically)) {
            UnifiedControl(
                database = database,
                selections = selections
            )
        }
        UnifiedMoreMenu(
            unifier = unifier,
            events = events
        )
    }
}

@Composable
fun UnifiedMoreMenu(
    unifier: Unifier,
    events: com.tsubuzaki.circlesgo.state.Events
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        IconButton(
            onClick = { expanded = true },
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More"
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Event Picker
            events.eventData?.list?.sortedByDescending { it.number }?.forEach { event ->
                DropdownMenuItem(
                    text = { Text("Event ${event.number}") },
                    onClick = {
                        expanded = false
                        events.setActiveEvent(event.number)
                    },
                    leadingIcon = if (event.number == events.activeEventNumber) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected"
                            )
                        }
                    } else null
                )
            }
            DropdownMenuItem(
                text = { Text("Sign Out") },
                onClick = {
                    expanded = false
                    unifier.setIsGoingToSignOut(true)
                }
            )
        }
    }
}
