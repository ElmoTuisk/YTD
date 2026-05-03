package com.elmotuisk.ytd

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.yausername.youtubedl_android.YoutubeDL
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class YtdApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initYoutubeDL()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Download progress notifications"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun initYoutubeDL() {
        applicationScope.launch {
            try {
                YoutubeDL.getInstance().init(this@YtdApplication)
            } catch (_: Exception) {
                // Already initialized
            }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(
                    this@YtdApplication,
                    YoutubeDL.UpdateChannel._STABLE,
                )
            } catch (_: Exception) {
                // Update failed, continue with bundled version
            }
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "ytd_downloads"
    }
}
