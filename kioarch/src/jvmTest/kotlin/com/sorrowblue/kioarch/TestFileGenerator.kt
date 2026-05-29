package com.sorrowblue.kioarch

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream

object TestFileGenerator {

    private fun writeBuffer(written: Int, buffer: ByteArray, offset: Int) {
        for (i in buffer.indices) {
            buffer[i] = ((written + i + offset) % 256).toByte()
        }
    }

    @JvmStatic
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "MagicNumber")
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("Usage: TestFileGenerator <output-directory>")
            return
        }
        val outDir = File(args[0])
        outDir.mkdirs()

        val dataSize = 10 * 1024 * 1024 // 10MB
        val dataSize100m = 100 * 1024 * 1024 // 100MB
        val buffer = ByteArray(1024 * 1024) // 1MB buffer
        val numFiles = 100

        // 7z (10MB)
        val file7z = outDir.resolve("large.7z")
        if (!file7z.exists()) {
            println("Generating large 7z: ${file7z.absolutePath}")
            SevenZOutputFile(file7z).use { sevenZFile ->
                val sizePerFile = dataSize / numFiles
                for (f in 0 until numFiles) {
                    val entry = sevenZFile.createArchiveEntry(file7z, "large_dummy_$f.bin")
                    entry.size = sizePerFile.toLong()
                    sevenZFile.putArchiveEntry(entry)
                    var written = 0
                    while (written < sizePerFile) {
                        writeBuffer(written, buffer, f * 10)
                        val toWrite = minOf(buffer.size, sizePerFile - written)
                        sevenZFile.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    sevenZFile.closeArchiveEntry()
                }
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
                        val sizePerFile = dataSize / numFiles
                        for (f in 0 until numFiles) {
                            val entry = TarArchiveEntry("large_dummy_$f.bin")
                            entry.size = sizePerFile.toLong()
                            tos.putArchiveEntry(entry)
                            var written = 0
                            while (written < sizePerFile) {
                                writeBuffer(written, buffer, f * 10)
                                val toWrite = minOf(buffer.size, sizePerFile - written)
                                tos.write(buffer, 0, toWrite)
                                written += toWrite
                            }
                            tos.closeArchiveEntry()
                        }
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
                val sizePerFile = dataSize100m / numFiles
                for (f in 0 until numFiles) {
                    val entry = sevenZFile.createArchiveEntry(file7z100m, "large_dummy_$f.bin")
                    entry.size = sizePerFile.toLong()
                    sevenZFile.putArchiveEntry(entry)
                    var written = 0
                    while (written < sizePerFile) {
                        writeBuffer(written, buffer, f * 10)
                        val toWrite = minOf(buffer.size, sizePerFile - written)
                        sevenZFile.write(buffer, 0, toWrite)
                        written += toWrite
                    }
                    sevenZFile.closeArchiveEntry()
                }
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
                        val sizePerFile = dataSize100m / numFiles
                        for (f in 0 until numFiles) {
                            val entry = TarArchiveEntry("large_dummy_$f.bin")
                            entry.size = sizePerFile.toLong()
                            tos.putArchiveEntry(entry)
                            var written = 0
                            while (written < sizePerFile) {
                                writeBuffer(written, buffer, f * 10)
                                val toWrite = minOf(buffer.size, sizePerFile - written)
                                tos.write(buffer, 0, toWrite)
                                written += toWrite
                            }
                            tos.closeArchiveEntry()
                        }
                    }
                }
            }
        } else {
            println("large_100m.tar.gz already exists")
        }

        // zip (10MB)
        val fileZip = outDir.resolve("large.zip")
        if (!fileZip.exists()) {
            println("Generating large zip: ${fileZip.absolutePath}")
            FileOutputStream(fileZip).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val sizePerFile = dataSize / numFiles
                    for (f in 0 until numFiles) {
                        val entry = ZipEntry("large_dummy_$f.bin")
                        entry.size = sizePerFile.toLong()
                        zos.putNextEntry(entry)
                        var written = 0
                        while (written < sizePerFile) {
                            writeBuffer(written, buffer, f * 10)
                            val toWrite = minOf(buffer.size, sizePerFile - written)
                            zos.write(buffer, 0, toWrite)
                            written += toWrite
                        }
                        zos.closeEntry()
                    }
                }
            }
        } else {
            println("large.zip already exists")
        }

        // zip (100MB)
        val fileZip100m = outDir.resolve("large_100m.zip")
        if (!fileZip100m.exists()) {
            println("Generating 100MB zip: ${fileZip100m.absolutePath}")
            FileOutputStream(fileZip100m).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val sizePerFile = dataSize100m / numFiles
                    for (f in 0 until numFiles) {
                        val entry = ZipEntry("large_dummy_$f.bin")
                        entry.size = sizePerFile.toLong()
                        zos.putNextEntry(entry)
                        var written = 0
                        while (written < sizePerFile) {
                            writeBuffer(written, buffer, f * 10)
                            val toWrite = minOf(buffer.size, sizePerFile - written)
                            zos.write(buffer, 0, toWrite)
                            written += toWrite
                        }
                        zos.closeEntry()
                    }
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
                zos.putNextEntry(ZipEntry("directory\\subdir\\file1.txt"))
                zos.write("hello_thread1".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("directory\\subdir\\file2.txt"))
                zos.write("hello_thread2".toByteArray())
                zos.closeEntry()
            }
        } else {
            println("test_path_normal.zip already exists")
        }

        // tar.bz2 (10MB)
        val fileTarBz2 = outDir.resolve("large.tar.bz2")
        if (!fileTarBz2.exists()) {
            println("Generating large tar.bz2: ${fileTarBz2.absolutePath}")
            FileOutputStream(fileTarBz2).use { fos ->
                BZip2CompressorOutputStream(fos).use { bz2os ->
                    TarArchiveOutputStream(bz2os).use { tos ->
                        val sizePerFile = dataSize / numFiles
                        for (f in 0 until numFiles) {
                            val entry = TarArchiveEntry("large_dummy_$f.bin")
                            entry.size = sizePerFile.toLong()
                            tos.putArchiveEntry(entry)
                            var written = 0
                            while (written < sizePerFile) {
                                writeBuffer(written, buffer, f * 10)
                                val toWrite = minOf(buffer.size, sizePerFile - written)
                                tos.write(buffer, 0, toWrite)
                                written += toWrite
                            }
                            tos.closeArchiveEntry()
                        }
                    }
                }
            }
        } else {
            println("large.tar.bz2 already exists")
        }

        // tar.bz2 (100MB)
        val fileTarBz2100m = outDir.resolve("large_100m.tar.bz2")
        if (!fileTarBz2100m.exists()) {
            println("Generating 100MB tar.bz2: ${fileTarBz2100m.absolutePath}")
            FileOutputStream(fileTarBz2100m).use { fos ->
                BZip2CompressorOutputStream(fos).use { bz2os ->
                    TarArchiveOutputStream(bz2os).use { tos ->
                        val sizePerFile = dataSize100m / numFiles
                        for (f in 0 until numFiles) {
                            val entry = TarArchiveEntry("large_dummy_$f.bin")
                            entry.size = sizePerFile.toLong()
                            tos.putArchiveEntry(entry)
                            var written = 0
                            while (written < sizePerFile) {
                                writeBuffer(written, buffer, f * 10)
                                val toWrite = minOf(buffer.size, sizePerFile - written)
                                tos.write(buffer, 0, toWrite)
                                written += toWrite
                            }
                            tos.closeArchiveEntry()
                        }
                    }
                }
            }
        } else {
            println("large_100m.tar.bz2 already exists")
        }

        // test.bz2 (Small single bzip2 for extraction test)
        val fileTestBz2 = outDir.resolve("test.bz2")
        if (!fileTestBz2.exists()) {
            println("Generating test.bz2: ${fileTestBz2.absolutePath}")
            FileOutputStream(fileTestBz2).use { fos ->
                BZip2CompressorOutputStream(fos).use { bz2os ->
                    val content = "This is a dummy text file compressed using bzip2.".toByteArray()
                    bz2os.write(content)
                }
            }
        } else {
            println("test.bz2 already exists")
        }

        // test.tar.bz2 (Small tar.bz2 for extraction test)
        val fileTestTarBz2 = outDir.resolve("test.tar.bz2")
        if (!fileTestTarBz2.exists()) {
            println("Generating test.tar.bz2: ${fileTestTarBz2.absolutePath}")
            FileOutputStream(fileTestTarBz2).use { fos ->
                BZip2CompressorOutputStream(fos).use { bz2os ->
                    TarArchiveOutputStream(bz2os).use { tos ->
                        val content1 = (
                            "This is a dummy text file inside " +
                                "tar.bz2."
                            ).toByteArray()
                        val entry1 = TarArchiveEntry("dummy1.txt")
                        entry1.size = content1.size.toLong()
                        tos.putArchiveEntry(entry1)
                        tos.write(content1)
                        tos.closeArchiveEntry()

                        val content2 = (
                            "Some more dummy content in the " +
                                "second tar.bz2 file."
                            ).toByteArray()
                        val entry2 = TarArchiveEntry("dummy2.txt")
                        entry2.size = content2.size.toLong()
                        tos.putArchiveEntry(entry2)
                        tos.write(content2)
                        tos.closeArchiveEntry()
                    }
                }
            }
        } else {
            println("test.tar.bz2 already exists")
        }

        // test.tar.gz (Small tar.gz for extraction test)
        val fileTestTarGz = outDir.resolve("test.tar.gz")
        if (!fileTestTarGz.exists()) {
            println("Generating test.tar.gz: ${fileTestTarGz.absolutePath}")
            FileOutputStream(fileTestTarGz).use { fos ->
                GzipCompressorOutputStream(fos).use { gzos ->
                    TarArchiveOutputStream(gzos).use { tos ->
                        val content1 = "This is a dummy text file inside tar.gz.".toByteArray()
                        val entry1 = TarArchiveEntry("dummy1.txt")
                        entry1.size = content1.size.toLong()
                        tos.putArchiveEntry(entry1)
                        tos.write(content1)
                        tos.closeArchiveEntry()

                        val content2 = (
                            "Some more dummy content in the " +
                                "second tar.gz file."
                            ).toByteArray()
                        val entry2 = TarArchiveEntry("dummy2.txt")
                        entry2.size = content2.size.toLong()
                        tos.putArchiveEntry(entry2)
                        tos.write(content2)
                        tos.closeArchiveEntry()

                        val content3 = "Windows path data".toByteArray()
                        val entry3 = TarArchiveEntry("nested\\windows\\path.txt")
                        entry3.size = content3.size.toLong()
                        tos.putArchiveEntry(entry3)
                        tos.write(content3)
                        tos.closeArchiveEntry()
                    }
                }
            }
        } else {
            println("test.tar.gz already exists")
        }

        // test_ext.zip (For extension test)
        val fileTestExt = outDir.resolve("test_ext.zip")
        if (!fileTestExt.exists()) {
            println("Generating test_ext.zip: ${fileTestExt.absolutePath}")
            FileOutputStream(fileTestExt).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val content1 = "hello_extension1".toByteArray()
                    zos.putNextEntry(ZipEntry("test1.txt"))
                    zos.write(content1)
                    zos.closeEntry()

                    val content2 = "hello_extension2".toByteArray()
                    zos.putNextEntry(ZipEntry("test2.txt"))
                    zos.write(content2)
                    zos.closeEntry()
                }
            }
        } else {
            println("test_ext.zip already exists")
        }

        // test_bulk.zip (Bulk metadata test with 100 entries)
        val fileTestBulk = outDir.resolve("test_bulk.zip")
        if (!fileTestBulk.exists()) {
            println("Generating test_bulk.zip: ${fileTestBulk.absolutePath}")
            FileOutputStream(fileTestBulk).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    val content = "data".toByteArray()
                    for (i in 0 until 100) {
                        zos.putNextEntry(ZipEntry("folder/subfolder/file_$i.txt"))
                        zos.write(content)
                        zos.closeEntry()
                    }
                }
            }
        } else {
            println("test_bulk.zip already exists")
        }
    }
}
