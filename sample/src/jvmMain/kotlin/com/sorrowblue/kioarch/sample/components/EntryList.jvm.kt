package com.sorrowblue.kioarch.sample.components

import java.text.DecimalFormat

actual fun formatDecimal(value: Double, precision: Int): String {
    val pattern = "0." + "0".repeat(precision)
    return DecimalFormat(pattern).format(value)
}
