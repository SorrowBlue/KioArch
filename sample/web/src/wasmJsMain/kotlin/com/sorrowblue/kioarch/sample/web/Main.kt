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

@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.sorrowblue.kioarch.sample.web

import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.ArchiveReader
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.extractToByteArray
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.FileReader
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import kotlin.js.Promise
import kotlin.math.pow
import org.w3c.xhr.ProgressEvent

// External Emscripten creator function
@JsFun("() => createKioArchModule()")
private external fun createKioArchModuleJs(): Promise<JsAny>

@JsFun("(array, index) => array[index]")
private external fun getInt8ArrayValue(array: Int8Array, index: Int): Byte

@JsFun(
    """(getByteFn, len, mimeType) => {
        var array = new Uint8Array(len);
        for (var i = 0; i < len; i++) {
            array[i] = getByteFn(i);
        }
        var blob = new Blob([array], { type: mimeType });
        return URL.createObjectURL(blob);
    }"""
)
private external fun createBlobUrlJs(getByteFn: (Int) -> Byte, len: Int, mimeType: String): String

@JsFun("(url) => URL.revokeObjectURL(url)")
private external fun revokeBlobUrlJs(url: String)

private var activeReader: ArchiveReader? = null
private var activeEntries: List<ArchiveEntry> = emptyList()
private var activeBlobUrl: String? = null

/**
 * Entry point for the Kotlin/WasmJS Sample Web Application.
 */
public fun main() {
    println("[KioArch Demo] main() started. Loading WebAssembly module...")
    // Initialize KioArch using Emscripten Wasm module promise
    createKioArchModuleJs().then { module ->
        println("[KioArch Demo] WebAssembly module loaded! Initializing KioArch...")
        KioArch.initialize(module)
        println("[KioArch Demo] KioArch initialized successfully. Launching initApp()...")
        initApp()
        module
    }
}

/**
 * Sets up the drag & drop listeners and element interactions.
 */
private fun initApp() {
    println("[KioArch Demo] initApp() started. Registering Event Listeners...")
    val dropZone = document.getElementById("drop-zone") as HTMLDivElement
    val fileInput = document.getElementById("file-input") as HTMLInputElement

    dropZone.addEventListener("click", { event ->
        println("[KioArch Demo] click event detected on dropZone. Triggering fileInput...")
        fileInput.click()
    })

    fileInput.addEventListener("change", { event ->
        val files = fileInput.files
        println("[KioArch Demo] change event detected on fileInput. Files count: ${files?.length}")
        if (files != null && files.length > 0) {
            val file = files.item(0)
            if (file != null) {
                processFile(file)
            }
        }
    })

    dropZone.addEventListener("dragover", { event ->
        event.preventDefault()
        dropZone.classList.add("dragover")
    })

    dropZone.addEventListener("dragleave", { event ->
        dropZone.classList.remove("dragover")
    })

    dropZone.addEventListener("drop", { event ->
        event.preventDefault()
        dropZone.classList.remove("dragover")
        println("[KioArch Demo] drop event detected on dropZone.")
        val dragEvent = event as DragEvent
        val files = dragEvent.dataTransfer?.files
        println("[KioArch Demo] Dropped files count: ${files?.length}")
        if (files != null && files.length > 0) {
            val file = files.item(0)
            if (file != null) {
                processFile(file)
            }
        }
    })
    println("[KioArch Demo] initApp() completed. All event listeners registered.")
}

/**
 * Reads the dropped file as ArrayBuffer and parses the archive structure inside Wasm.
 */
private fun processFile(file: File) {
    println("[KioArch Demo] processFile() started for file: ${file.name} (${file.size} bytes)")
    val reader = FileReader()
    val progressContainer = document.getElementById("progress-container") as HTMLDivElement
    val progressPercent = document.getElementById("progress-percent")
    val progressBar = document.getElementById("progress-bar") as HTMLDivElement
    val progressText = document.getElementById("progress-text")
    val resultsContainer = document.getElementById("results-container") as HTMLDivElement
    val dropZone = document.getElementById("drop-zone") as HTMLDivElement

    progressContainer.style.display = "block"
    resultsContainer.style.display = "none"
    dropZone.style.display = "none"

    if (progressText != null) progressText.textContent = "ファイルを読み込み中..."
    if (progressPercent != null) progressPercent.textContent = "0%"
    if (progressBar != null) progressBar.style.width = "0%"

    reader.onload = { event ->
        println("[KioArch Demo] FileReader onload triggered. Converting ArrayBuffer...")
        val result = reader.result as ArrayBuffer
        if (result != null) {
            if (progressText != null) progressText.textContent = "Wasm でアーカイブを解析中..."
            window.setTimeout({
                try {
                    println("[KioArch Demo] parsing archive bytes inside Wasm...")
                    val bytes = arrayBufferToByteArray(result)
                    
                    // Close previous reader if any
                    activeReader?.close()
                    
                    val archiveReader = KioArch.createReader(bytes)
                    activeReader = archiveReader
                    
                    val entries = archiveReader.getEntries()
                    activeEntries = entries
                    
                    println("[KioArch Demo] Archive loaded successfully! Entries found: ${entries.size}")
                    displayFileList(entries)
                    
                    if (progressContainer != null) progressContainer.style.display = "none"
                    if (resultsContainer != null) resultsContainer.style.display = "grid"
                } catch (e: Exception) {
                    if (progressText != null) {
                        progressText.textContent = "エラーが発生しました: ${e.message}"
                        progressText.setAttribute("style", "color: #f87171;") // Red text on error
                    }
                    if (dropZone != null) dropZone.style.display = "block"
                    println("[KioArch Demo] ERROR parsing archive: " + e.message)
                }
                document
            }, 100)
        }
        Unit
    }

    reader.onprogress = { event: ProgressEvent ->
        if (event.lengthComputable) {
            val percent = ((event.loaded.toDouble() / event.total.toDouble()) * 100).toInt()
            if (progressPercent != null) progressPercent.textContent = "$percent%"
            if (progressBar != null) progressBar.style.width = "$percent%"
        }
    }

    reader.readAsArrayBuffer(file)
}

/**
 * Converts JS ArrayBuffer to Kotlin ByteArray.
 */
private fun arrayBufferToByteArray(buffer: ArrayBuffer): ByteArray {
    val int8Array = Int8Array(buffer)
    val size = int8Array.length
    val bytes = ByteArray(size)
    for (i in 0 until size) {
        bytes[i] = getInt8ArrayValue(int8Array, i)
    }
    return bytes
}

/**
 * Renders the parsed file list inside the HTML panel.
 */
private fun displayFileList(entries: List<ArchiveEntry>) {
    val fileList = document.getElementById("file-list") ?: return
    fileList.textContent = "" // Clear existing items

    entries.forEach { entry ->
        val fileItem = document.createElement("div")
        fileItem.className = "file-item"
        fileItem.setAttribute("id", "entry-${entry.index}")

        val nameSpan = document.createElement("span")
        nameSpan.className = "file-name"
        nameSpan.textContent = entry.name

        val sizeSpan = document.createElement("span")
        sizeSpan.className = "file-size"
        sizeSpan.textContent = formatBytes(entry.size)

        fileItem.appendChild(nameSpan)
        fileItem.appendChild(sizeSpan)

        if (entry.isDirectory) {
            fileItem.setAttribute("style", "opacity: 0.6; cursor: default;")
        } else {
            fileItem.addEventListener("click", { event ->
                selectFile(entry, fileItem)
            })
        }

        fileList.appendChild(fileItem)
    }
}

/**
 * Extracts and displays the selected file contents in the preview panel.
 */
private fun getMimeType(filename: String): String {
    val lower = filename.lowercase()
    return when {
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".bmp") -> "image/bmp"
        else -> "application/octet-stream"
    }
}

private fun isImageFile(filename: String): Boolean {
    val lower = filename.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
           lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") || lower.endsWith(".bmp")
}

private fun selectFile(entry: ArchiveEntry, element: org.w3c.dom.Element) {
    // Clear previous selection style
    val selectedItems = document.querySelectorAll(".file-item.selected")
    for (i in 0 until selectedItems.length) {
        val item = selectedItems.item(i) as org.w3c.dom.HTMLElement
        item.classList.remove("selected")
    }
    (element as org.w3c.dom.HTMLElement).classList.add("selected")

    val previewFilename = document.getElementById("preview-filename")
    val previewSize = document.getElementById("preview-size")
    val previewBody = document.getElementById("preview-body") ?: return

    if (previewFilename != null) previewFilename.textContent = entry.name
    if (previewSize != null) previewSize.textContent = formatBytes(entry.size)

    previewBody.textContent = "展開中..."

    // Revoke previous blob URL to avoid memory leaks
    activeBlobUrl?.let { url ->
        revokeBlobUrlJs(url)
        activeBlobUrl = null
    }

    // Run extraction inside timeout to let UI update instantly
    window.setTimeout({
        val reader = activeReader
        if (reader != null) {
            try {
                val extractedBytes = entry.extractToByteArray(reader)
                
                if (isImageFile(entry.name)) {
                    val mime = getMimeType(entry.name)
                    val url = createBlobUrlJs(getByteFn = { i -> extractedBytes[i] }, extractedBytes.size, mime)
                    activeBlobUrl = url
                    
                    val img = document.createElement("img")
                    img.setAttribute("src", url)
                    img.setAttribute(
                        "style", 
                        "max-width: 100%; max-height: 100%; object-fit: contain; border-radius: 4px; border: 1px solid #e2e8f0;"
                    )
                    
                    previewBody.textContent = ""
                    // Center the image container
                    previewBody.setAttribute("style", "display: flex; justify-content: center; align-items: center; height: 100%; overflow: hidden;")
                    previewBody.appendChild(img)
                } else {
                    // Simple heuristical binary check
                    var isBinary = false
                    for (b in extractedBytes) {
                        if (b == 0.toByte()) {
                            isBinary = true
                            break
                        }
                    }
                    
                    // Reset styling if it was previously set for an image
                    previewBody.removeAttribute("style")
                    
                    if (isBinary) {
                        previewBody.textContent = "[バイナリファイルのためプレビューできません]\nサイズ: ${entry.size} バイト\nCRC: 0x${entry.crc.toString(16).uppercase()}"
                    } else {
                        val text = extractedBytes.decodeToString()
                        previewBody.textContent = text
                    }
                }
            } catch (e: Exception) {
                previewBody.removeAttribute("style")
                previewBody.textContent = "展開エラー: ${e.message}"
            }
        }
        document
    }, 50)
}

/**
 * Standard utility to format raw byte count into readable sizes.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes == 0L) return "0 Bytes"
    val k = 1024.0
    val sizes = arrayOf("Bytes", "KB", "MB", "GB")
    val i = kotlin.math.floor(kotlin.math.log(bytes.toDouble(), k)).toInt()
    val num = bytes.toDouble() / k.pow(i.toDouble())
    val formattedNum = ((num * 100).toInt() / 100.0).toString()
    return "$formattedNum ${sizes[i]}"
}

