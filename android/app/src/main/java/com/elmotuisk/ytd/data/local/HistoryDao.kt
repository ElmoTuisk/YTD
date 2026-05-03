package com.elmotuisk.ytd.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM download_history ORDER BY id DESC LIMIT 50")
    fun getAll(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun insert(entity: HistoryEntity)

    @Query("DELETE FROM download_history WHERE id NOT IN (SELECT id FROM download_history ORDER BY id DESC LIMIT 50)")
    suspend fun trimToMax()
}
