package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.sink
import kotlinx.io.RawSink

internal actual fun PlatformFile.div2(child: String): PlatformFile {
    return this.div(child)
}

internal actual fun PlatformFile.sink2(): RawSink {
    return this.sink()
}

internal actual fun PlatformFile.createDirectories2() {
    return createDirectories()
}

internal actual fun PlatformFile.createFile(
    context: PlatformContext,
    name: String
): PlatformFile {
    val directoryDocument = this.file
    directoryDocument.listFiles().find { it.name == name }?.let { existing ->
        if (existing.isDirectory) {
            throw FileKitException("Destination already contains a directory named $name")
        }

        return PlatformFile(existing)
    }
    val created = directoryDocument.resolve(name)
    created.createNewFile()
    return PlatformFile(created)
}
