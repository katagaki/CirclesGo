package com.tsubuzaki.circlesgo.state

import com.tsubuzaki.circlesgo.database.CatalogDatabase
import com.tsubuzaki.circlesgo.database.DataFetcher
import com.tsubuzaki.circlesgo.database.tables.ComiketCircle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CatalogCache {

    private val _displayedCircles = MutableStateFlow<List<ComiketCircle>>(emptyList())
    val displayedCircles: StateFlow<List<ComiketCircle>> = _displayedCircles

    private val _searchedCircles = MutableStateFlow<List<ComiketCircle>?>(null)
    val searchedCircles: StateFlow<List<ComiketCircle>?> = _searchedCircles

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    var invalidationID: String = ""

    fun setDisplayedCircles(circles: List<ComiketCircle>) {
        _displayedCircles.value = circles
    }

    fun setSearchedCircles(circles: List<ComiketCircle>?) {
        _searchedCircles.value = circles
    }

    fun setIsLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    companion object {
        suspend fun fetchCircles(
            genreIDs: List<Int>?,
            mapID: Int?,
            blockIDs: List<Int>?,
            dayID: Int?,
            database: CatalogDatabase
        ): List<Int> {
            val fetcher = DataFetcher(database.getTextDatabase())
            return fetcher.circles(
                mapID = mapID,
                genreIDs = genreIDs,
                blockIDs = blockIDs,
                dayID = dayID
            )
        }

        suspend fun fetchGenreIDs(
            mapID: Int,
            dayID: Int,
            database: CatalogDatabase
        ): List<Int> {
            val fetcher = DataFetcher(database.getTextDatabase())
            return fetcher.genreIDs(mapID, dayID)
        }

        suspend fun fetchBlockIDs(
            mapID: Int,
            dayID: Int,
            genreIDs: List<Int>?,
            database: CatalogDatabase
        ): List<Int> {
            val fetcher = DataFetcher(database.getTextDatabase())
            return fetcher.blockIDs(mapID, dayID, genreIDs)
        }

        suspend fun searchCircles(
            searchTerm: String,
            database: CatalogDatabase
        ): List<Int>? {
            val fetcher = DataFetcher(database.getTextDatabase())
            val trimmed = searchTerm.trim()
            return if (trimmed.length >= 2) {
                fetcher.circlesContaining(trimmed)
            } else {
                null
            }
        }
    }
}
