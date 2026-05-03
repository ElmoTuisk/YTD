package com.elmotuisk.ytd.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.elmotuisk.ytd.MainActivity
import com.elmotuisk.ytd.R
import com.elmotuisk.ytd.YtdApplication
import com.elmotuisk.ytd.data.model.DownloadConfig
import com.elmotuisk.ytd.data.model.DownloadFormat
import com.elmotuisk.ytd.data.model.Platform
import com.elmotuisk.ytd.download.DownloadState
import com.elmotuisk.ytd.download.YtdDownloadManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var downloadManager: YtdDownloadManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val platformName = intent.getStringExtra(EXTRA_PLATFORM) ?: Platform.YOUTUBE.name
                val formatName = intent.getStringExtra(EXTRA_FORMAT) ?: DownloadFormat.VIDEO_MP4.name
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "Best"
                val outputDir = intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: return START_NOT_STICKY

                val config = DownloadConfig(
                    url = url,
                    platform = try {
                        Platform.valueOf(platformName)
                    } catch (_: Exception) {
                        Platform.YOUTUBE
                    },
                    format = try {
                        DownloadFormat.valueOf(formatName)
                    } catch (_: Exception) {
                        DownloadFormat.VIDEO_MP4
                    },
                    quality = quality,
                    outputDir = outputDir,
                )

                startForeground(NOTIFICATION_ID, buildNotification("Starting download..."))
                downloadManager.startDownload(config, serviceScope)
                observeProgress()
            }

            ACTION_CANCEL -> {
                downloadManager.cancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeProgress() {
        serviceScope.launch(Dispatchers.Main) {
            downloadManager.downloadState.collect { state ->
                when (state) {
                    is DownloadState.Downloading -> {
                        val progress = state.progress
                        val text = buildString {
                            if (progress.sizeOrInfo.isNotEmpty()) append(progress.sizeOrInfo)
                            if (progress.eta.isNotEmpty()) {
                                if (isNotEmpty()) append(" | ")
                                append(progress.eta)
                            }
                        }.ifEmpty { "Downloading..." }

                        val notification = buildNotification(text, progress.percent.toInt())
                        val manager = getSystemService(NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                        manager.notify(NOTIFICATION_ID, notification)
                    }

                    is DownloadState.Completed, is DownloadState.Error -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    DownloadState.Idle -> {
                        // Do nothing
                    }
                }
            }
        }
    }

    private fun buildNotification(
        text: String,
        progress: Int = 0,
    ) = NotificationCompat.Builder(this, YtdApplication.NOTIFICATION_CHANNEL_ID)
        .setContentTitle("YTD Download")
        .setContentText(text)
        .setSmallIcon(R.drawable.ic_notification)
        .setOngoing(true)
        .setSilent(true)
        .setProgress(100, progress.coerceIn(0, 100), progress == 0)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            R.drawable.ic_notification, "Cancel",
            PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .build()

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.elmotuisk.ytd.START_DOWNLOAD"
        const val ACTION_CANCEL = "com.elmotuisk.ytd.CANCEL_DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_PLATFORM = "platform"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        private const val NOTIFICATION_ID = 1001

        fun startDownload(context: Context, config: DownloadConfig) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, config.url)
                putExtra(EXTRA_PLATFORM, config.platform.name)
                putExtra(EXTRA_FORMAT, config.format.name)
                putExtra(EXTRA_QUALITY, config.quality)
                putExtra(EXTRA_OUTPUT_DIR, config.outputDir)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
