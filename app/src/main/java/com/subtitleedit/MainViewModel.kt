package com.subtitleedit

import androidx.lifecycle.ViewModel
import java.io.File

internal enum class FileOperation { COPY, MOVE, EXTRACT }

internal data class DestinationNavigationState(
    val directory: File,
    val directoryHistory: List<File>
)

internal class MainViewModel : ViewModel() {
    var currentDirectory: File? = null
    val directoryHistory = mutableListOf<File>()
    val selectedPaths = linkedSetOf<String>()
    var pendingFileOperation: FileOperation? = null
    var pendingArchiveFile: File? = null
    val destinationNavigationHistory = mutableListOf<DestinationNavigationState>()
}
