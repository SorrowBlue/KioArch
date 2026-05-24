package com.sorrowblue.kioarch

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.FileOutputStream

public object LargeTestFileGenerator {
    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: LargeTestFileGenerator <output-directory>")
            return
        }
        val outDir = File(args[0])
        outDir.mkdirs()

        val dataSize = 10 * 1024 * 1024 // 10MB
        val buffer = ByteArray(1024 * 1024) // 1MB buffer

        // 7z
        val file7z = outDir.resolve("large.7z")
        if (!file7z.exists()) {
            println("Generating large 7z: ${file7z.absolutePath}")
            SevenZOutputFile(file7z).use { sevenZFile ->
                val entry = sevenZFile.createArchiveEntry(file7z, "large_dummy.bin")
                entry.size = dataSize.toLong()
                sevenZFile.putArchiveEntry(entry)

                var written = 0
                while (written < dataSize) {
                    for (i in buffer.indices) {
                        buffer[i] = ((written + i) % 256).toByte()
                    }
                    sevenZFile.write(buffer)
                    written += buffer.size
                }
                sevenZFile.closeArchiveEntry()
            }
        } else {
            println("large.7z already exists")
        }

        // tar.gz
        val fileTarGz = outDir.resolve("large.tar.gz")
        if (!fileTarGz.exists()) {
            println("Generating large tar.gz: ${fileTarGz.absolutePath}")
            FileOutputStream(fileTarGz).use { fos ->
                GzipCompressorOutputStream(fos).use { gzos ->
                    TarArchiveOutputStream(gzos).use { tos ->
                        val entry = TarArchiveEntry("large_dummy.bin")
                        entry.size = dataSize.toLong()
                        tos.putArchiveEntry(entry)

                        var written = 0
                        while (written < dataSize) {
                            for (i in buffer.indices) {
                                buffer[i] = ((written + i) % 256).toByte()
                            }
                            tos.write(buffer)
                            written += buffer.size
                        }
                        tos.closeArchiveEntry()
                    }
                }
            }
        } else {
            println("large.tar.gz already exists")
        }
    }
}
