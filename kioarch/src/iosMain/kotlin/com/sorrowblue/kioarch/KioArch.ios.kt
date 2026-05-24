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

package com.sorrowblue.kioarch

import kotlinx.cinterop.*
import kotlinx.io.files.Path
import kotlinx.io.Sink
import platform.posix.*
import com.sorrowblue.kioarch.internal.cinterop.*

private class IosPathSeekableSource(pathStr: String) : SeekableSource {
    private val file = fopen(pathStr, "rb") ?: throw ArchiveException("Failed to open file: $pathStr")
    private val lock = Any()
    private var isClosed = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return synchronized(lock) {
            if (isClosed) return -1
            buffer.usePinned { pinned ->
                val bytesRead = fread(pinned.addressOf(offset), 1uL, length.toULong(), file)
                if (bytesRead == 0uL) {
                    if (feof(file) != 0) {
                        return -1
                    }
                    throw ArchiveException("Error reading from file")
                }
                bytesRead.toInt()
            }
        }
    }

    override fun seek(position: Long) {
        synchronized(lock) {
            if (isClosed) return
            fseek(file, position, SEEK_SET)
        }
    }

    override fun position(): Long {
        return synchronized(lock) {
            if (isClosed) 0L else ftell(file)
        }
    }

    override fun length(): Long {
        return synchronized(lock) {
            if (isClosed) 0L else {
                val current = ftell(file)
                fseek(file, 0L, SEEK_END)
                val len = ftell(file)
                fseek(file, current, SEEK_SET)
                len
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                fclose(file)
                isClosed = true
            }
        }
    }
}

private class IosArchiveReader(
    private val sourceStableRef: StableRef<SeekableSource>,
    private val handle: Long
) : ArchiveReader {

    private val lock = Any()
    private var isClosed = false

    override fun getEntries(): List<ArchiveEntry> {
        return synchronized(lock) {
            if (isClosed) throw IllegalStateException("Reader is closed")
            val count = kio_get_entry_count(handle.toULong())
            if (count < 0) return emptyList()

            val list = ArrayList<ArchiveEntry>(count)
            memScoped {
                val entry = alloc<kio_entry_t>()
                for (i in 0 until count) {
                    if (kio_get_entry(handle.toULong(), i, entry.ptr) != 0) {
                        val cName = entry.name
                        val nameStr = cName?.toKString() ?: ""
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
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        synchronized(lock) {
            if (isClosed) throw IllegalStateException("Reader is closed")
            val sinkStableRef = StableRef.create(sink)
            try {
                memScoped {
                    val errMsgBytes = allocArray<ByteVar>(512)
                    
                    val kioSink = alloc<kio_sink_t>().apply {
                        write = staticCFunction { opaque: COpaquePointer?, buf: CPointer<ByteVar>?, len: Int ->
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
                        throw ArchiveException("Failed to extract entry: ${entry.name}. Native error: $errMsg")
                    }
                }
            } finally {
                sinkStableRef.dispose()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!isClosed) {
                kio_close_archive(handle.toULong())
                sourceStableRef.dispose()
                isClosed = true
            }
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
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val sourceStableRef = StableRef.create(source)
        try {
            var handle: ULong = 0uL
            memScoped {
                val errMsgBytes = allocArray<ByteVar>(512)
                
                val kioSource = alloc<kio_source_t>().apply {
                    read = staticCFunction { opaque: COpaquePointer?, buf: CPointer<ByteVar>?, len: Int ->
                        if (opaque == null || buf == null) return@staticCFunction -1
                        val innerSource = opaque.asStableRef<SeekableSource>().get()
                        val byteArray = ByteArray(len)
                        val readBytes = innerSource.read(byteArray, 0, len)
                        if (readBytes > 0) {
                            for (i in 0 until readBytes) {
                                buf[i] = byteArray[i].toByte()
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
                    throw ArchiveException("Failed to open archive. Native error: $errMsg")
                }
            }
            return IosArchiveReader(sourceStableRef, handle.toLong())
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
}
