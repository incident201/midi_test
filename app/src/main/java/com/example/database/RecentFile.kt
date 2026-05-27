package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "recent_files")
data class RecentFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val fileUri: String,
    val fileName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val detectedPatternsCount: Int = 0,
    val maxConfidence: Int = 0
)
