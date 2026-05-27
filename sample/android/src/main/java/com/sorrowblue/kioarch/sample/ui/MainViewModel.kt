package com.sorrowblue.kioarch.sample.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.sample.io.UriSeekableSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.asSink

/**
 * UI State representing the state of the main archive processing dashboard.
 */
sealed interface MainUiState {
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
 * ViewModel that coordinates non-blocking archive analysis and extraction operations
 * using Coroutines and [KioArch] on the Android platform.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount")
class MainViewModel(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Idle)

    /**
     * Exposes the read-only [StateFlow] representing the current UI state of the app.
     */
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var currentSourceUri: Uri? = null
    private var currentFileName: String = ""

    /**
     * Asynchronously loads and parses the archive from the given [uri].
     *
     * @param context The Android context used to open the URI.
     * @param uri The document URI of the 7z or Zip archive.
     */
    fun loadArchive(context: Context, uri: Uri) {
        currentSourceUri = uri
        currentFileName = getFileName(context, uri) ?: "Archive"
        _uiState.value = MainUiState.Loading

        viewModelScope.launch(ioDispatcher) {
            try {
                UriSeekableSource(context, uri).use { source ->
                    KioArch.createReader(source).use { reader ->
                        val entries = reader.getEntries()
                        _uiState.value = MainUiState.Loaded(currentFileName, entries)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading archive", e)
                _uiState.value = MainUiState.Error(
                    "Failed to parse archive: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Asynchronously extracts all entries of the currently loaded archive into the destination folder.
     *
     * @param context The Android context used for content resolution.
     * @param destinationFolderUri The tree URI of the directory chosen by the user.
     */
    fun extractArchive(context: Context, destinationFolderUri: Uri) {
        val sourceUri = currentSourceUri ?: return
        val originalState = _uiState.value
        if (originalState !is MainUiState.Loaded) return

        _uiState.value = MainUiState.Extracting(currentFileName)

        viewModelScope.launch(ioDispatcher) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, destinationFolderUri)
                    ?: throw IllegalArgumentException("Invalid destination folder URI")

                UriSeekableSource(context, sourceUri).use { source ->
                    KioArch.createReader(source).use { reader ->
                        val entries = reader.getEntries()
                        entries.forEach { entry ->
                            if (entry.isDirectory) {
                                createDirectoryRecursively(rootDoc, entry.name)
                            } else {
                                val buffer = Buffer()
                                reader.extractEntry(entry, buffer)

                                val parentDir = getOrCreateParentDirectory(rootDoc, entry.name)
                                val baseName = getBaseName(entry.name)

                                val newFile = parentDir?.createFile("*/*", baseName)
                                newFile?.uri?.let { fileUri ->
                                    val resolver = context.contentResolver
                                    resolver.openOutputStream(fileUri)?.use { output ->
                                        output.asSink().use { sink ->
                                            sink.write(buffer, buffer.size)
                                            sink.flush()
                                        }
                                    }
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
                Log.e("MainViewModel", "Error extracting archive", e)
                _uiState.value = MainUiState.Error(
                    "Extraction failed: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Resets the UI State to Idle.
     */
    fun resetState() {
        _uiState.value = MainUiState.Idle
        currentSourceUri = null
        currentFileName = ""
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        val document = DocumentFile.fromSingleUri(context, uri)
        return document?.name
    }

    private fun createDirectoryRecursively(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        val parts = path.split("/").filter { it.isNotEmpty() }
        for (part in parts) {
            val next = current.findFile(part)
            current = if (next != null && next.isDirectory) {
                next
            } else {
                current.createDirectory(part) ?: return null
            }
        }
        return current
    }

    private fun getOrCreateParentDirectory(root: DocumentFile, path: String): DocumentFile? {
        val parts = path.split("/").filter { it.isNotEmpty() }
        if (parts.size <= 1) return root

        var current = root
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            val next = current.findFile(part)
            current = if (next != null && next.isDirectory) {
                next
            } else {
                current.createDirectory(part) ?: return null
            }
        }
        return current
    }

    private fun getBaseName(path: String): String = path.split("/").lastOrNull() ?: path
}
