package com.sorrowblue.kioarch.sample.jvm

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sorrowblue.kioarch.sample.JvmContext
import com.sorrowblue.kioarch.sample.KioarchSampleApp

/**
 * Main entry point for the Compose Multiplatform Desktop application.
 */
fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KioArch JVM Sample Viewer"
    ) {
        KioarchSampleApp(context = JvmContext, modifier = Modifier.fillMaxSize())
    }
}
