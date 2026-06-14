package com.sorrowblue.kioarch.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun AppTheme(content: @Composable (() -> Unit)) {
    MaterialTheme(content = content)
}
