package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.FileSeekableSource
import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile

internal actual suspend fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource = FileSeekableSource(file = file.file)
