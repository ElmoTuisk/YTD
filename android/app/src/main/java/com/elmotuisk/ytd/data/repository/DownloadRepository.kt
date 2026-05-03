package com.elmotuisk.ytd.data.repository

import com.elmotuisk.ytd.data.local.HistoryDao
import com.elmotuisk.ytd.data.local.HistoryEntity
import com.elmotuisk.ytd.data.local.toModel
import com.elmotuisk.ytd.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val historyDao: HistoryDao,
) {
    fun getHistory(): Flow<List<HistoryItem>> =
        historyDao.getAll().map { entities -> entities.map { it.toModel() } }

    suspend fun addHistoryEntry(item: HistoryItem) {
        historyDao.insert(
            HistoryEntity(
                filename = item.filename,
                filePath = item.filePath,
                date = item.date,
                size = item.size,
            )
        )
        historyDao.trimToMax()
    }
}
