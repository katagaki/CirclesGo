package com.tsubuzaki.circlesgo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CirclesFavoriteDao {

    @Query("SELECT * FROM circles_favorites")
    suspend fun getAll(): List<CirclesFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: CirclesFavoriteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<CirclesFavoriteEntity>)

    @Query("DELETE FROM circles_favorites")
    suspend fun deleteAll()

    @Query("DELETE FROM circles_favorites WHERE webCatalogID = :webCatalogID")
    suspend fun deleteByWebCatalogID(webCatalogID: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM circles_favorites WHERE webCatalogID = :webCatalogID)")
    suspend fun exists(webCatalogID: Int): Boolean
}
