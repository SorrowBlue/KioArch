package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.ParcelFileDescriptorSeekableSource
import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import java.io.IOException

internal actual suspend fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource {
    val pfd = context.contentResolver.openFileDescriptor(file.toAndroidUri(), "r")
        ?: throw IOException("Failed to open file descriptor for URI: ${file.toAndroidUri()}")
    return ParcelFileDescriptorSeekableSource(pfd)
}
