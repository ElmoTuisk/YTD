package com.elmotuisk.ytd.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class YtdDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
