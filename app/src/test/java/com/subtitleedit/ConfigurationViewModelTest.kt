package com.subtitleedit

import com.subtitleedit.model.SubtitleEntry
import com.subtitleedit.util.SubtitleParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationViewModelTest {
    @Test
    fun mainStateKeepsNavigationAndDestinationSelection() {
        val model = MainViewModel()
        val download = File("/storage/emulated/0/Download")
        val parent = File("/storage/emulated/0")

        model.currentDirectory = download
        model.directoryHistory += parent
        model.selectedPaths += File(download, "example.srt").path
        model.pendingFileOperation = FileOperation.COPY
        model.destinationNavigationHistory += DestinationNavigationState(parent, emptyList())

        assertEquals(download, model.currentDirectory)
        assertEquals(listOf(parent), model.directoryHistory)
        assertEquals(FileOperation.COPY, model.pendingFileOperation)
        assertEquals(1, model.selectedPaths.size)
        assertEquals(1, model.destinationNavigationHistory.size)
    }

    @Test
    fun editorStateKeepsUnsavedDocumentAndUiPosition() {
        val model = EditorViewModel()
        val entry = SubtitleEntry(1, 100L, 900L, "未保存内容")

        model.documentLoaded = true
        model.subtitleEntries += entry
        model.currentFormat = SubtitleParser.SubtitleFormat.SRT
        model.hasUnsavedChanges = true
        model.isSourceViewMode = false
        model.selectedIndices = setOf(0)
        model.savedFirstVisibleItemPosition = 0
        model.savedScrollPosition = -12

        assertSame(entry, model.subtitleEntries.single())
        assertTrue(model.hasUnsavedChanges)
        assertEquals(setOf(0), model.selectedIndices)
        assertEquals(-12, model.savedScrollPosition)
    }

    @Test
    fun editorStateKeepsSourceDocumentAndCreatedUri() {
        val model = EditorViewModel()
        model.documentLoaded = true
        model.isSourceViewMode = true
        model.sourceViewContent = "line 1\nline 2"
        model.documentUri = "content://documents/subtitle.srt"
        model.isNewFile = false

        assertEquals("line 1\nline 2", model.sourceViewContent)
        assertEquals("content://documents/subtitle.srt", model.documentUri)
        assertTrue(!model.isNewFile)
    }
}
