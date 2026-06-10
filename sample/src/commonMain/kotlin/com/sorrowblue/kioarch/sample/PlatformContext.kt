package com.sorrowblue.kioarch.sample

import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf

internal val LocalPlatformContext = staticCompositionLocalOf<PlatformContext> {
    throw IllegalStateException("PlatformContext")
}

internal fun providePlatformContext(
    platformContext: PlatformContext
): ProvidedValue<PlatformContext> = LocalPlatformContext provides platformContext

@Suppress("AbstractClassCanBeInterface")
expect abstract class PlatformContext
