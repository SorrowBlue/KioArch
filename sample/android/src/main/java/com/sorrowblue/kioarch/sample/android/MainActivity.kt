package com.sorrowblue.kioarch.sample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.sorrowblue.kioarch.sample.KioarchSampleApp

/**
 * Main Android activity that acts as the primary host and entry point for the Compose UI.
 */
internal class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KioarchSampleApp(
                context = this,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
