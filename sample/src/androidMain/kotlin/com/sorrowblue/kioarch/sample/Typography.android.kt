package com.sorrowblue.kioarch.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable

@ReadOnlyComposable
@Composable
internal actual fun getAppTypography(): Typography = MaterialTheme.typography
