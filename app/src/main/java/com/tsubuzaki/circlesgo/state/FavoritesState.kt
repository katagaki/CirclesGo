package com.tsubuzaki.circlesgo.state

import com.tsubuzaki.circlesgo.api.catalog.UserFavorites
import com.tsubuzaki.circlesgo.database.tables.ComiketCircle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FavoritesState {

    private val _items = MutableStateFlow<List<UserFavorites.Response.FavoriteItem>?>(null)
    val items: StateFlow<List<UserFavorites.Response.FavoriteItem>?> = _items

    private val _wcIDMappedItems = MutableStateFlow<Map<Int, UserFavorites.Response.FavoriteItem>?>(null)
    val wcIDMappedItems: StateFlow<Map<Int, UserFavorites.Response.FavoriteItem>?> = _wcIDMappedItems

    private val _circles = MutableStateFlow<Map<String, List<ComiketCircle>>?>(null)
    val circles: StateFlow<Map<String, List<ComiketCircle>>?> = _circles

    var isGroupedByColor: Boolean = true

    fun setItems(items: List<UserFavorites.Response.FavoriteItem>) {
        _items.value = items
    }

    fun setWcIDMappedItems(items: Map<Int, UserFavorites.Response.FavoriteItem>) {
        _wcIDMappedItems.value = items
    }

    fun setCircles(circles: Map<String, List<ComiketCircle>>) {
        _circles.value = circles
    }

    fun contains(webCatalogID: Int): Boolean {
        return _items.value?.any { it.circle.webCatalogID == webCatalogID } == true
    }
}
