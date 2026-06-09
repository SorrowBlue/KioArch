/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sorrowblue.kioarch.sample.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.sample.KioarchSampleApp
import com.sorrowblue.kioarch.sample.WebContext
import kotlin.js.Promise

@JsName("createKioArchModule")
private external fun createKioArchModuleJs(): Promise<Any>

/**
 * Entry point for the Kotlin/JS Sample Web Application.
 */
@OptIn(ExperimentalComposeUiApi::class)
public fun main() {
    println("[KioArch Demo] [JS] main() started. Loading WebAssembly module...")
    createKioArchModuleJs().then { module ->
        println("[KioArch Demo] [JS] WebAssembly module loaded! Initializing KioArch...")
        KioArch.initialize(module)
        println("[KioArch Demo] [JS] KioArch initialized successfully. Launching App...")
        ComposeViewport {
            KioarchSampleApp(WebContext)
        }
        module
    }
}
