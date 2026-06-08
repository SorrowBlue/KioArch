package com.sorrowblue.kioarch.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
fun KioarchSampleApp(context: PlatformContext, modifier: Modifier = Modifier) {
    CompositionLocalProvider(providePlatformContext(context)) {
        MaterialTheme {
            ArchiveDashboard(
                modifier = modifier
            )
        }
    }
}
