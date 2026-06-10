package com.sorrowblue.kioarch.sample.section

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.sample.MainUiState
import com.sorrowblue.kioarch.sample.components.EntryList
import com.sorrowblue.kioarch.sample.components.icons.Archive
import com.sorrowblue.kioarch.sample.components.icons.FolderOpen
import com.sorrowblue.kioarch.sample.components.icons.Icons

@Composable
internal fun LoadedSection(
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

        Box(modifier = Modifier.weight(1f)) {
            EntryList(entries = state.entries, onEntryClick = onEntryClick)
        }

        Spacer(modifier = Modifier.height(16.dp))

        BottomActionButtons(
            onExtractClick = onExtractClick,
            onResetClick = onResetClick
        )
    }
}

@Composable
private fun BottomActionButtons(onResetClick: () -> Unit, onExtractClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onResetClick,
            modifier = Modifier.weight(NegativeButtonWeight),
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
            modifier = Modifier.weight(PositiveButtonWeight),
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

private const val PositiveButtonWeight = 0.6f
private const val NegativeButtonWeight = 1 - PositiveButtonWeight

@Suppress("MagicNumber")
@Preview(showBackground = true)
@Composable
private fun LoadedSectionPreview() {
    MaterialTheme {
        LoadedSection(
            state = MainUiState.Loaded(
                fileName = "example_archive.zip",
                entries = List(10) {
                    ArchiveEntry(it, "file$it.txt", 1024L * it, 0, it == 0, 0)
                }
            ),
            onExtractClick = {},
            onResetClick = {},
            onEntryClick = {}
        )
    }
}
