package com.sorrowblue.kioarch.sample.section

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sorrowblue.kioarch.sample.MainUiState
import com.sorrowblue.kioarch.sample.components.icons.CheckCircle
import com.sorrowblue.kioarch.sample.components.icons.Icons

@Composable
internal fun SuccessSection(state: MainUiState.Success, onBackClick: () -> Unit) {
    StatusSection(
        icon = {
            Icon(
                imageVector = Icons.CheckCircle,
                contentDescription = null,
                tint = Color(SuccessColor),
                modifier = Modifier.size(88.dp)
            )
        },
        title = "Unpacked Successfully!",
        titleColor = MaterialTheme.colorScheme.onBackground,
        message = "${state.fileName} has been extracted without any intermediate filesystem files.",
        buttonText = "Back to Dashboard",
        buttonColors = ButtonDefaults.buttonColors(),
        onClick = onBackClick
    )
}

private const val SuccessColor = 0xFF4CAF50

@Preview
@Composable
private fun SuccessSectionPreview() {
    MaterialTheme {
        SuccessSection(
            state = MainUiState.Success(fileName = "example.zip", "message"),
            onBackClick = {}
        )
    }
}
