package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.NodeFileSeekableSource
import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import com.sorrowblue.kioarch.wasmModule
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes

@JsFun("() => (typeof process !== 'undefined' && process.versions != null && process.versions.node != null)")
private external fun isNodeJs(): Boolean

internal actual suspend fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource {
    return if (isNodeJs()) {
        NodeFileSeekableSource(file.path, wasmModule!!)
    } else {
        com.sorrowblue.kioarch.ByteArraySeekableSource(file.readBytes())
    }
}
