package com.sorrowblue.kioarch.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
internal expect fun AppTheme(content: @Composable () -> Unit)

@Composable
fun KioarchSampleApp(context: PlatformContext, modifier: Modifier = Modifier) {
    CompositionLocalProvider(providePlatformContext(context)) {
        AppTheme {
            ArchiveDashboard(
                modifier = modifier
            )
        }
    }
}
