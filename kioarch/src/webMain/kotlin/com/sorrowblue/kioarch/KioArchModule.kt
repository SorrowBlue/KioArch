package com.sorrowblue.kioarch

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

@OptIn(ExperimentalWasmJsInterop::class)
public expect fun loadKioArchModule(): Promise<JsAny>
