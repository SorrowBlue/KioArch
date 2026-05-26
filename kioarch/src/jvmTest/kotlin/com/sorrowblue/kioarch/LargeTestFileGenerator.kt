package com.sorrowblue.kioarch

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val dataSize100m = 100 * 1024 * 1024 // 100MB
        val buffer = ByteArray(1024 * 1024) // 1MB buffer

        // 7z (10MB)
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

        // tar.gz (10MB)
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

        // 7z (100MB)
        val file7z100m = outDir.resolve("large_100m.7z")
        if (!file7z100m.exists()) {
            println("Generating 100MB 7z: ${file7z100m.absolutePath}")
            SevenZOutputFile(file7z100m).use { sevenZFile ->
                val entry = sevenZFile.createArchiveEntry(file7z100m, "large_dummy.bin")
                entry.size = dataSize100m.toLong()
                sevenZFile.putArchiveEntry(entry)

                var written = 0
                while (written < dataSize100m) {
                    for (i in buffer.indices) {
                        buffer[i] = ((written + i) % 256).toByte()
                    }
                    sevenZFile.write(buffer)
                    written += buffer.size
                }
                sevenZFile.closeArchiveEntry()
            }
        } else {
            println("large_100m.7z already exists")
        }

        // tar.gz (100MB)
        val fileTarGz100m = outDir.resolve("large_100m.tar.gz")
        if (!fileTarGz100m.exists()) {
            println("Generating 100MB tar.gz: ${fileTarGz100m.absolutePath}")
            FileOutputStream(fileTarGz100m).use { fos ->
                GzipCompressorOutputStream(fos).use { gzos ->
                    TarArchiveOutputStream(gzos).use { tos ->
                        val entry = TarArchiveEntry("large_dummy.bin")
                        entry.size = dataSize100m.toLong()
                        tos.putArchiveEntry(entry)

                        var written = 0
                        while (written < dataSize100m) {
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
            println("large_100m.tar.gz already exists")
        }

        // zip (100MB)
        val fileZip100m = outDir.resolve("large_100m.zip")
        if (!fileZip100m.exists()) {
            println("Generating 100MB zip: ${fileZip100m.absolutePath}")
            FileOutputStream(fileZip100m).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val entry = ZipEntry("large_dummy.bin")
                    entry.size = dataSize100m.toLong()
                    zos.putNextEntry(entry)

                    var written = 0
                    while (written < dataSize100m) {
                        for (i in buffer.indices) {
                            buffer[i] = ((written + i) % 256).toByte()
                        }
                        zos.write(buffer)
                        written += buffer.size
                    }
                    zos.closeEntry()
                }
            }
        } else {
            println("large_100m.zip already exists")
        }

        // test.zip (Small zip for extraction test)
        val fileTestZip = outDir.resolve("test.zip")
        if (!fileTestZip.exists()) {
            println("Generating test.zip: ${fileTestZip.absolutePath}")
            FileOutputStream(fileTestZip).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val content1 = "This is a dummy text file inside zip.".toByteArray()
                    zos.putNextEntry(ZipEntry("dummy1.txt"))
                    zos.write(content1)
                    zos.closeEntry()

                    val content2 = ByteArray(1200) { 'a'.code.toByte() }
                    zos.putNextEntry(ZipEntry("dummy2.txt"))
                    zos.write(content2)
                    zos.closeEntry()
                }
            }
        } else {
            println("test.zip already exists")
        }

        // test.7z (Small 7z for extraction test)
        val fileTest7z = outDir.resolve("test.7z")
        if (!fileTest7z.exists()) {
            println("Generating test.7z: ${fileTest7z.absolutePath}")
            SevenZOutputFile(fileTest7z).use { sevenZFile ->
                val content1 = "This is a dummy text file inside 7z.".toByteArray()
                val entry1 = sevenZFile.createArchiveEntry(fileTest7z, "dummy1.txt")
                entry1.size = content1.size.toLong()
                sevenZFile.putArchiveEntry(entry1)
                sevenZFile.write(content1)
                sevenZFile.closeArchiveEntry()

                val content2 = "Some more dummy content in the second 7z file.".toByteArray()
                val entry2 = sevenZFile.createArchiveEntry(fileTest7z, "dummy2.txt")
                entry2.size = content2.size.toLong()
                sevenZFile.putArchiveEntry(entry2)
                sevenZFile.write(content2)
                sevenZFile.closeArchiveEntry()
            }
        } else {
            println("test.7z already exists")
        }

        // test_sjis.zip (Shift_JIS filename zip)
        val fileTestSjis = outDir.resolve("test_sjis.zip")
        if (!fileTestSjis.exists()) {
            println("Generating test_sjis.zip: ${fileTestSjis.absolutePath}")
            val edgeCaseNames = listOf(
                "テスト_日本語ファイル名_Shift_JIS.txt",
                "dame_moji_ソ表能予.txt",
                "half_width_ｶﾀｶﾅﾃｽﾄ.txt",
                "cp932_extensions_①Ⅳ髙﨑.txt"
            )
            ZipOutputStream(
                FileOutputStream(fileTestSjis),
                java.nio.charset.Charset.forName("MS932")
            ).use { zos ->
                for (name in edgeCaseNames) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write("hello".toByteArray())
                    zos.closeEntry()
                }
            }
        } else {
            println("test_sjis.zip already exists")
        }

        // test_path_normal.zip (Windows style path zip)
        val fileTestPathNormal = outDir.resolve("test_path_normal.zip")
        if (!fileTestPathNormal.exists()) {
            println("Generating test_path_normal.zip: ${fileTestPathNormal.absolutePath}")
            ZipOutputStream(FileOutputStream(fileTestPathNormal)).use { zos ->
                zos.putNextEntry(ZipEntry("directory\\subdir\\file.txt"))
                zos.write("hello_thread".toByteArray())
                zos.closeEntry()
            }
        } else {
            println("test_path_normal.zip already exists")
        }
    }
}
