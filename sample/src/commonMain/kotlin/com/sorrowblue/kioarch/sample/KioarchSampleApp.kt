package com.sorrowblue.kioarch.sample

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier

@Composable
internal expect fun getAppTypography(): Typography

@Composable
fun KioarchSampleApp(context: PlatformContext, modifier: Modifier = Modifier) {
    println("[KioArch Demo] KioarchSampleApp composition started!")
    CompositionLocalProvider(providePlatformContext(context)) {
        MaterialTheme(typography = getAppTypography()) {
            println("[KioArch Demo] MaterialTheme composition started!")
            ArchiveDashboard(
                modifier = modifier
            )
        }
    }
}
