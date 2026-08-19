package com.nadrlab.visionai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val analysisType: String,
    val aiMode: String,
    val contentType: String,
    val description: String,
    val elements: String,
    val extractedText: String,
    val keywords: String,
    val confidence: String,
    val fullResult: String,
    val searchResults: String,
    val imageUri: String
)
