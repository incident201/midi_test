package com.example.database

import kotlinx.coroutines.flow.Flow

class RecentFileRepository(private val recentFileDao: RecentFileDao) {

    val recentFiles: Flow<List<RecentFile>> = recentFileDao.getRecentFiles()

    suspend fun addRecentFile(uri: String, name: String, patternsCount: Int, maxConfidence: Int) {
        val file = RecentFile(
            fileUri = uri,
            fileName = name,
            timestamp = System.currentTimeMillis(),
            detectedPatternsCount = patternsCount,
            maxConfidence = maxConfidence
        )
        // First delete any previous entry of the same file to bring it to the top
        recentFileDao.deleteRecentFileByUri(uri)
        recentFileDao.insertRecentFile(file)
    }

    suspend fun removeRecentFile(uri: String) {
        recentFileDao.deleteRecentFileByUri(uri)
    }

    suspend fun clearAll() {
        recentFileDao.clearAllRecentFiles()
    }
}
