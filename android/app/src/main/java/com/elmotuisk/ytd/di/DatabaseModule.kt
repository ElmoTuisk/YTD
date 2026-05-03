package com.elmotuisk.ytd.di

import android.content.Context
import androidx.room.Room
import com.elmotuisk.ytd.data.local.HistoryDao
import com.elmotuisk.ytd.data.local.YtdDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): YtdDatabase =
        Room.databaseBuilder(context, YtdDatabase::class.java, "ytd.db").build()

    @Provides
    fun provideHistoryDao(database: YtdDatabase): HistoryDao = database.historyDao()
}
