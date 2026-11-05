/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sorrowblue.kioarch.benchmark

import com.sorrowblue.kioarch.FileSeekableSource
import com.sorrowblue.kioarch.KioArch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import net.sf.sevenzipjbinding.ArchiveFormat
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import net.sf.sevenzipjbinding.util.ByteArrayStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * JVM and Android benchmark comparing [KioArch] against Apache Commons Compress
 * and 7-Zip-JBinding across ZIP, 7z, and TAR.GZ archives.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
public open class JniZeroCopyBenchmark {

    @Param("zip", "7z", "targz")
    public var format: String = "zip"

    @Param("small", "medium", "large")
    public var profile: String = "medium"

    private lateinit var archiveFile: File

    private val discardingSink = object : RawSink {
        override fun write(source: Buffer, byteCount: Long) {
            source.skip(byteCount)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    @Setup
    public fun setup() {
        val archiveBytes = BenchmarkArchiveGenerator.generate(format, profile)
        archiveFile = File.createTempFile("kioarch_lib_bench_${profile}_", ".$format")
        archiveFile.writeBytes(archiveBytes)

        // Pre-initialize KioArch JNI and 7-Zip-JBinding native libraries outside timed runs
        KioArch.createReader(archiveBytes).close()
        if (!SevenZip.isInitializedSuccessfully()) {
            SevenZip.initSevenZipFromPlatformJAR()
        }
    }

    @TearDown
    public fun tearDown() {
        archiveFile.delete()
    }

    @Benchmark
    public fun extractKioArch(): Long {
        var totalBytes = 0L
        KioArch.createReader(FileSeekableSource(archiveFile)).use { reader ->
            val sink = discardingSink.buffered()
            for (entry in reader.getEntries()) {
                if (!entry.isDirectory) {
                    reader.extractEntry(entry, sink)
                    sink.flush()
                    totalBytes += entry.size
                }
            }
        }
        return totalBytes
    }

    @Benchmark
    public fun extractCommonsCompress(): Long = when (format) {
        "zip" -> extractCommonsZip()
        "7z" -> extractCommons7z()
        "targz" -> extractCommonsTarGz()
        else -> error("Unsupported format: $format")
    }

    @Benchmark
    public fun extractSevenZipJBinding(): Long = when (format) {
        "zip", "7z" -> extractSevenZipDirect()
        "targz" -> extractSevenZipTarGz()
        else -> error("Unsupported format: $format")
    }

    private fun extractCommonsZip(): Long {
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipFile.builder().setFile(archiveFile).get().use { zipFile ->
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory) {
                    zipFile.getInputStream(entry).use { input ->
                        totalBytes += drainStream(input, buffer)
                    }
                }
            }
        }
        return totalBytes
    }

    private fun extractCommons7z(): Long {
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
            var entry = sevenZFile.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    var read = sevenZFile.read(buffer)
                    while (read != -1) {
                        totalBytes += read
                        read = sevenZFile.read(buffer)
                    }
                }
                entry = sevenZFile.nextEntry
            }
        }
        return totalBytes
    }

    private fun extractCommonsTarGz(): Long {
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archiveFile)))
        ).use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    totalBytes += drainStream(tis, buffer)
                }
                entry = tis.nextEntry
            }
        }
        return totalBytes
    }

    private fun drainStream(input: java.io.InputStream, buffer: ByteArray): Long {
        var total = 0L
        var read = input.read(buffer)
        while (read != -1) {
            total += read
            read = input.read(buffer)
        }
        return total
    }

    private fun extractSevenZipDirect(): Long {
        var totalBytes = 0L
        RandomAccessFile(archiveFile, "r").use { raf ->
            RandomAccessFileInStream(raf).use { inStream ->
                SevenZip.openInArchive(null, inStream).use { inArchive ->
                    val callback = DiscardingExtractCallback(inArchive) { count ->
                        totalBytes += count
                    }
                    inArchive.extract(null, false, callback)
                }
            }
        }
        return totalBytes
    }

    private fun extractSevenZipTarGz(): Long {
        var totalBytes = 0L
        RandomAccessFile(archiveFile, "r").use { raf ->
            RandomAccessFileInStream(raf).use { inStream ->
                SevenZip.openInArchive(ArchiveFormat.GZIP, inStream).use { gzipArchive ->
                    val tarStream = ByteArrayStream(MAX_TAR_STREAM_BYTES)
                    gzipArchive.extractSlow(0, tarStream)
                    tarStream.rewind()
                    SevenZip.openInArchive(ArchiveFormat.TAR, tarStream).use { tarArchive ->
                        val callback = DiscardingExtractCallback(tarArchive) { count ->
                            totalBytes += count
                        }
                        tarArchive.extract(null, false, callback)
                    }
                }
            }
        }
        return totalBytes
    }

    private class DiscardingExtractCallback(
        private val inArchive: IInArchive,
        onBytes: (Int) -> Unit
    ) : IArchiveExtractCallback {
        private val outStream = ISequentialOutStream { data ->
            onBytes(data.size)
            data.size
        }

        override fun getStream(index: Int, extractAskMode: ExtractAskMode): ISequentialOutStream? {
            if (extractAskMode != ExtractAskMode.EXTRACT) return null
            val isFolder = inArchive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
            return if (isFolder) null else outStream
        }

        override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit
        override fun setOperationResult(extractOperationResult: ExtractOperationResult) = Unit
        override fun setTotal(total: Long) = Unit
        override fun setCompleted(completeValue: Long) = Unit
    }

    private companion object {
        private const val MAX_TAR_STREAM_BYTES = 32 * 1024 * 1024
    }
}
