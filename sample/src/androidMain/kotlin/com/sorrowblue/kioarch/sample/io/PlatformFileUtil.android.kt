package com.sorrowblue.kioarch.sample.io

import androidx.documentfile.provider.DocumentFile
import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.dialogs.toAndroidUri
import io.github.vinceglb.filekit.div
import io.github.vinceglb.filekit.exceptions.FileKitException
import io.github.vinceglb.filekit.sink
import kotlinx.io.RawSink

internal actual fun PlatformFile.div2(child: String): PlatformFile {
    return div(child)
}

internal actual fun PlatformFile.sink2(): RawSink {
    return this.sink()
}

internal actual fun PlatformFile.createDirectories2() {
    return createDirectories()
}

internal actual fun PlatformFile.createFile(context: PlatformContext, name: String): PlatformFile {
    val directoryDocument = DocumentFile.fromTreeUri(context, toAndroidUri())
        ?: DocumentFile.fromSingleUri(context, toAndroidUri())
        ?: throw FileKitException("Could not access Uri as DocumentFile")

    directoryDocument.findFile(name)?.let { existing ->
        if (existing.isDirectory) {
            throw FileKitException("Destination already contains a directory named ${name}")
        }

        return PlatformFile(existing.uri)
    }

    val created = directoryDocument.createFile("*/*", name)
        ?: throw FileKitException("Could not create destination file in bookmarked directory")
    return PlatformFile(created.uri)
}
