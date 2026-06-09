package com.sorrowblue.kioarch.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.sample.components.EntryList
import com.sorrowblue.kioarch.sample.components.decodeByteArrayToImageBitmap
import com.sorrowblue.kioarch.sample.components.icons.Archive
import com.sorrowblue.kioarch.sample.components.icons.CheckCircle
import com.sorrowblue.kioarch.sample.components.icons.Error
import com.sorrowblue.kioarch.sample.components.icons.FolderOpen
import com.sorrowblue.kioarch.sample.components.icons.Icons

/**
 * Main dashboard screen of the KioArch Android sample app.
 * Renders a state-driven, beautifully crafted UI built with Material 3, custom gradients,
 * and high-fidelity micro-interactions for a premium user experience.
 *
 * @param viewModel The view model that handles archive ingestion and decompression logic.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
internal fun ArchiveDashboard(modifier: Modifier = Modifier) {
    val state = rememberArchiveDashboardState()
    val uiState by state.uiState
    val previewState by state.previewState

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Premium Hero Header Section
            HeroHeader()

            Spacer(modifier = Modifier.height(24.dp))

            // State-driven UI Content Area
            AnimatedStateContainer(
                uiState = uiState,
                onClickSelect = { state.onClickSelect() },
                onExtractClick = { state.onExtractClick() },
                onResetClick = { state.resetState() },
                onEntryClick = { state.onEntryClick(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }

    if (previewState != PreviewState.Idle) {
        PreviewDialog(
            previewState = previewState,
            onDismiss = { state.dismissPreview() }
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun AnimatedStateContainer(
    uiState: MainUiState,
    onClickSelect: () -> Unit,
    onExtractClick: () -> Unit,
    onResetClick: () -> Unit,
    onEntryClick: (ArchiveEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        AnimatedVisibility(
            visible = uiState is MainUiState.Idle,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IdleSection(onClickSelect = onClickSelect)
        }

        AnimatedVisibility(
            visible = uiState is MainUiState.Loading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            LoadingSection("Reading Archive Magic Bytes...")
        }

        AnimatedVisibility(
            visible = uiState is MainUiState.Extracting,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val state = uiState as? MainUiState.Extracting
            LoadingSection("Streaming and unpacking: ${state?.fileName.orEmpty()}...")
        }

        AnimatedVisibility(
            visible = uiState is MainUiState.Loaded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val state = uiState as? MainUiState.Loaded
            if (state != null) {
                LoadedSection(
                    state = state,
                    onExtractClick = onExtractClick,
                    onResetClick = onResetClick,
                    onEntryClick = onEntryClick
                )
            }
        }

        AnimatedVisibility(
            visible = uiState is MainUiState.Success,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val state = uiState as? MainUiState.Success
            if (state != null) {
                SuccessSection(
                    state = state,
                    onBackClick = onResetClick
                )
            }
        }

        AnimatedVisibility(
            visible = uiState is MainUiState.Error,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val state = uiState as? MainUiState.Error
            if (state != null) {
                ErrorSection(
                    state = state,
                    onBackClick = onResetClick
                )
            }
        }
    }
}

@Composable
private fun HeroHeader() {
    val gradient = Brush.horizontalGradient(
        colors = listOf(Color(0xFF8A2387), Color(0xFFE94057), Color(0xFFF27121))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(gradient)
            .padding(vertical = 24.dp, horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "KioArch Android",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Transparent Filesystem-Free Decompression",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
@Suppress("MagicNumber")
private fun IdleSection(onClickSelect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
            .background(Color(0x0AFFFFFF))
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Archive,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier.size(80.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "No archive selected",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Support .7z and .zip extensions natively in pure memory space.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onClickSelect,
                modifier = Modifier.semantics { contentDescription = "Choose Archive File" },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(
                    top = 14.dp,
                    bottom = 14.dp,
                    start = 24.dp,
                    end = 24.dp
                )
            ) {
                Icon(Icons.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Choose Archive File", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
@Suppress("MagicNumber")
private fun LoadingSection(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 5.dp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = message,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
@Suppress("LongMethod", "MagicNumber")
private fun LoadedSection(
    state: MainUiState.Loaded,
    onExtractClick: () -> Unit,
    onResetClick: () -> Unit,
    onEntryClick: (ArchiveEntry) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = state.fileName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "${state.entries.size} Entries detected",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ARCHIVE DIRECTORY MAP",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Embedded scrollable archive entry list
        Box(modifier = Modifier.weight(1f)) {
            EntryList(entries = state.entries, onEntryClick = onEntryClick)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onResetClick,
                modifier = Modifier.weight(0.4f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onExtractClick,
                modifier = Modifier.weight(0.6f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.FolderOpen, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Extract All", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
@Suppress("MagicNumber")
private fun SuccessSection(state: MainUiState.Success, onBackClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(88.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Unpacked Successfully!",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${state.fileName} has been extracted without " +
                        "any intermediate filesystem files.",
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onBackClick,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Back to Dashboard", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
@Suppress("MagicNumber")
private fun ErrorSection(state: MainUiState.Error, onBackClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(88.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Extraction Failed",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.message,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Dismiss", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "MagicNumber")
private fun PreviewDialog(
    previewState: PreviewState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = when (previewState) {
                    is PreviewState.Text -> "Text Preview"
                    is PreviewState.Image -> "Image Preview"
                    is PreviewState.Unsupported -> "Unsupported Format"
                    is PreviewState.Error -> "Error"
                    PreviewState.Loading -> "Loading..."
                    PreviewState.Idle -> ""
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                contentAlignment = Alignment.Center
            ) {
                when (previewState) {
                    PreviewState.Loading -> {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    is PreviewState.Text -> {
                        val scrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = previewState.content,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    is PreviewState.Image -> {
                        val bitmap = remember(previewState.bytes) {
                            decodeByteArrayToImageBitmap(previewState.bytes)
                        }
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    is PreviewState.Unsupported -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "対応していない形式です。 (.${previewState.extension})",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    is PreviewState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = previewState.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    PreviewState.Idle -> {}
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    )
}
