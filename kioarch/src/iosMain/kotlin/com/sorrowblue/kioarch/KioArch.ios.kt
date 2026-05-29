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
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.sorrowblue.kioarch

import kotlinx.cinterop.*
import kotlinx.io.files.Path
import kotlinx.io.Sink
import platform.posix.*
import platform.Foundation.*
import com.sorrowblue.kioarch.internal.cinterop.*

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun decodeCName(cPointer: CPointer<ByteVar>?): String {
    if (cPointer == null) return ""
    
    // Calculate length of C string safely without POSIX strlen type issues
    var len = 0uL
    while (cPointer[len.toInt()] != 0.toByte()) {
        len++
    }
    if (len == 0uL) return ""
    
    // 1. Try UTF-8
    val nsStringUtf8 = NSString.create(bytes = cPointer, length = len, encoding = NSUTF8StringEncoding)
    if (nsStringUtf8 != null) {
        return nsStringUtf8 as String
    }
    
    // 2. Try Windows-31J / Shift_JIS (0x80000A01uL)
    val nsStringSjis = NSString.create(bytes = cPointer, length = len, encoding = 0x80000A01uL)
    if (nsStringSjis != null) {
        return nsStringSjis as String
    }
    
    // 3. Try Standard Shift_JIS
    val nsStringStdSjis = NSString.create(bytes = cPointer, length = len, encoding = NSShiftJISStringEncoding)
    if (nsStringStdSjis != null) {
        return nsStringStdSjis as String
    }
    
    // Fallback
    return cPointer.toKString()
}

private inline fun <T> NSLock.withLock(action: () -> T): T {
    this.lock()
    try {
        return action()
    } finally {
        this.unlock()
    }
}

private class IosPathSeekableSource(pathStr: String) : SeekableSource {
    private val file = fopen(pathStr, "rb") ?: throw ArchiveIOException("Failed to open file: $pathStr")
    private val lock = NSLock()
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        buffer.usePinned { pinned ->
            val bytesRead = fread(pinned.addressOf(offset), 1uL, length.toULong(), file)
            if (bytesRead == 0uL) {
                if (feof(file) != 0) {
                    return -1
                }
                throw ArchiveIOException("Error reading from file")
            }
            bytesRead.toInt()
        }
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            fseek(file, position, SEEK_SET)
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else ftell(file)
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) 0L else {
            val current = ftell(file)
            fseek(file, 0L, SEEK_END)
            val len = ftell(file)
            fseek(file, current, SEEK_SET)
            len
        }
    }

    override fun close() {
        lock.withLock {
            if (!isClosed) {
                fclose(file)
                isClosed = true
            }
        }
    }
}

private class IosNSDataSeekableSource(private val nsData: NSData) : SeekableSource {
    private val lock = NSLock()
    private var pos = 0L
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        val totalLength = nsData.length.toLong()
        if (pos >= totalLength) return -1
        val available = totalLength - pos
        val toRead = if (length > available) available.toInt() else length
        if (toRead <= 0) return -1

        buffer.usePinned { pinned ->
            val rawPtr = nsData.bytes?.reinterpret<ByteVar>() ?: return@withLock -1
            val src = rawPtr + pos
            memcpy(pinned.addressOf(offset), src, toRead.toULong())
        }
        pos += toRead
        toRead
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            val totalLength = nsData.length.toLong()
            pos = if (position < 0) {
                0L
            } else if (position > totalLength) {
                totalLength
            } else {
                position
            }
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else pos
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) 0L else nsData.length.toLong()
    }

    override fun close() {
        lock.withLock {
            isClosed = true
        }
    }
}

private class IosNSURLSeekableSource(private val nsUrl: NSURL) : SeekableSource {
    private val delegate: IosPathSeekableSource

    init {
        val path = nsUrl.path ?: throw ArchiveIOException("URL path is null: $nsUrl")
        delegate = IosPathSeekableSource(path)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)
    override fun seek(position: Long) = delegate.seek(position)
    override fun position(): Long = delegate.position()
    override fun length(): Long = delegate.length()
    override fun close() = delegate.close()
}

private class IosNSFileHandleSeekableSource(private val fileHandle: NSFileHandle) : SeekableSource {
    private val lock = NSLock()
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        if (isClosed) return -1
        val data = fileHandle.readDataUpToLength(length.toULong(), null) ?: return -1
        if (data.length == 0uL) return -1

        data.bytes?.let { bytesPtr ->
            buffer.usePinned { pinned ->
                memcpy(pinned.addressOf(offset), bytesPtr, data.length)
            }
        }
        data.length.toInt()
    }

    override fun seek(position: Long) {
        lock.withLock {
            if (isClosed) return
            fileHandle.seekToOffset(position.toULong(), null)
        }
    }

    override fun position(): Long = lock.withLock {
        if (isClosed) 0L else fileHandle.offsetInFile.toLong()
    }

    override fun length(): Long = lock.withLock {
        if (isClosed) 0L else {
            val current = fileHandle.offsetInFile
            fileHandle.seekToEndOfFile()
            val len = fileHandle.offsetInFile.toLong()
            fileHandle.seekToOffset(current, null)
            len
        }
    }

    override fun close() {
        lock.withLock {
            if (!isClosed) {
                fileHandle.closeFile()
                isClosed = true
            }
        }
    }
}

private class IosArchiveReader(
    private val sourceStableRef: StableRef<SeekableSource>,
    private val handle: Long
) : ArchiveReader {

    private val lock = NSLock()
    private var isClosed = false

    override fun getEntries(): List<ArchiveEntry> = lock.withLock {
        if (isClosed) throw IllegalStateException("Reader is closed")
        val count = kio_get_entry_count(handle.toULong())
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        memScoped {
            val entry = alloc<kio_entry_t>()
            for (i in 0 until count) {
                if (kio_get_entry(handle.toULong(), i, entry.ptr) != 0) {
                    val cName = entry.name
                    val nameStr = decodeCName(cName)
                    val normalizedName = nameStr.replace('\\', '/')
                    list.add(
                        ArchiveEntry(
                            index = entry.index,
                            name = normalizedName,
                            size = entry.size,
                            compressedSize = entry.size,
                            isDirectory = entry.is_dir != 0,
                            crc = entry.crc.toLong()
                        )
                    )
                    if (cName != null) {
                        free(cName)
                    }
                }
            }
        }
        list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) = lock.withLock {
        if (isClosed) throw IllegalStateException("Reader is closed")
        val sinkStableRef = StableRef.create(sink)
        try {
            memScoped {
                val errMsgBytes = allocArray<ByteVar>(512)
                
                val kioSink = alloc<kio_sink_t>().apply {
                    write = staticCFunction { opaque: COpaquePointer?, buf: CPointer<UByteVar>?, len: Int ->
                        if (opaque == null || buf == null) return@staticCFunction
                        val innerSink = opaque.asStableRef<Sink>().get()
                        val byteArray = ByteArray(len)
                        for (i in 0 until len) {
                            byteArray[i] = buf[i].toByte()
                        }
                        innerSink.write(byteArray, 0, len)
                    }
                    opaque = sinkStableRef.asCPointer()
                }

                val success = kio_extract_entry(
                    handle.toULong(),
                    entry.index,
                    kioSink.readValue(),
                    errMsgBytes,
                    512
                )

                if (success == 0) {
                    val errMsg = errMsgBytes.toKString()
                    throw ArchiveIOException("Failed to extract entry: ${entry.name}. Native error: $errMsg")
                }
            }
        } finally {
            sinkStableRef.dispose()
        }
    }

    override fun close() = lock.withLock {
        if (!isClosed) {
            kio_close_archive(handle.toULong())
            sourceStableRef.dispose()
            isClosed = true
        }
    }
}

/**
 * iOS implementation of [KioArch] leveraging pure C archive decompression engine.
 */
public actual object KioArch {

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for iOS.
     *
     * @param source the seekable source containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    @Suppress("MagicNumber")
    private fun isBzip2(source: SeekableSource): Boolean {
        val current = source.position()
        source.seek(0)
        val buf = ByteArray(3)
        val read = source.read(buf, 0, 3)
        source.seek(current)
        return read >= 3 &&
            buf[0] == 0x42.toByte() &&
            buf[1] == 0x5A.toByte() &&
            buf[2] == 0x68.toByte()
    }

    private fun wrapIfNeeded(source: SeekableSource, reader: ArchiveReader): ArchiveReader {
        return if (isBzip2(source)) Bzip2ArchiveReader(reader) else reader
    }

    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val sourceStableRef = StableRef.create(source)
        try {
            var handle: ULong = 0uL
            memScoped {
                val errMsgBytes = allocArray<ByteVar>(512)
                
                val kioSource = alloc<kio_source_t>().apply {
                    read = staticCFunction { opaque: COpaquePointer?, buf: CPointer<UByteVar>?, len: Int ->
                        if (opaque == null || buf == null) return@staticCFunction -1
                        val innerSource = opaque.asStableRef<SeekableSource>().get()
                        val byteArray = ByteArray(len)
                        val readBytes = innerSource.read(byteArray, 0, len)
                        if (readBytes > 0) {
                            for (i in 0 until readBytes) {
                                buf[i] = byteArray[i].toUByte()
                            }
                        }
                        readBytes
                    }
                    seek = staticCFunction { opaque: COpaquePointer?, pos: Long ->
                        if (opaque == null) return@staticCFunction
                        val innerSource = opaque.asStableRef<SeekableSource>().get()
                        innerSource.seek(pos)
                    }
                    position = staticCFunction { opaque: COpaquePointer? ->
                        if (opaque == null) return@staticCFunction 0L
                        val innerSource = opaque.asStableRef<SeekableSource>().get()
                        innerSource.position()
                    }
                    length = staticCFunction { opaque: COpaquePointer? ->
                        if (opaque == null) return@staticCFunction 0L
                        val innerSource = opaque.asStableRef<SeekableSource>().get()
                        innerSource.length()
                    }
                    opaque = sourceStableRef.asCPointer()
                }

                handle = kio_open_archive(kioSource.readValue(), errMsgBytes, 512)
                if (handle == 0uL) {
                    val errMsg = errMsgBytes.toKString()
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
            }
            return wrapIfNeeded(source, IosArchiveReader(sourceStableRef, handle.toLong()))
        } catch (e: Exception) {
            sourceStableRef.dispose()
            throw e
        }
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for iOS.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return createReader(ByteArraySeekableSource(byteArray))
    }

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for iOS.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(path: Path): ArchiveReader {
        return createReader(IosPathSeekableSource(path.toString()))
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [NSData] for iOS.
     *
     * @param nsData the [NSData] containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public fun createReader(nsData: NSData): ArchiveReader {
        return createReader(IosNSDataSeekableSource(nsData))
    }

    /**
     * Creates an [ArchiveReader] from an [NSURL] for iOS.
     *
     * @param nsUrl the [NSURL] pointing to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public fun createReader(nsUrl: NSURL): ArchiveReader {
        return createReader(IosNSURLSeekableSource(nsUrl))
    }

    /**
     * Creates an [ArchiveReader] from an [NSFileHandle] for iOS.
     *
     * @param fileHandle the [NSFileHandle] to read the archive from
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public fun createReader(fileHandle: NSFileHandle): ArchiveReader {
        return createReader(IosNSFileHandleSeekableSource(fileHandle))
    }
}

