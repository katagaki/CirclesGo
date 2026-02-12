package com.tsubuzaki.circlesgo.data.repository

import com.tsubuzaki.circlesgo.data.local.CirclesVisitEntryDao
import com.tsubuzaki.circlesgo.data.local.CirclesVisitEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VisitRepository(private val visitEntryDao: CirclesVisitEntryDao) {

    suspend fun toggleVisit(circleID: Int, eventNumber: Int) = withContext(Dispatchers.IO) {
        val existingVisits = visitEntryDao.getVisits(eventNumber, circleID)
        if (existingVisits.isEmpty()) {
            visitEntryDao.insert(
                CirclesVisitEntryEntity(
                    eventNumber = eventNumber,
                    circleID = circleID,
                    visitDate = System.currentTimeMillis()
                )
            )
        } else {
            visitEntryDao.delete(eventNumber, circleID)
        }
    }
}
