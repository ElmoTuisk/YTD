package com.elmotuisk.ytd.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.elmotuisk.ytd.ui.download.DownloadRoute
import com.elmotuisk.ytd.ui.theme.YtdTheme

@Composable
fun YtdApp(sharedUrl: String? = null) {
    YtdTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            DownloadRoute(sharedUrl = sharedUrl)
        }
    }
}
