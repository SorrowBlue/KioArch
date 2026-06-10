package com.sorrowblue.kioarch.sample.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.initialize
import com.sorrowblue.kioarch.loadKioArchModule
import com.sorrowblue.kioarch.sample.KioarchSampleApp
import com.sorrowblue.kioarch.sample.WebContext
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Entry point for the Kotlin/WasmJS Sample Web Application.
 */
@OptIn(ExperimentalWasmJsInterop::class, ExperimentalComposeUiApi::class)
public fun main() {
    loadKioArchModule().then { module ->
        KioArch.initialize(module)
        ComposeViewport {
            KioarchSampleApp(WebContext)
        }
        module
    }
}
