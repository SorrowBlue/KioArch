import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

@CacheableTask
abstract class GenerateTestFilesTask @Inject constructor(
    private val workerExecutor: WorkerExecutor
) : DefaultTask() {

    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val queue = workerExecutor.processIsolation {
            classpath.from(generatorClasspath)
        }
        queue.submit(GenerateTestFilesWorkAction::class.java) {
            targetDir.set(outputDir)
        }
    }
}

interface GenerateTestFilesParameters : WorkParameters {
    val targetDir: DirectoryProperty
}

@Suppress("MagicNumber")
abstract class GenerateTestFilesWorkAction : WorkAction<GenerateTestFilesParameters> {

    override fun execute() {
        val outDir = parameters.targetDir.get().asFile
        outDir.mkdirs()

        val buffer = ByteArray(1024 * 1024)
        generateLarge7z(outDir.resolve("large.7z"), buffer)
        generateLargeTarGz(outDir.resolve("large.tar.gz"), buffer)
        generateLargeZip(outDir.resolve("large.zip"), buffer)
        generateSmallZips(outDir)
        generateSmall7zAndTar(outDir)
    }

    private fun writeBuffer(written: Int, buffer: ByteArray, offset: Int) {
        for (i in buffer.indices) {
            buffer[i] = ((written + i + offset) % 256).toByte()
        }
    }

    private fun generateLarge7z(file: File, buffer: ByteArray) {
        val sizePerFile = (10 * 1024 * 1024) / 100
        SevenZOutputFile(file).use { sevenZFile ->
            for (f in 0 until 100) {
                val entry = sevenZFile.createArchiveEntry(file, "large_dummy_$f.bin")
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
    }

    private fun generateLargeTarGz(file: File, buffer: ByteArray) {
        val sizePerFile = (10 * 1024 * 1024) / 100
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(file))).use { tos ->
            for (f in 0 until 100) {
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

    private fun generateLargeZip(file: File, buffer: ByteArray) {
        val sizePerFile = (10 * 1024 * 1024) / 100
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            for (f in 0 until 100) {
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

    private fun generateSmallZips(outDir: File) {
        val fileTestZip = outDir.resolve("test.zip")
        ZipOutputStream(FileOutputStream(fileTestZip)).use { zos ->
            zos.putNextEntry(ZipEntry("dummy1.txt"))
            zos.write("This is a dummy text file inside zip.".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("dummy2.txt"))
            zos.write(ByteArray(1200) { 'a'.code.toByte() })
            zos.closeEntry()
        }

        val fileTestSjis = outDir.resolve("test_sjis.zip")
        val edgeCaseNames = listOf(
            "テスト_日本語ファイル名_Shift_JIS.txt",
            "dame_moji_ソ表能予.txt",
            "half_width_ｶﾀｶﾅﾃｽﾄ.txt",
            "cp932_extensions_①Ⅳ髙﨑.txt"
        )
        ZipOutputStream(FileOutputStream(fileTestSjis), Charset.forName("MS932")).use { zos ->
            for (name in edgeCaseNames) {
                zos.putNextEntry(ZipEntry(name))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
        }

        val fileTestPathNormal = outDir.resolve("test_path_normal.zip")
        ZipOutputStream(FileOutputStream(fileTestPathNormal)).use { zos ->
            zos.putNextEntry(ZipEntry("directory\\subdir\\file1.txt"))
            zos.write("hello_thread1".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("directory\\subdir\\file2.txt"))
            zos.write("hello_thread2".toByteArray())
            zos.closeEntry()
        }

        generateBulkAndExtZips(outDir)
    }

    private fun generateBulkAndExtZips(outDir: File) {
        val fileTestExt = outDir.resolve("test_ext.zip")
        ZipOutputStream(FileOutputStream(fileTestExt)).use { zos ->
            zos.putNextEntry(ZipEntry("test1.txt"))
            zos.write("hello_extension1".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("test2.txt"))
            zos.write("hello_extension2".toByteArray())
            zos.closeEntry()
        }

        val fileTestBulk = outDir.resolve("test_bulk.zip")
        ZipOutputStream(FileOutputStream(fileTestBulk)).use { zos ->
            val content = "data".toByteArray()
            for (i in 0 until 100) {
                zos.putNextEntry(ZipEntry("folder/subfolder/file_$i.txt"))
                zos.write(content)
                zos.closeEntry()
            }
        }
    }

    private fun generateSmall7zAndTar(outDir: File) {
        val fileTest7z = outDir.resolve("test.7z")
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

        val fileTestBz2 = outDir.resolve("test.bz2")
        BZip2CompressorOutputStream(FileOutputStream(fileTestBz2)).use { bz2os ->
            bz2os.write("This is a dummy text file compressed using bzip2.".toByteArray())
        }

        generateSmallTarArchives(outDir)
    }

    private fun generateSmallTarArchives(outDir: File) {
        val fileTestTarBz2 = outDir.resolve("test.tar.bz2")
        TarArchiveOutputStream(
            BZip2CompressorOutputStream(FileOutputStream(fileTestTarBz2))
        ).use { tos ->
            val c1 = "This is a dummy text file inside tar.bz2.".toByteArray()
            val e1 = TarArchiveEntry("dummy1.txt").apply { size = c1.size.toLong() }
            tos.putArchiveEntry(e1)
            tos.write(c1)
            tos.closeArchiveEntry()

            val c2 = "Some more dummy content in the second tar.bz2 file.".toByteArray()
            val e2 = TarArchiveEntry("dummy2.txt").apply { size = c2.size.toLong() }
            tos.putArchiveEntry(e2)
            tos.write(c2)
            tos.closeArchiveEntry()
        }

        val fileTestTarGz = outDir.resolve("test.tar.gz")
        TarArchiveOutputStream(
            GzipCompressorOutputStream(FileOutputStream(fileTestTarGz))
        ).use { tos ->
            val c1 = "This is a dummy text file inside tar.gz.".toByteArray()
            val e1 = TarArchiveEntry("dummy1.txt").apply { size = c1.size.toLong() }
            tos.putArchiveEntry(e1)
            tos.write(c1)
            tos.closeArchiveEntry()

            val c2 = "Some more dummy content in the second tar.gz file.".toByteArray()
            val e2 = TarArchiveEntry("dummy2.txt").apply { size = c2.size.toLong() }
            tos.putArchiveEntry(e2)
            tos.write(c2)
            tos.closeArchiveEntry()

            val c3 = "Windows path data".toByteArray()
            val e3 = TarArchiveEntry("nested\\windows\\path.txt").apply {
                size = c3.size.toLong()
            }
            tos.putArchiveEntry(e3)
            tos.write(c3)
            tos.closeArchiveEntry()
        }
    }
}
