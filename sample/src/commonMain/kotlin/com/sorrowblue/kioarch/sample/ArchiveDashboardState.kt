package com.sorrowblue.kioarch.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberDirectoryPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

internal interface ArchiveDashboardState {
    fun onClickSelect()
    fun onExtractClick()
    fun resetState()

    val uiState: State<MainUiState>
}

@Composable
internal fun rememberArchiveDashboardState(): ArchiveDashboardState {
    val viewModel: MainViewModel = viewModel { MainViewModel() }

    val state = remember(viewModel) {
        ArchiveDashboardStateImpl(viewModel)
    }

    val platformContext = LocalPlatformContext.current

    state.uiState = viewModel.uiState.collectAsState()
    state.filePickerLauncher = rememberFilePickerLauncher(
        type = FileKitType.File(
            extensions = listOf("zip", "7z", "tar.gz", "tgz")
        ), mode = FileKitMode.Single, onResult = { file ->
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

private class ArchiveDashboardStateImpl(private val viewModel: MainViewModel) : ArchiveDashboardState {

    override lateinit var uiState: State<MainUiState>
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
}
