package com.tsubuzaki.circlesgo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "circles_visit_entries")
data class CirclesVisitEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventNumber: Int,
    val circleID: Int,
    val visitDate: Long? = null // Epoch millis
)
