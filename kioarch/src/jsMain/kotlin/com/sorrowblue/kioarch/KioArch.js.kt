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

import kotlinx.io.Sink
import kotlinx.io.files.Path
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private const val SOURCE_STRUCT_SIZE = 20
private const val ENTRY_STRUCT_SIZE = 24
private const val SINK_STRUCT_SIZE = 8
private const val ERR_MSG_BUFFER_SIZE = 512
private const val ENTRY_SIZE_OFFSET = 8

// Global map to track active Kotlin objects passed to C callbacks
private val opaqueMap = mutableMapOf<Int, Any>()
private var nextOpaqueId = 1

@JsName("registerOpaque")
internal fun registerOpaque(obj: Any): Int {
    val id = nextOpaqueId++
    opaqueMap[id] = obj
    return id
}

@JsName("getOpaque")
internal fun getOpaque(id: Int): Any? = opaqueMap[id]

@JsName("releaseOpaque")
internal fun releaseOpaque(id: Int) {
    opaqueMap.remove(id)
}

private fun wasmUtf8ToString(wasm: dynamic, namePtr: Int): String {
    val decoder = js(
        """
        function(wasm, namePtr) {
            var len = 0;
            while (wasm.HEAPU8[namePtr + len] !== 0) {
                len++;
            }
            if (len === 0) return "";
            var bytes = new Uint8Array(wasm.HEAPU8.buffer, namePtr, len);
            
            var isUtf8 = true;
            var i = 0;
            while (i < bytes.length) {
                var b1 = bytes[i];
                if (b1 <= 0x7F) {
                    i++;
                } else if (b1 >= 0xC2 && b1 <= 0xDF) {
                    if (i + 1 >= bytes.length || bytes[i+1] < 0x80 || bytes[i+1] > 0xBF) { isUtf8 = false; break; }
                    i += 2;
                } else if (b1 >= 0xE0 && b1 <= 0xEF) {
                    if (i + 2 >= bytes.length || bytes[i+1] < 0x80 || bytes[i+1] > 0xBF || bytes[i+2] < 0x80 || bytes[i+2] > 0xBF) { isUtf8 = false; break; }
                    if (b1 === 0xE0 && bytes[i+1] < 0xA0) { isUtf8 = false; break; }
                    if (b1 === 0xED && bytes[i+1] > 0x9F) { isUtf8 = false; break; }
                    i += 3;
                } else if (b1 >= 0xF0 && b1 <= 0xF4) {
                    if (i + 3 >= bytes.length || bytes[i+1] < 0x80 || bytes[i+1] > 0xBF || bytes[i+2] < 0x80 || bytes[i+2] > 0xBF || bytes[i+3] < 0x80 || bytes[i+3] > 0xBF) { isUtf8 = false; break; }
                    if (b1 === 0xF0 && bytes[i+1] < 0x90) { isUtf8 = false; break; }
                    if (b1 === 0xF4 && bytes[i+1] > 0x8F) { isUtf8 = false; break; }
                    i += 4;
                } else {
                    isUtf8 = false;
                    break;
                }
            }
            
            if (isUtf8) {
                try {
                    var utf8Decoder = new TextDecoder('utf-8', { fatal: true });
                    return utf8Decoder.decode(bytes);
                } catch (e) {}
            }
            
            var sjisLabels = ['shift_jis', 'shift-jis', 'windows-31j', 'x-sjis'];
            for (var j = 0; j < sjisLabels.length; j++) {
                try {
                    var sjisDecoder = new TextDecoder(sjisLabels[j]);
                    return sjisDecoder.decode(bytes);
                } catch (err) {}
            }
            
            return wasm.UTF8ToString(namePtr);
        }
    """
    ) as (dynamic, Int) -> String
    return decoder(wasm, namePtr)
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeRead(wasm: dynamic, source: Any, bufPtr: Int, len: Int): Int {
    val innerSource = source as? SeekableSource ?: return -1
    return try {
        val tmpArray = ByteArray(len)
        val bytesRead = innerSource.read(tmpArray, 0, len)
        if (bytesRead > 0) {
            val jsArray = tmpArray.asDynamic() as Int8Array
            val view = Uint8Array(
                jsArray.buffer,
                jsArray.byteOffset,
                bytesRead
            )
            wasm.HEAPU8.set(view, bufPtr)
        }
        bytesRead
    } catch (e: Throwable) {
        -1
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeSeek(source: Any, pos: Double) {
    try {
        (source as? SeekableSource)?.seek(pos.toLong())
    } catch (e: Throwable) {
        // Safe guard to prevent throwing into C++ Wasm boundary
    }
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgePosition(source: Any): Double = try {
    (source as? SeekableSource)?.position()?.toDouble() ?: 0.0
} catch (e: Throwable) {
    0.0
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeLength(source: Any): Double = try {
    (source as? SeekableSource)?.length()?.toDouble() ?: 0.0
} catch (e: Throwable) {
    0.0
}

@Suppress("SwallowedException", "TooGenericExceptionCaught")
private fun bridgeWrite(wasm: dynamic, sink: Any, bufPtr: Int, len: Int) {
    val innerSink = sink as? Sink ?: return
    try {
        val tmpArray = ByteArray(len)
        val jsArray = tmpArray.asDynamic() as Int8Array
        val view = Int8Array(
            wasm.HEAPU8.buffer as ArrayBuffer,
            bufPtr,
            len
        )
        jsArray.set(view)
        innerSink.write(tmpArray, 0, len)
    } catch (e: Throwable) {
        // Safe guard to prevent throwing into C++ Wasm boundary
    }
}

/**
 * JS implementation of [KioArch] utilizing WebAssembly compiled from pure C archive engines.
 */
public actual object KioArch {

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for JS.
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
        val wasm: dynamic = wasmModule ?: throwNotInitialized()

        val sourceOpaqueId = registerOpaque(source)
        var sourcePtr = 0
        var ptrs: SourceCallbackPtrs? = null
        var handle: Long = 0L

        try {
            // Register callbacks to Emscripten function table
            ptrs = registerJsSourceCallbacks(wasm)

            // Allocate and populate kio_source_t structure (20 bytes)
            sourcePtr = wasm._malloc(SOURCE_STRUCT_SIZE) as Int
            val heap32 = wasm.HEAP32
            val sourceWordOffset = sourcePtr / 4
            heap32[sourceWordOffset + 0] = ptrs.readPtr
            heap32[sourceWordOffset + 1] = ptrs.seekPtr
            heap32[sourceWordOffset + 2] = ptrs.posPtr
            heap32[sourceWordOffset + 3] = ptrs.lenPtr
            heap32[sourceWordOffset + 4] = sourceOpaqueId

            // Allocate error message buffer (512 bytes)
            val errMsgPtr = wasm._malloc(ERR_MSG_BUFFER_SIZE) as Int
            try {
                val rawHandle = wasm._kio_open_archive(sourcePtr, errMsgPtr, ERR_MSG_BUFFER_SIZE)

                // Emscripten handles 64-bit unsigned int returns as BigInt or low/high parts.
                // In modern Emscripten with BigInt support, it is returned as a BigInt.
                val handleVal = (
                    js(
                    "function(raw) { return globalThis['BigInt'](raw); }"
                ) as (dynamic) -> dynamic
                )(rawHandle)
                val isZero = (
                    js(
                    "function(raw) { return globalThis['BigInt'](raw) === globalThis['BigInt'](0); }"
                ) as (dynamic) -> Boolean
                )(rawHandle)
                if (isZero) {
                    // Extract error message
                    val errMsg = wasmUtf8ToString(wasm, errMsgPtr)
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
                handle = handleVal.toString().toLong()
            } finally {
                wasm._free(errMsgPtr)
            }

            return wrapIfNeeded(source, JsArchiveReader(
                wasm,
                sourceOpaqueId,
                sourcePtr,
                ptrs.readPtr,
                ptrs.seekPtr,
                ptrs.posPtr,
                ptrs.lenPtr,
                handle
            ))
        } catch (e: Exception) {
            cleanupSourcePtrs(wasm, sourcePtr, ptrs, sourceOpaqueId)
            throw e
        }
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for JS.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader =
        createReader(ByteArraySeekableSource(byteArray))

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for JS.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(path: Path): ArchiveReader {
        if (isNodeJs()) {
            return createReader(NodeFileSeekableSource(path.toString()))
        }
        throw UnsupportedOperationException(
            "File system access using Path is not supported in browser JS environment. Use ByteArray or custom SeekableSource."
        )
    }
}

private class SourceCallbackPtrs(
    val readPtr: Int,
    val seekPtr: Int,
    val posPtr: Int,
    val lenPtr: Int
)

private fun throwNotInitialized(): Nothing {
    throw IllegalStateException(
        "KioArch has not been initialized. Call KioArch.initialize(module) first."
    )
}

private fun registerJsSourceCallbacks(wasm: dynamic): SourceCallbackPtrs {
    val readFuncCreator = js(
        """
        function(wasm, getOpaque, bridgeRead) {
            return wasm.addFunction(function(opaqueId, bufPtr, len) {
                var innerSource = getOpaque(opaqueId);
                if (!innerSource) return -1;
                return bridgeRead(wasm, innerSource, bufPtr, len);
            }, 'iiii');
        }
    """
    ) as (dynamic, (Int) -> Any?, (dynamic, Any, Int, Int) -> Int) -> Int
    val readPtr = readFuncCreator(wasm, ::getOpaque, ::bridgeRead)

    val seekFuncCreator = js(
        """
        function(wasm, getOpaque, bridgeSeek) {
            return wasm.addFunction(function(opaqueId, pos) {
                var innerSource = getOpaque(opaqueId);
                if (innerSource) {
                    bridgeSeek(innerSource, Number(pos));
                }
            }, 'vij');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any, Double) -> Unit) -> Int
    val seekPtr = seekFuncCreator(wasm, ::getOpaque, ::bridgeSeek)

    val posFuncCreator = js(
        """
        function(wasm, getOpaque, bridgePosition) {
            return wasm.addFunction(function(opaqueId) {
                var innerSource = getOpaque(opaqueId);
                var bigInt = globalThis["BigInt"];
                return innerSource ? bigInt(bridgePosition(innerSource)) : bigInt(0);
            }, 'ji');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any) -> Double) -> Int
    val posPtr = posFuncCreator(wasm, ::getOpaque, ::bridgePosition)

    val lenFuncCreator = js(
        """
        function(wasm, getOpaque, bridgeLength) {
            return wasm.addFunction(function(opaqueId) {
                var innerSource = getOpaque(opaqueId);
                var bigInt = globalThis["BigInt"];
                return innerSource ? bigInt(bridgeLength(innerSource)) : bigInt(0);
            }, 'ji');
        }
    """
    ) as (dynamic, (Int) -> Any?, (Any) -> Double) -> Int
    val lenPtr = lenFuncCreator(wasm, ::getOpaque, ::bridgeLength)

    return SourceCallbackPtrs(readPtr, seekPtr, posPtr, lenPtr)
}

private fun cleanupSourcePtrs(
    wasm: dynamic,
    sourcePtr: Int,
    ptrs: SourceCallbackPtrs?,
    opaqueId: Int
) {
    if (sourcePtr != 0) wasm._free(sourcePtr)
    if (ptrs != null) {
        if (ptrs.readPtr != 0) wasm.removeFunction(ptrs.readPtr)
        if (ptrs.seekPtr != 0) wasm.removeFunction(ptrs.seekPtr)
        if (ptrs.posPtr != 0) wasm.removeFunction(ptrs.posPtr)
        if (ptrs.lenPtr != 0) wasm.removeFunction(ptrs.lenPtr)
    }
    releaseOpaque(opaqueId)
}

private fun isNodeJs(): Boolean = js(
    "typeof process !== 'undefined' && process.versions != null && process.versions.node != null"
) as Boolean

public class NodeFileSeekableSource(private val pathStr: String) : SeekableSource {
    private val fs: dynamic = js("eval('require')('fs')")
    private val fd: Int
    private var pos: Long = 0L
    private val totalLength: Long

    init {
        fd = fs.openSync(pathStr, "r") as Int
        val stats = fs.fstatSync(fd)
        totalLength = (stats.size as Double).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val result: Int
        if (pos >= totalLength) {
            result = -1
        } else {
            val bytesToRead = minOf(length.toLong(), totalLength - pos).toInt()
            if (bytesToRead <= 0) {
                result = 0
            } else {
                val isBufferAllocSupported = js(
                    "typeof Buffer.alloc === 'function'"
                ) as Boolean
                val jsBuffer = if (isBufferAllocSupported) {
                    js("Buffer.alloc(bytesToRead)")
                } else {
                    js("new Buffer(bytesToRead)")
                }
                val readBytes = fs.readSync(
                    fd,
                    jsBuffer,
                    0,
                    bytesToRead,
                    pos.toDouble()
                ) as Int

                if (readBytes <= 0) {
                    result = -1
                } else {
                    val kotlinArray = buffer.asDynamic() as Int8Array
                    val view = Int8Array(
                        jsBuffer.buffer as ArrayBuffer,
                        jsBuffer.byteOffset as Int,
                        readBytes
                    )
                    kotlinArray.set(view, offset)
                    pos += readBytes
                    result = readBytes
                }
            }
        }
        return result
    }

    override fun seek(position: Long) {
        pos = maxOf(0L, minOf(position, totalLength))
    }

    override fun position(): Long = pos

    override fun length(): Long = totalLength

    override fun close() {
        fs.closeSync(fd)
    }
}

private class JsArchiveReader(
    private val wasm: dynamic,
    private val sourceOpaqueId: Int,
    private val sourcePtr: Int,
    private val readFuncPtr: Int,
    private val seekFuncPtr: Int,
    private val posFuncPtr: Int,
    private val lenFuncPtr: Int,
    private val handle: Long
) : ArchiveReader {

    private var isClosed = false

    override fun getEntries(): List<ArchiveEntry> {
        if (isClosed) throw IllegalStateException("Reader is closed")

        // Emscripten receives Long as BigInt
        val rawHandle = (
            js(
            "function(h) { return globalThis['BigInt'](h.toString()); }"
        ) as (dynamic) -> dynamic
        )(handle)
        val count = wasm._kio_get_entry_count(rawHandle) as Int
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        // Allocate kio_entry_t (24 bytes)
        val entryPtr = wasm._malloc(ENTRY_STRUCT_SIZE) as Int
        try {
            for (i in 0 until count) {
                if (wasm._kio_get_entry(rawHandle, i, entryPtr) as Int != 0) {
                    val heap32 = wasm.HEAP32
                    val wordOffset = entryPtr / 4
                    val index = heap32[wordOffset + 0] as Int
                    val namePtr = heap32[wordOffset + 1] as Int

                    // Read string from memory using Emscripten helper
                    val nameStr = wasmUtf8ToString(wasm, namePtr)
                    val normalizedName = nameStr.replace('\\', '/')

                    // Size is int64, read from HEAP64/HEAPU32
                    // Emscripten handles 64-bit alignment, so offset of size is 8 bytes (word offset 2 and 3)
                    val sizeVal = (
                        js(
                        "function(heap, idx) { return Number(heap[idx]); }"
                    ) as (dynamic, Int) -> Double
                    )(wasm.HEAP64, (entryPtr + ENTRY_SIZE_OFFSET) / ENTRY_SIZE_OFFSET)
                    val size = sizeVal.toLong()

                    val isDir = heap32[wordOffset + 4] as Int != 0
                    val crc = heap32[wordOffset + 5] as Int

                    list.add(
                        ArchiveEntry(
                            index = index,
                            name = normalizedName,
                            size = size,
                            compressedSize = size,
                            isDirectory = isDir,
                            crc = crc.toLong() and 0xFFFFFFFFL
                        )
                    )
                }
            }
        } finally {
            wasm._free(entryPtr)
        }
        return list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        if (isClosed) throw IllegalStateException("Reader is closed")

        val sinkOpaqueId = registerOpaque(sink)
        var sinkPtr = 0
        var writeFuncPtr = 0

        try {
            val writeFuncCreator = js(
                """
                function(wasm, getOpaque, bridgeWrite) {
                    return wasm.addFunction(function(opaqueId, bufPtr, len) {
                        var innerSink = getOpaque(opaqueId);
                        if (innerSink) {
                            bridgeWrite(wasm, innerSink, bufPtr, len);
                        }
                    }, 'viii');
                }
            """
            ) as (dynamic, (Int) -> Any?, (dynamic, Any, Int, Int) -> Unit) -> Int
            writeFuncPtr = writeFuncCreator(wasm, ::getOpaque, ::bridgeWrite)

            // Allocate kio_sink_t (8 bytes)
            sinkPtr = wasm._malloc(SINK_STRUCT_SIZE) as Int
            val heap32 = wasm.HEAP32
            val wordOffset = sinkPtr / 4
            heap32[wordOffset + 0] = writeFuncPtr
            heap32[wordOffset + 1] = sinkOpaqueId

            val rawHandle = (
                js(
                "function(h) { return globalThis['BigInt'](h.toString()); }"
            ) as (dynamic) -> dynamic
            )(handle)
            val errMsgPtr = wasm._malloc(ERR_MSG_BUFFER_SIZE) as Int
            try {
                val success = wasm._kio_extract_entry(
                    rawHandle,
                    entry.index,
                    sinkPtr,
                    errMsgPtr,
                    ERR_MSG_BUFFER_SIZE
                ) as Int

                if (success == 0) {
                    val errMsg = wasmUtf8ToString(wasm, errMsgPtr)
                    throw ArchiveIOException(
                        "Failed to extract entry: ${entry.name}. Native error: $errMsg"
                    )
                }
            } finally {
                wasm._free(errMsgPtr)
            }
        } finally {
            if (sinkPtr != 0) wasm._free(sinkPtr)
            if (writeFuncPtr != 0) wasm.removeFunction(writeFuncPtr)
            releaseOpaque(sinkOpaqueId)
        }
    }

    override fun close() {
        if (!isClosed) {
            val rawHandle = (
                js(
                "function(h) { return globalThis['BigInt'](h.toString()); }"
            ) as (dynamic) -> dynamic
            )(handle)
            wasm._kio_close_archive(rawHandle)
            wasm._free(sourcePtr)
            wasm.removeFunction(readFuncPtr)
            wasm.removeFunction(seekFuncPtr)
            wasm.removeFunction(posFuncPtr)
            wasm.removeFunction(lenFuncPtr)
            releaseOpaque(sourceOpaqueId)
            isClosed = true
        }
    }
}
