package com.mediahub.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.mediahub.mobile.catalog.CatalogViewModel
import com.mediahub.mobile.ui.CloudTransferViewModel
import com.mediahub.mobile.ui.MediaHubScreen
import com.mediahub.mobile.ui.MediaHubTheme

class MainActivity : ComponentActivity() {
    private val transfers by viewModels<CloudTransferViewModel>()
    private val catalog by viewModels<CatalogViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(true) }
            MediaHubTheme(darkTheme = darkTheme) {
                MediaHubScreen(
                    darkTheme = darkTheme, onThemeToggle = { darkTheme = !darkTheme },
                    transfers = transfers, catalog = catalog
                )
            }
        }
    }
}
