package com.tsubuzaki.circlesgo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tsubuzaki.circlesgo.auth.Authenticator
import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.state.CatalogCache
import com.tsubuzaki.circlesgo.state.Events
import com.tsubuzaki.circlesgo.state.FavoritesState
import com.tsubuzaki.circlesgo.state.Mapper
import com.tsubuzaki.circlesgo.state.Unifier
import com.tsubuzaki.circlesgo.state.UserSelections
import com.tsubuzaki.circlesgo.ui.unified.UnifiedView

class MainActivity : ComponentActivity() {

    private var authenticator: Authenticator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = CatalogDatabase(this)
        val mapper = Mapper()
        val selections = UserSelections(this)
        val events = Events(this)
        val favorites = FavoritesState()
        val unifier = Unifier()
        val catalogCache = CatalogCache()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnifiedView(
                        unifier = unifier,
                        mapper = mapper,
                        database = database,
                        selections = selections,
                        events = events,
                        favorites = favorites,
                        catalogCache = catalogCache,
                        onLogout = {
                            database.delete()
                            selections.resetSelections()
                            unifier.close()
                            authenticator?.resetAuthentication()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth callback
        intent.data?.let { uri ->
            if (uri.scheme == "circles-app") {
                authenticator?.getAuthenticationCode(uri)
            }
        }
    }
}
