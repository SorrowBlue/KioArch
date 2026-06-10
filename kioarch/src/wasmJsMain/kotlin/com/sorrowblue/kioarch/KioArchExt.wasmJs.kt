package com.sorrowblue.kioarch

public actual fun KioArch.initialize(module: Any) {
    wasmModule = module.cast()
    initOpaqueMap()
    initSinkMap()
}

// Inline helper extension to cast JS reference safely in Kotlin/Wasm
@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "UNCHECKED_CAST")
private fun <T : JsAny> Any.cast(): T = this as T
