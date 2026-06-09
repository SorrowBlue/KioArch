package com.sorrowblue.kioarch.sample.components

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

@OptIn(ExperimentalWasmJsInterop::class)
actual fun formatDecimal(value: Double, precision: Int): String =
    js("(new Intl.NumberFormat('en-US', { minimumFractionDigits: precision, maximumFractionDigits: precision, useGrouping: false })).format(value)")
