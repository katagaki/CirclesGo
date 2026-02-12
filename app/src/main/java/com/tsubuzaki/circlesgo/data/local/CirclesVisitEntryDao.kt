package com.tsubuzaki.circlesgo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CirclesVisitEntryDao {

    @Query("SELECT * FROM circles_visit_entries WHERE eventNumber = :eventNumber AND circleID = :circleID")
    suspend fun getVisits(eventNumber: Int, circleID: Int): List<CirclesVisitEntryEntity>

    @Insert
    suspend fun insert(entry: CirclesVisitEntryEntity)

    @Query("DELETE FROM circles_visit_entries WHERE eventNumber = :eventNumber AND circleID = :circleID")
    suspend fun delete(eventNumber: Int, circleID: Int)
}
