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

@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.sorrowblue.kioarch.sample.jvm

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.ArchiveReader
import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.extractToByteArray
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import org.jetbrains.skia.Image as SkiaImage

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    secondary = Color(0xFFC2E7FF),
    background = Color(0xFF1F1F1F),
    surface = Color(0xFF2D2D2D),
    onBackground = Color(0xFFE3E3E3),
    onSurface = Color(0xFFE3E3E3)
)

private sealed interface PreviewData {
    data class TextPreview(val content: String) : PreviewData
    data class ImagePreview(val bytes: ByteArray) : PreviewData
    data class BinaryPreview(val size: Long, val crc: Long) : PreviewData
}

/**
 * Main entry point for the Compose Multiplatform Desktop application.
 */
public fun main(): Unit = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "KioArch JVM Sample Viewer"
    ) {
        MaterialTheme(colorScheme = DarkColorScheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppScreen()
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun AppScreen() {
    val coroutineScope = rememberCoroutineScope()
    var activeReader by remember { mutableStateOf<ArchiveReader?>(null) }
    var loadedFileName by remember { mutableStateOf("No archive loaded") }
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var selectedEntry by remember { mutableStateOf<ArchiveEntry?>(null) }
    var previewData by remember { mutableStateOf<PreviewData?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            activeReader?.close()
        }
    }

    val launcher = rememberFilePickerLauncher(
        type = FileKitType.File(
            extensions = listOf("zip", "7z", "tar.gz", "tgz")
        ),
        mode = FileKitMode.Single,
    ) { file ->
        if (file != null) {
            val pathStr = file.path
            if (pathStr != null) {
                loadedFileName = file.name
                isError = null
                selectedEntry = null
                previewData = null
                activeReader?.close()
                activeReader = null

                coroutineScope.launch {
                    try {
                        val reader = withContext(Dispatchers.IO) {
                            KioArch.createReader(Path(pathStr))
                        }
                        activeReader = reader
                        entries = withContext(Dispatchers.IO) {
                            reader.getEntries()
                        }
                    } catch (e: Exception) {
                        isError = "Failed to load archive: ${e.message}"
                        loadedFileName = "Error loading archive"
                        entries = emptyList()
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color(0xFF2C3E50), Color(0xFF3498DB))
                    )
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { launcher.launch() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2980B9),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = "Open")
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Archive")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = loadedFileName,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Left panel: List
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
            ) {
                if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val errText = isError
                        Text(
                            text = errText ?: "Please open an archive file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (errText != null) {
                                Color(0xFFF87171)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = 0.6f)
                            }
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries) { entry ->
                            EntryItem(
                                entry = entry,
                                isSelected = entry == selectedEntry,
                                onClick = {
                                    selectedEntry = entry
                                    previewData = null
                                    isExtracting = true
                                    coroutineScope.launch {
                                        previewData = extractPreview(
                                            entry,
                                            activeReader
                                        )
                                        isExtracting = false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF3D3D3D))
            )

            // Right panel: Preview
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp)
            ) {
                val entry = selectedEntry
                if (entry == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select a file to preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF121212))
                                .padding(16.dp)
                        ) {
                            if (isExtracting) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                previewData?.let { data ->
                                    PreviewContent(data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryItem(
    entry: ArchiveEntry,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = if (entry.isDirectory) {
            Icons.Default.Folder
        } else {
            Icons.Default.InsertDriveFile
        }
        val iconColor = if (entry.isDirectory) {
            Color(0xFFF1C40F)
        } else {
            Color(0xFF95A5A6)
        }

        Icon(
            icon,
            contentDescription = null,
            tint = iconColor
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PreviewContent(data: PreviewData) {
    when (data) {
        is PreviewData.TextPreview -> {
            SelectionContainer {
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = data.content,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        is PreviewData.ImagePreview -> {
            val bitmap = remember(data.bytes) {
                runCatching {
                    SkiaImage.makeFromEncoded(data.bytes).toComposeImageBitmap()
                }.getOrNull()
            }
            if (bitmap != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Image Preview",
                        modifier = Modifier.fillMaxSize(0.9f)
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Failed to render image preview",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFF87171)
                    )
                }
            }
        }

        is PreviewData.BinaryPreview -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(48.dp).width(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Binary File Preview Unavailable",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Size: ${data.size} bytes",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val hexCrc = data.crc.toString(16).uppercase()
                    Text(
                        text = "CRC: 0x$hexCrc",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private suspend fun extractPreview(
    entry: ArchiveEntry,
    reader: ArchiveReader?
): PreviewData {
    if (reader == null) {
        return PreviewData.TextPreview("Error: Archive reader not initialized.")
    }
    if (entry.isDirectory) {
        return PreviewData.BinaryPreview(entry.size, entry.crc)
    }

    return withContext(Dispatchers.IO) {
        runCatching {
            entry.extractToByteArray(reader)
        }.fold(
            onSuccess = { bytes ->
                if (isImageFile(entry.name)) {
                    PreviewData.ImagePreview(bytes)
                } else {
                    val isBinary = bytes.any { it == 0.toByte() }
                    if (isBinary) {
                        PreviewData.BinaryPreview(entry.size, entry.crc)
                    } else {
                        PreviewData.TextPreview(bytes.decodeToString())
                    }
                }
            },
            onFailure = { error ->
                PreviewData.TextPreview("Extraction failed: ${error.message}")
            }
        )
    }
}

private fun isImageFile(name: String): Boolean {
    val lower = name.lowercase()
    return lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
            lower.endsWith(".webp") || lower.endsWith(".bmp")
}
