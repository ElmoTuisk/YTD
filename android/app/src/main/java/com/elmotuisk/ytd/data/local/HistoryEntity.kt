package com.elmotuisk.ytd.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.elmotuisk.ytd.data.model.HistoryItem

@Entity(tableName = "download_history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filename: String,
    val filePath: String,
    val date: String,
    val size: String,
)

fun HistoryEntity.toModel() = HistoryItem(
    id = id,
    filename = filename,
    filePath = filePath,
    date = date,
    size = size,
)

fun HistoryItem.toEntity() = HistoryEntity(
    id = id,
    filename = filename,
    filePath = filePath,
    date = date,
    size = size,
)
