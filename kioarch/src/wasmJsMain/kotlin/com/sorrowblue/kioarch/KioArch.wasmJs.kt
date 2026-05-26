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

import kotlinx.io.files.Path
import kotlinx.io.Sink

// Global reference to track active Wasm module instance
private var wasmModule: JsAny? = null

// --- JS Interop Helper Functions using @JsFun ---

@JsFun("() => { if (!globalThis.kioarchSources) { globalThis.kioarchSources = new Map(); } }")
private external fun initOpaqueMap()

@JsFun(
    """(id, readFn, seekFn, posFn, lenFn) => {
        globalThis.kioarchSources.set(id, {
            read: readFn,
            seek: seekFn,
            position: posFn,
            length: lenFn
        });
    }"""
)
private external fun putSourceJs(
    id: Int,
    readFn: (Int, Int) -> Int,
    seekFn: (Double) -> Unit,
    posFn: () -> Double,
    lenFn: () -> Double
)

@JsFun("(id) => { globalThis.kioarchSources.delete(id); }")
private external fun removeSourceJs(id: Int)

@JsFun("() => { if (!globalThis.kioarchSinks) { globalThis.kioarchSinks = new Map(); } }")
private external fun initSinkMap()

@JsFun("(id, writeFn) => { globalThis.kioarchSinks.set(id, { write: writeFn }); }")
private external fun putSinkJs(id: Int, writeFn: (Int, Int) -> Unit)

@JsFun("(id) => { globalThis.kioarchSinks.delete(id); }")
private external fun removeSinkJs(id: Int)

// --- Emscripten dynamic callbacks function pointer registration ---

@JsFun(
    """(module) => module.addFunction(function(opaqueId, bufPtr, len) {
        var source = globalThis.kioarchSources.get(opaqueId);
        if (!source) return -1;
        return source.read(bufPtr, len);
    }, 'iiii')"""
)
private external fun registerReadCallback(module: JsAny): Int

@JsFun(
    """(module) => module.addFunction(function(opaqueId, pos) {
        var source = globalThis.kioarchSources.get(opaqueId);
        if (source) {
            source.seek(Number(pos));
        }
    }, 'vij')"""
)
private external fun registerSeekCallback(module: JsAny): Int

@JsFun(
    """(module) => module.addFunction(function(opaqueId) {
        var source = globalThis.kioarchSources.get(opaqueId);
        return source ? BigInt(source.position()) : 0n;
    }, 'ji')"""
)
private external fun registerPosCallback(module: JsAny): Int

@JsFun(
    """(module) => module.addFunction(function(opaqueId) {
        var source = globalThis.kioarchSources.get(opaqueId);
        return source ? BigInt(source.length()) : 0n;
    }, 'ji')"""
)
private external fun registerLenCallback(module: JsAny): Int

@JsFun(
    """(module) => module.addFunction(function(opaqueId, bufPtr, len) {
        var sink = globalThis.kioarchSinks.get(opaqueId);
        if (sink) {
            sink.write(bufPtr, len);
        }
    }, 'viii')"""
)
private external fun registerWriteCallback(module: JsAny): Int

// --- Emscripten directly bound C functions ---

@JsFun("(module, size) => module._malloc(size)")
private external fun wasmMalloc(module: JsAny, size: Int): Int

@JsFun("(module, ptr) => module._free(ptr)")
private external fun wasmFree(module: JsAny, ptr: Int)

@JsFun("(module, ptr, offset, value) => { module.HEAP32[(ptr / 4) + offset] = value; }")
private external fun writeHeap32(module: JsAny, ptr: Int, offset: Int, value: Int)

@JsFun("(module, ptr, offset) => module.HEAP32[(ptr / 4) + offset]")
private external fun readHeap32(module: JsAny, ptr: Int, offset: Int): Int

@JsFun("(module, ptr, offset) => Number(module.HEAP64[((ptr + (offset * 8)) / 8)])")
private external fun readHeap64Double(module: JsAny, ptr: Int, offset: Int): Double

@JsFun(
    """(module, namePtr) => {
        var len = 0;
        while (module.HEAPU8[namePtr + len] !== 0) {
            len++;
        }
        if (len === 0) return "";
        var bytes = new Uint8Array(module.HEAPU8.buffer, namePtr, len);
        try {
            var utf8Decoder = new TextDecoder('utf-8', { fatal: true });
            return utf8Decoder.decode(bytes);
        } catch (e) {
            try {
                var sjisDecoder = new TextDecoder('shift-jis');
                return sjisDecoder.decode(bytes);
            } catch (err) {
                return module.UTF8ToString(namePtr);
            }
        }
    }"""
)
private external fun wasmUtf8ToString(module: JsAny, namePtr: Int): String

@JsFun("(module, sourcePtr, errMsgPtr, errMsgLen) => module._kio_open_archive_wasm(sourcePtr, errMsgPtr, errMsgLen)")
private external fun wasmOpenArchive(module: JsAny, sourcePtr: Int, errMsgPtr: Int, errMsgLen: Int): JsAny

@JsFun("(module, handle) => module._kio_get_entry_count(handle)")
private external fun wasmGetEntryCount(module: JsAny, handle: JsAny): Int

@JsFun("(module, handle, index, entryPtr) => module._kio_get_entry(handle, index, entryPtr)")
private external fun wasmGetEntry(module: JsAny, handle: JsAny, index: Int, entryPtr: Int): Int

@JsFun("(module, handle, index, sinkPtr, errMsgPtr, errMsgLen) => module._kio_extract_entry_wasm(handle, index, sinkPtr, errMsgPtr, errMsgLen)")
private external fun wasmExtractEntry(module: JsAny, handle: JsAny, index: Int, sinkPtr: Int, errMsgPtr: Int, errMsgLen: Int): Int

@JsFun("(module, handle) => module._kio_close_archive(handle)")
private external fun wasmCloseArchive(module: JsAny, handle: JsAny)

@JsFun("(module, ptr) => module.removeFunction(ptr)")
private external fun wasmRemoveFunction(module: JsAny, ptr: Int)

// --- Dynamic Memory Copy Helpers ---

@JsFun(
    """(module, bufPtr, getByteFn, len) => {
        var heap = new Uint8Array(module.HEAPU8.buffer, bufPtr, len);
        for (var i = 0; i < len; i++) {
            heap[i] = getByteFn(i);
        }
    }"""
)
private external fun copyToHeapJs(module: JsAny, bufPtr: Int, getByteFn: (Int) -> Byte, len: Int)

private fun copyToHeap(module: JsAny, bufPtr: Int, byteArray: ByteArray, len: Int) {
    copyToHeapJs(module, bufPtr, getByteFn = { i -> byteArray[i] }, len)
}

@JsFun(
    """(module, bufPtr, setByteFn, len) => {
        var heap = new Uint8Array(module.HEAPU8.buffer, bufPtr, len);
        for (var i = 0; i < len; i++) {
            setByteFn(i, heap[i]);
        }
    }"""
)
private external fun copyFromHeapJs(module: JsAny, bufPtr: Int, setByteFn: (Int, Byte) -> Unit, len: Int)

private fun copyFromHeap(module: JsAny, bufPtr: Int, byteArray: ByteArray, len: Int) {
    copyFromHeapJs(module, bufPtr, setByteFn = { i, value -> byteArray[i] = value }, len)
}

@JsFun("(rawHandle) => BigInt(rawHandle)")
private external fun toBigInt(rawHandle: JsAny): JsAny

@JsFun("(bigIntVal) => bigIntVal === 0n")
private external fun isZeroBigInt(bigIntVal: JsAny): Boolean

@JsFun("() => typeof process !== 'undefined' && process.versions != null && process.versions.node != null")
private external fun isNodeJsWasm(): Boolean

@JsFun("() => { if (!globalThis.kioarchFs) { globalThis.kioarchFs = typeof require !== 'undefined' ? require('fs') : null; } }")
private external fun initNodeFs()

@JsFun("(pathStr) => { if (!globalThis.kioarchFs) return -1; return globalThis.kioarchFs.openSync(pathStr, 'r'); }")
private external fun nodeOpenSync(pathStr: String): Int

@JsFun("(fd) => { if (!globalThis.kioarchFs) return 0.0; return Number(globalThis.kioarchFs.fstatSync(fd).size); }")
private external fun nodeSizeSync(fd: Int): Double

@JsFun(
    """(fd, bufPtr, bytesToRead, pos, module, copyFn) => {
        if (!globalThis.kioarchFs) return -1;
        var jsBuffer = typeof Buffer !== 'undefined' && typeof Buffer.alloc === 'function' ? Buffer.alloc(bytesToRead) : new Uint8Array(bytesToRead);
        var readBytes = globalThis.kioarchFs.readSync(fd, jsBuffer, 0, bytesToRead, pos);
        if (readBytes <= 0) return readBytes;
        for (var i = 0; i < readBytes; i++) {
            copyFn(i, jsBuffer[i]);
        }
        return readBytes;
    }"""
)
private external fun nodeReadSync(fd: Int, bufPtr: Int, bytesToRead: Int, pos: Double, module: JsAny, copyFn: (Int, Byte) -> Unit): Int

@JsFun("(fd) => { if (globalThis.kioarchFs) globalThis.kioarchFs.closeSync(fd); }")
private external fun nodeCloseSync(fd: Int)

// Global map to track active Kotlin objects passed to C callbacks for Wasm
private val opaqueMap = mutableMapOf<Int, Any>()
private var nextOpaqueId = 1

private fun registerOpaque(obj: Any): Int {
    val id = nextOpaqueId++
    opaqueMap[id] = obj
    return id
}

private fun getOpaque(id: Int): Any? = opaqueMap[id]

private fun releaseOpaque(id: Int) {
    opaqueMap.remove(id)
}

/**
 * WasmJs implementation of [KioArch] utilizing WebAssembly compiled from pure C archive engines.
 */
public actual object KioArch {

    /**
     * Initializes the WebAssembly module wrapper generated by Emscripten.
     * This MUST be called and completed before using any archive operations.
     *
     * @param module the modularized Emscripten Module instance
     */
    public fun initialize(module: Any) {
        wasmModule = module.cast()
        initOpaqueMap()
        initSinkMap()
    }

    /**
     * Creates an [ArchiveReader] from a custom [SeekableSource] for WasmJs.
     *
     * @param source the seekable source containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(source: SeekableSource): ArchiveReader {
        val module = wasmModule ?: throw IllegalStateException("KioArch has not been initialized. Call KioArch.initialize(module) first.")
        
        val sourceOpaqueId = registerOpaque(source)
        var sourcePtr = 0
        var readFuncPtr = 0
        var seekFuncPtr = 0
        var posFuncPtr = 0
        var lenFuncPtr = 0
        var handle: JsAny? = null

        try {
            // Register Kotlin SeekableSource callbacks into JS global map
            putSourceJs(
                sourceOpaqueId,
                readFn = { bufPtr, len ->
                    val innerSource = getOpaque(sourceOpaqueId) as? SeekableSource
                    if (innerSource == null) {
                        -1
                    } else {
                        try {
                            val tmpArray = ByteArray(len)
                            val readBytes = innerSource.read(tmpArray, 0, len)
                            if (readBytes > 0) {
                                copyToHeap(module, bufPtr, tmpArray, readBytes)
                            }
                            readBytes
                        } catch (e: Throwable) {
                            -1
                        }
                    }
                },
                seekFn = { pos ->
                    try {
                        val innerSource = getOpaque(sourceOpaqueId) as? SeekableSource
                        innerSource?.seek(pos.toLong())
                    } catch (e: Throwable) {
                        // Safe guard to prevent throwing into C++ Wasm boundary
                    }
                },
                posFn = {
                    try {
                        val innerSource = getOpaque(sourceOpaqueId) as? SeekableSource
                        innerSource?.position()?.toDouble() ?: 0.0
                    } catch (e: Throwable) {
                        0.0
                    }
                },
                lenFn = {
                    try {
                        val innerSource = getOpaque(sourceOpaqueId) as? SeekableSource
                        innerSource?.length()?.toDouble() ?: 0.0
                    } catch (e: Throwable) {
                        0.0
                    }
                }
            )

            // Register C-compatible callback function pointers in Emscripten
            readFuncPtr = registerReadCallback(module)
            seekFuncPtr = registerSeekCallback(module)
            posFuncPtr = registerPosCallback(module)
            lenFuncPtr = registerLenCallback(module)

            // Allocate and populate kio_source_t structure (20 bytes)
            sourcePtr = wasmMalloc(module, 20)
            writeHeap32(module, sourcePtr, 0, readFuncPtr)
            writeHeap32(module, sourcePtr, 1, seekFuncPtr)
            writeHeap32(module, sourcePtr, 2, posFuncPtr)
            writeHeap32(module, sourcePtr, 3, lenFuncPtr)
            writeHeap32(module, sourcePtr, 4, sourceOpaqueId)

            // Allocate error message buffer (512 bytes)
            val errMsgPtr = wasmMalloc(module, 512)
            try {
                val rawHandle = wasmOpenArchive(module, sourcePtr, errMsgPtr, 512)
                val handleVal = toBigInt(rawHandle)
                
                if (isZeroBigInt(handleVal)) {
                    val errMsg = wasmUtf8ToString(module, errMsgPtr)
                    throw ArchiveIOException("Failed to open archive. Native error: $errMsg")
                }
                handle = handleVal
            } finally {
                wasmFree(module, errMsgPtr)
            }

            return WasmArchiveReader(
                module,
                sourceOpaqueId,
                sourcePtr,
                readFuncPtr,
                seekFuncPtr,
                posFuncPtr,
                lenFuncPtr,
                handle
            )
        } catch (e: Exception) {
            // Clean up resources on failure
            if (sourcePtr != 0) wasmFree(module, sourcePtr)
            if (readFuncPtr != 0) wasmRemoveFunction(module, readFuncPtr)
            if (seekFuncPtr != 0) wasmRemoveFunction(module, seekFuncPtr)
            if (posFuncPtr != 0) wasmRemoveFunction(module, posFuncPtr)
            if (lenFuncPtr != 0) wasmRemoveFunction(module, lenFuncPtr)
            removeSourceJs(sourceOpaqueId)
            releaseOpaque(sourceOpaqueId)
            throw e
        }
    }

    /**
     * Creates an [ArchiveReader] from an in-memory [ByteArray] for WasmJs.
     *
     * @param byteArray the byte array containing the archive data
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(byteArray: ByteArray): ArchiveReader {
        return createReader(ByteArraySeekableSource(byteArray))
    }

    /**
     * Creates an [ArchiveReader] from a Kotlin Multiplatform [Path] for WasmJs.
     *
     * @param path the path to the archive file
     * @return an [ArchiveReader] to read the archive
     * @throws ArchiveException if native library fails to open the archive
     */
    public actual fun createReader(path: Path): ArchiveReader {
        if (isNodeJsWasm()) {
            val module = wasmModule ?: throw IllegalStateException("KioArch has not been initialized. Call KioArch.initialize(module) first.")
            return createReader(NodeFileSeekableSource(path.toString(), module))
        }
        throw UnsupportedOperationException("File system access using Path is not supported in browser WasmJs environment. Use ByteArray or custom SeekableSource.")
    }
}

private class NodeFileSeekableSource(private val pathStr: String, private val module: JsAny) : SeekableSource {
    private val fd: Int
    private var pos: Long = 0L
    private val totalLength: Long

    init {
        initNodeFs()
        fd = nodeOpenSync(pathStr)
        if (fd < 0) throw ArchiveIOException("Failed to open file: $pathStr")
        totalLength = nodeSizeSync(fd).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (pos >= totalLength) return -1
        val bytesToRead = minOf(length.toLong(), totalLength - pos).toInt()
        if (bytesToRead <= 0) return 0

        val readBytes = nodeReadSync(
            fd,
            0,
            bytesToRead,
            pos.toDouble(),
            module,
            copyFn = { i, value ->
                buffer[offset + i] = value
            }
        )
        if (readBytes > 0) {
            pos += readBytes
        }
        return readBytes
    }

    override fun seek(position: Long) {
        pos = maxOf(0L, minOf(position, totalLength))
    }

    override fun position(): Long = pos

    override fun length(): Long = totalLength

    override fun close() {
        nodeCloseSync(fd)
    }
}

private class WasmArchiveReader(
    private val module: JsAny,
    private val sourceOpaqueId: Int,
    private val sourcePtr: Int,
    private val readFuncPtr: Int,
    private val seekFuncPtr: Int,
    private val posFuncPtr: Int,
    private val lenFuncPtr: Int,
    private val handle: JsAny
) : ArchiveReader {

    private var isClosed = false

    override fun getEntries(): List<ArchiveEntry> {
        if (isClosed) throw IllegalStateException("Reader is closed")
        
        val count = wasmGetEntryCount(module, handle)
        if (count < 0) return emptyList()

        val list = ArrayList<ArchiveEntry>(count)
        // Allocate kio_entry_t (24 bytes)
        val entryPtr = wasmMalloc(module, 24)
        try {
            for (i in 0 until count) {
                if (wasmGetEntry(module, handle, i, entryPtr) != 0) {
                    val index = readHeap32(module, entryPtr, 0)
                    val namePtr = readHeap32(module, entryPtr, 1)
                    
                    val nameStr = wasmUtf8ToString(module, namePtr)
                    val normalizedName = nameStr.replace('\\', '/')

                    // Size is int64, read using double/HEAP64 helper (offset of size is 8 bytes)
                    val size = readHeap64Double(module, entryPtr, 1).toLong()

                    val isDir = readHeap32(module, entryPtr, 4) != 0
                    val crc = readHeap32(module, entryPtr, 5)

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
            wasmFree(module, entryPtr)
        }
        return list
    }

    override fun extractEntry(entry: ArchiveEntry, sink: Sink) {
        if (isClosed) throw IllegalStateException("Reader is closed")
        
        val sinkOpaqueId = registerOpaque(sink)
        var sinkPtr = 0
        var writeFuncPtr = 0
        
        try {
            // Register Kotlin Sink callback into JS global map
            putSinkJs(
                sinkOpaqueId,
                writeFn = { bufPtr, len ->
                    val innerSink = getOpaque(sinkOpaqueId) as? Sink
                    if (innerSink != null) {
                        try {
                            val tmpArray = ByteArray(len)
                            copyFromHeap(module, bufPtr, tmpArray, len)
                            innerSink.write(tmpArray, 0, len)
                        } catch (e: Throwable) {
                            // Safe guard to prevent throwing into C++ Wasm boundary
                        }
                    }
                }
            )

            // Register C-compatible callback pointer in Emscripten
            writeFuncPtr = registerWriteCallback(module)

            // Allocate kio_sink_t (8 bytes)
            sinkPtr = wasmMalloc(module, 8)
            writeHeap32(module, sinkPtr, 0, writeFuncPtr)
            writeHeap32(module, sinkPtr, 1, sinkOpaqueId)

            val errMsgPtr = wasmMalloc(module, 512)
            try {
                val success = wasmExtractEntry(
                    module,
                    handle,
                    entry.index,
                    sinkPtr,
                    errMsgPtr,
                    512
                )

                if (success == 0) {
                    val errMsg = wasmUtf8ToString(module, errMsgPtr)
                    throw ArchiveIOException("Failed to extract entry: ${entry.name}. Native error: $errMsg")
                }
            } finally {
                wasmFree(module, errMsgPtr)
            }
        } finally {
            if (sinkPtr != 0) wasmFree(module, sinkPtr)
            if (writeFuncPtr != 0) wasmRemoveFunction(module, writeFuncPtr)
            removeSinkJs(sinkOpaqueId)
            releaseOpaque(sinkOpaqueId)
        }
    }

    override fun close() {
        if (!isClosed) {
            wasmCloseArchive(module, handle)
            wasmFree(module, sourcePtr)
            wasmRemoveFunction(module, readFuncPtr)
            wasmRemoveFunction(module, seekFuncPtr)
            wasmRemoveFunction(module, posFuncPtr)
            wasmRemoveFunction(module, lenFuncPtr)
            removeSourceJs(sourceOpaqueId)
            releaseOpaque(sourceOpaqueId)
            isClosed = true
        }
    }
}

// Inline helper extension to cast JS reference safely in Kotlin/Wasm
@Suppress("UNCHECKED_CAST_TO_EXTERNAL_INTERFACE", "UNCHECKED_CAST")
private fun <T : JsAny> Any.cast(): T = this as T
