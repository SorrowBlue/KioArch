package com.sorrowblue.kioarch.sample.components

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

@OptIn(ExperimentalWasmJsInterop::class)
actual fun formatDecimal(value: Double, precision: Int): String {
    return js("""
        new Intl.NumberFormat('en-US', {
            minimumFractionDigits: precision,
            maximumFractionDigits: precision,
            useGrouping: false // カンマ（桁区切り）を入れたくない場合は false
        }).format(value)
    """)
}
