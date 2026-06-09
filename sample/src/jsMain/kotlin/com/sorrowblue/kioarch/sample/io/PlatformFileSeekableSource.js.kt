package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.NodeFileSeekableSource
import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.path

private fun isNodeJs(): Boolean = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean

internal actual suspend fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource {
    return if (isNodeJs()) {
        NodeFileSeekableSource(file.path)
    } else {
        com.sorrowblue.kioarch.ByteArraySeekableSource(file.readBytes())
    }
}
