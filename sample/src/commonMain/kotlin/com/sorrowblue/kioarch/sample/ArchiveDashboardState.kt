package com.sorrowblue.kioarch.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sorrowblue.kioarch.ArchiveEntry
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

internal interface ArchiveDashboardState {
    fun onClickSelect()
    fun onExtractClick()
    fun onEntryClick(entry: ArchiveEntry)
    fun dismissPreview()
    fun resetState()

    val uiState: State<MainUiState>
    val previewState: State<PreviewState>
}

@Composable
internal fun rememberArchiveDashboardState(): ArchiveDashboardState {
    val viewModel: MainViewModel = viewModel { MainViewModel() }
    val platformContext = LocalPlatformContext.current

    val state = remember(viewModel, platformContext) {
        ArchiveDashboardStateImpl(viewModel, platformContext)
    }

    state.uiState = viewModel.uiState.collectAsState()
    state.previewState = viewModel.previewState.collectAsState()
    state.filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(
            extensions = listOf("zip", "7z", "tar.gz", "tgz")
        ),
        mode = FileKitMode.Single,
        onResult = { file ->
            file?.let {
                viewModel.loadArchive(platformContext, it)
            }
        }
    )

    state.directoryPickerLauncher =
        rememberDirectoryPickerLauncher(onResult = { file ->
            file?.let {
                viewModel.extractArchive(platformContext, it)
            }
        })

    return state
}

private class ArchiveDashboardStateImpl(
    private val viewModel: MainViewModel,
    private val platformContext: PlatformContext
) : ArchiveDashboardState {

    override lateinit var uiState: State<MainUiState>
    override lateinit var previewState: State<PreviewState>
    lateinit var filePickerLauncher: PickerResultLauncher
    lateinit var directoryPickerLauncher: PickerResultLauncher

    override fun resetState() {
        viewModel.resetState()
    }

    override fun onClickSelect() {
        filePickerLauncher.launch()
    }

    override fun onExtractClick() {
        directoryPickerLauncher.launch()
    }

    override fun onEntryClick(entry: ArchiveEntry) {
        viewModel.previewEntry(platformContext, entry)
    }

    override fun dismissPreview() {
        viewModel.dismissPreview()
    }
}
