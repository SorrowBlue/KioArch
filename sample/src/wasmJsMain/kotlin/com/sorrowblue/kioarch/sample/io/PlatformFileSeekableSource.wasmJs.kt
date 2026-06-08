package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.NodeFileSeekableSource
import com.sorrowblue.kioarch.SeekableSource
import com.sorrowblue.kioarch.sample.PlatformContext
import com.sorrowblue.kioarch.wasmModule
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.path

internal actual fun createSeekableSource(
    context: PlatformContext,
    file: PlatformFile
): SeekableSource = NodeFileSeekableSource(file.path, wasmModule!!)
