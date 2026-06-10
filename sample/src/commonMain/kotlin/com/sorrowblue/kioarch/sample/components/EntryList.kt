package com.sorrowblue.kioarch.sample.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.sample.components.icons.Description
import com.sorrowblue.kioarch.sample.components.icons.Folder
import com.sorrowblue.kioarch.sample.components.icons.Icons
import kotlin.math.log10
import kotlin.math.pow

/**
 * A beautiful, scrollable list showing the individual files and directories inside an archive.
 *
 * @param entries The list of parsed [ArchiveEntry] objects from KioArch.
 * @param onEntryClick Callback triggered when a file entry is clicked.
 * @param modifier The modifier to be applied to the layout.
 */
@Composable
internal fun EntryList(
    entries: List<ArchiveEntry>,
    onEntryClick: (ArchiveEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(entries) { entry ->
            EntryItem(entry = entry, onEntryClick = onEntryClick)
        }
    }
}

private const val HExRadix = 16
private const val BytesInKB = 1024.0

@Composable
private fun EntryItem(entry: ArchiveEntry, onEntryClick: (ArchiveEntry) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !entry.isDirectory) { onEntryClick(entry) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EntryItemIcon(isDirectory = entry.isDirectory)

            Spacer(modifier = Modifier.width(16.dp))

            EntryItemDetails(
                name = entry.name,
                isDirectory = entry.isDirectory,
                crc = entry.crc,
                modifier = Modifier.weight(1f)
            )

            EntryItemSize(
                isDirectory = entry.isDirectory,
                size = entry.size
            )
        }
    }
}

@Composable
private fun EntryItemIcon(isDirectory: Boolean) {
    val icon = if (isDirectory) Icons.Folder else Icons.Description
    val iconColor = if (isDirectory) {
        Color(0xFFFFCA28) // Warm Folder Amber
    } else {
        MaterialTheme.colorScheme.primary // Cool File Primary Color
    }

    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconColor,
        modifier = Modifier.size(32.dp)
    )
}

@Composable
private fun EntryItemDetails(
    name: String,
    isDirectory: Boolean,
    crc: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!isDirectory) {
            Text(
                text = "CRC: ${crc.toString(HExRadix).uppercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun EntryItemSize(isDirectory: Boolean, size: Long) {
    if (!isDirectory) {
        Text(
            text = formatFileSize(size),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Text(
            text = "Dir",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
        )
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(BytesInKB)).toInt()
    val a = size / BytesInKB.pow(digitGroups.toDouble())
    val unit = units[digitGroups]
    return "${formatDecimal(a, 1)} $unit"
}

expect fun formatDecimal(value: Double, precision: Int = 2): String
