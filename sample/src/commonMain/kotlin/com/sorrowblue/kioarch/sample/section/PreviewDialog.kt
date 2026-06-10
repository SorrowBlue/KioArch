package com.sorrowblue.kioarch.sample.section

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.sorrowblue.kioarch.sample.PreviewState
import com.sorrowblue.kioarch.sample.components.decodeByteArrayToImageBitmap
import com.sorrowblue.kioarch.sample.components.icons.Error
import com.sorrowblue.kioarch.sample.components.icons.Icons

@Composable
internal fun PreviewDialog(previewState: PreviewState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = previewTitle(previewState),
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
                    PreviewState.Loading -> CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )

                    is PreviewState.Text -> PreviewTextContent(previewState.content)

                    is PreviewState.Image -> PreviewImageContent(previewState.bytes)

                    is PreviewState.Unsupported -> PreviewMessageContent(
                        message = "対応していない形式です。 (.${previewState.extension})"
                    )

                    is PreviewState.Error -> PreviewMessageContent(
                        message = previewState.message,
                        isError = true
                    )

                    PreviewState.Idle -> Unit
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        properties = DialogProperties(usePlatformDefaultWidth = true)
    )
}

@Composable
private fun PreviewTextContent(content: String) {
    val scrollState = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp)
            .verticalScroll(scrollState)
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PreviewImageContent(bytes: ByteArray) {
    val bitmap = remember(bytes) { decodeByteArrayToImageBitmap(bytes) }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.fillMaxWidth(),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun PreviewMessageContent(message: String, isError: Boolean = false) {
    val color =
        if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
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
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            textAlign = TextAlign.Center
        )
    }
}

private fun previewTitle(previewState: PreviewState): String = when (previewState) {
    is PreviewState.Text -> "Text Preview"
    is PreviewState.Image -> "Image Preview"
    is PreviewState.Unsupported -> "Unsupported Format"
    is PreviewState.Error -> "Error"
    PreviewState.Loading -> "Loading..."
    PreviewState.Idle -> ""
}

@Preview
@Composable
private fun PreviewDialogPreview() {
    MaterialTheme {
        PreviewDialog(
            previewState = PreviewState.Text(
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit."
            ),
            onDismiss = {}
        )
    }
}
