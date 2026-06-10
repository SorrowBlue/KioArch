package com.sorrowblue.kioarch.sample.section

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sorrowblue.kioarch.sample.MainUiState
import com.sorrowblue.kioarch.sample.components.icons.Error
import com.sorrowblue.kioarch.sample.components.icons.Icons

@Composable
internal fun ErrorSection(state: MainUiState.Error, onBackClick: () -> Unit) {
    StatusSection(
        icon = {
            Icon(
                imageVector = Icons.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(88.dp)
            )
        },
        title = "Extraction Failed",
        titleColor = MaterialTheme.colorScheme.error,
        message = state.message,
        buttonText = "Dismiss",
        buttonColors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error
        ),
        onClick = onBackClick
    )
}

@Preview
@Composable
private fun ErrorSectionPreview() {
    MaterialTheme {
        ErrorSection(
            state = MainUiState.Error(
                message = "Failed to extract example.zip: Invalid archive format."
            ),
            onBackClick = {}
        )
    }
}
