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

package com.sorrowblue.kioarch

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Holds the active Emscripten WebAssembly module instance registered via [KioArch.initialize].
 *
 * This property is `null` until [KioArch.initialize] has been invoked with a loaded module from
 * [loadKioArchModule].
 */
@OptIn(ExperimentalWasmJsInterop::class)
public var wasmModule: JsAny? = null

/**
 * Registers the instantiated Emscripten WebAssembly [module] with [KioArch].
 *
 * Because WebAssembly compilation and instantiation are asynchronous in browsers and Node.js, this
 * function **must** be called before any [KioArch.createReader] operation on JS and WasmJs targets.
 *
 * ```kotlin
 * loadKioArchModule().then { module ->
 *     KioArch.initialize(module)
 * }
 * ```
 *
 * @param module the resolved Emscripten module instance returned by [loadKioArchModule].
 * @see loadKioArchModule
 */
public expect fun KioArch.initialize(module: Any)
