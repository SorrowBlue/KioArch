package com.sorrowblue.kioarch.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import com.sorrowblue.kioarch.ArchiveEntry
import com.sorrowblue.kioarch.sample.section.ErrorSection
import com.sorrowblue.kioarch.sample.section.IdleSection
import com.sorrowblue.kioarch.sample.section.LoadedSection
import com.sorrowblue.kioarch.sample.section.LoadingSection
import com.sorrowblue.kioarch.sample.section.PreviewDialog
import com.sorrowblue.kioarch.sample.section.SuccessSection

@Composable
internal fun ArchiveDashboard(modifier: Modifier = Modifier) {
    val state = rememberArchiveDashboardState()
    val uiState by state.uiState
    val previewState by state.previewState
    ArchiveDashboard(
        uiState = uiState,
        previewState = previewState,
        onClickSelect = { state.onClickSelect() },
        onExtractClick = { state.onExtractClick() },
        onResetClick = { state.resetState() },
        onEntryClick = { state.onEntryClick(it) },
        dismissPreview = { state.dismissPreview() },
        modifier = modifier
    )
}

@Composable
private fun ArchiveDashboard(
    uiState: MainUiState,
    previewState: PreviewState,
    onClickSelect: () -> Unit,
    onExtractClick: () -> Unit,
    onResetClick: () -> Unit,
    onEntryClick: (ArchiveEntry) -> Unit,
    dismissPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Kioarch Sample") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedStateContainer(
                uiState = uiState,
                onClickSelect = onClickSelect,
                onExtractClick = onExtractClick,
                onResetClick = onResetClick,
                onEntryClick = onEntryClick,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }

    if (previewState != PreviewState.Idle) {
        PreviewDialog(previewState = previewState, onDismiss = dismissPreview)
    }
}

@Composable
private fun FadeVisible(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        content()
    }
}

@Composable
private fun AnimatedStateContainer(
    uiState: MainUiState,
    onClickSelect: () -> Unit,
    onExtractClick: () -> Unit,
    onResetClick: () -> Unit,
    onEntryClick: (ArchiveEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val extracting = uiState as? MainUiState.Extracting
    val loaded = uiState as? MainUiState.Loaded
    val success = uiState as? MainUiState.Success
    val error = uiState as? MainUiState.Error

    Box(modifier = modifier) {
        FadeVisible(uiState is MainUiState.Idle) {
            IdleSection(onClickSelect)
        }
        FadeVisible(uiState is MainUiState.Loading) {
            LoadingSection("Reading Archive Magic Bytes...")
        }
        FadeVisible(extracting != null) {
            LoadingSection("Streaming and unpacking: ${extracting?.fileName.orEmpty()}...")
        }
        FadeVisible(loaded != null) {
            loaded?.let {
                LoadedSection(
                    state = it,
                    onExtractClick = onExtractClick,
                    onResetClick = onResetClick,
                    onEntryClick = onEntryClick
                )
            }
        }
        FadeVisible(success != null) {
            success?.let { SuccessSection(state = it, onBackClick = onResetClick) }
        }
        FadeVisible(error != null) {
            error?.let { ErrorSection(state = it, onBackClick = onResetClick) }
        }
    }
}

@Preview()
@Composable
private fun AnimatedStateContainerPreview(
    @PreviewParameter(MainUiStatePreviewProvider::class) uiState: MainUiState
) {
    MaterialTheme {
        ArchiveDashboard(
            uiState = uiState,
            previewState = PreviewState.Idle,
            onClickSelect = {},
            onExtractClick = {},
            onResetClick = {},
            onEntryClick = {},
            dismissPreview = {}
        )
    }
}

private class MainUiStatePreviewProvider : PreviewParameterProvider<MainUiState> {
    override val values: Sequence<MainUiState>
        get() = sequenceOf(
            MainUiState.Idle,
            MainUiState.Loading,
            MainUiState.Success(fileName = "example.zip", "5.2 MB"),
            MainUiState.Error("Failed to extract the archive. Please try again.")
        )
}
