package com.example.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recent_files ORDER BY timestamp DESC LIMIT 20")
    fun getRecentFiles(): Flow<List<RecentFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecentFile(recentFile: RecentFile)

    @Query("DELETE FROM recent_files WHERE fileUri = :uri")
    suspend fun deleteRecentFileByUri(uri: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAllRecentFiles()
}
