package com.sorrowblue.kioarch.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.sorrowblue.kioarch.sample.ui.MainViewModel
import com.sorrowblue.kioarch.sample.ui.components.ArchiveDashboard
import com.sorrowblue.kioarch.sample.ui.theme.KioArchTheme

/**
 * Main Android activity that acts as the primary host and entry point for the Compose UI.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KioArchTheme {
                ArchiveDashboard(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
