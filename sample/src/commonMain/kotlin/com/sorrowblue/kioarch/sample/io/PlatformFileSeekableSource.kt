package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile

internal expect suspend fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource
