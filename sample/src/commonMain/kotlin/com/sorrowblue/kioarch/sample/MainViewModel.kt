package com.sorrowblue.kioarch.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.sample.io.createDirectories2
import com.sorrowblue.kioarch.sample.io.createFile
import com.sorrowblue.kioarch.sample.io.createSeekableSource
import com.sorrowblue.kioarch.sample.io.div2
import com.sorrowblue.kioarch.sample.io.sink2
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.isDirectory
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * UI State representing the state of the main archive processing dashboard.
 */
internal sealed interface MainUiState {
    /** Idle state when no archive is selected yet. */
    data object Idle : MainUiState

    /** Loading state when the archive is being parsed and scanned. */
    data object Loading : MainUiState

    /** State representing that the archive has been successfully parsed and entries are ready. */
    data class Loaded(val fileName: String, val entries: List<ArchiveEntry>) : MainUiState

    /** Extracting state showing that entries are being saved into the destination. */
    data class Extracting(val fileName: String) : MainUiState

    /** Success state indicating decompression completed successfully. */
    data class Success(val fileName: String, val message: String) : MainUiState

    /** Error state representing failure during loading or extraction. */
    data class Error(val message: String) : MainUiState
}

/**
 * UI State representing the preview of a single entry in the archive.
 */
internal sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Text(val content: String) : PreviewState
    data class Image(val bytes: ByteArray) : PreviewState
    data class Unsupported(val extension: String) : PreviewState
    data class Error(val message: String) : PreviewState
}

/**
 * ViewModel that coordinates non-blocking archive analysis and extraction operations
 * using Coroutines and [KioArch] on the Android platform.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
internal class MainViewModel(private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default) :
    ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)

    /**
     * Exposes the read-only [StateFlow] representing the current UI state of the app.
     */
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    /**
     * Asynchronously extracts and previews the contents of a specific [ArchiveEntry].
     */
    fun previewEntry(context: PlatformContext, entry: ArchiveEntry) {
        if (entry.isDirectory) return

        val ext = entry.name.substringAfterLast('.', "").lowercase()
        val isImage = ext in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        val isText =
            ext in
                listOf("txt", "log", "md", "json", "xml", "html", "css", "js", "kt", "properties", "gradle", "kts", "toml")

        if (!isImage && !isText) {
            _previewState.value = PreviewState.Unsupported(ext)
            return
        }

        _previewState.value = PreviewState.Loading

        viewModelScope.launch(ioDispatcher) {
            try {
                val file = currentPlatformFile ?: throw IllegalStateException(
                    "No archive file loaded"
                )
                createSeekableSource(context, file).use { source ->
                    KioArch.createReader(source).use { reader ->
                        val buffer = Buffer()
                        val target = reader.getEntries().find { it.index == entry.index }
                            ?: throw IllegalArgumentException("Entry not found in archive")
                        reader.extractEntry(target, buffer)
                        val bytes = buffer.readByteArray()

                        if (isImage) {
                            _previewState.value = PreviewState.Image(bytes)
                        } else {
                            _previewState.value = PreviewState.Text(bytes.decodeToString())
                        }
                    }
                }
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Failed to load preview")
            }
        }
    }

    /**
     * Closes the preview dialog and resets the preview state.
     */
    fun dismissPreview() {
        _previewState.value = PreviewState.Idle
    }

    private var currentPlatformFile: PlatformFile? = null
    private var currentFileName: String = ""

    /**
     * Asynchronously loads and parses the archive from the given [uri].
     *
     * @param context The Android context used to open the URI.
     * @param uri The document URI of the 7z or Zip archive.
     */
    fun loadArchive(context: PlatformContext, file: PlatformFile) {
        currentPlatformFile = file
        currentFileName = file.name
        _uiState.value = MainUiState.Loading

        viewModelScope.launch(ioDispatcher) {
            try {
                createSeekableSource(context, file).use { source ->
                    KioArch.createReader(source).use { reader ->
                        val entries = reader.getEntries()
                        _uiState.value = MainUiState.Loaded(currentFileName, entries)
                    }
                }
            } catch (e: Exception) {
                println("MainViewModel: Error loading archive. ${e.message}")
                _uiState.value = MainUiState.Error("Failed to parse archive: ${e.message}")
            }
        }
    }

    /**
     * Asynchronously extracts all entries of the currently loaded archive into the destination folder.
     *
     * @param context The Android context used for content resolution.
     * @param destinationFolderUri The tree URI of the directory chosen by the user.
     */
    fun extractArchive(context: PlatformContext, destinationFolder: PlatformFile) {
        val sourceUri = currentPlatformFile ?: return
        val originalState = _uiState.value
        if (originalState !is MainUiState.Loaded) return

        _uiState.value = MainUiState.Extracting(currentFileName)

        viewModelScope.launch(ioDispatcher) {
            try {
                createSeekableSource(context, sourceUri).use { source ->
                    KioArch.createReader(source).use { reader ->
                        val entries = reader.getEntries()
                        entries.forEach { entry ->
                            if (entry.isDirectory) {
                                createDirectoryRecursively(destinationFolder, entry.name)
                            } else {
                                val buffer = Buffer()
                                reader.extractEntry(entry, buffer)

                                val parentDir = getOrCreateParentDirectory(
                                    destinationFolder,
                                    entry.name
                                )
                                val baseName = getBaseName(entry.name)

                                val newFile = parentDir?.createFile(context, baseName)
                                newFile?.sink2()?.use { sink ->
                                    sink.write(buffer, buffer.size)
                                    sink.flush()
                                }
                            }
                        }
                    }
                }
                _uiState.value = MainUiState.Success(
                    currentFileName,
                    "Successfully extracted all files!"
                )
            } catch (e: Exception) {
                println("MainViewModel: Error extracting archive. ${e.message}")
                _uiState.value = MainUiState.Error("Extraction failed: ${e.message}")
            }
        }
    }

    /**
     * Resets the UI State to Idle.
     */
    fun resetState() {
        _uiState.value = MainUiState.Idle
        currentPlatformFile = null
        currentFileName = ""
    }

    private fun createDirectoryRecursively(root: PlatformFile, path: String): PlatformFile {
        var current = root
        val parts = path.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val next = current.list().find { it.name == part }
            current = if (next?.isDirectory() == true) {
                next
            } else {
                current.div2(part).also {
                    it.createDirectories2()
                }
            }
        }
        return current
    }

    private fun getOrCreateParentDirectory(root: PlatformFile, path: String): PlatformFile? {
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.size <= 1) return root

        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val next = current.list().find { it.name == part }
            current = if (next?.isDirectory() == true) {
                next
            } else {
                current.div2(part).also {
                    it.createDirectories2()
                }
            }
        }
        return current
    }

    private fun getBaseName(path: String): String = path.split("/").lastOrNull() ?: path
}
