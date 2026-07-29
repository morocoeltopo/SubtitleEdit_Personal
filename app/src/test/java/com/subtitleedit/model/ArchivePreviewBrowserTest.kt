package com.subtitleedit.model

import com.subtitleedit.util.ArchiveManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivePreviewBrowserTest {
    @Test
    fun `folder counts direct children including implicit folders`() {
        val browser = ArchivePreviewBrowser(
            listOf(
                entry("docs/readme.txt", modifiedTimeMillis = 1234L),
                entry("docs/images/cover.png", modifiedTimeMillis = 5678L),
                entry("docs/.hidden"),
                entry("empty/", isDirectory = true, modifiedTimeMillis = 999L)
            )
        )

        val root = browser.itemsAt("")
        assertEquals(2, root.size)
        assertEquals(3, root.single { it.name == "docs" }.itemCount)
        assertEquals(5678L, root.single { it.name == "docs" }.modifiedTimeMillis)
        assertEquals(0, root.single { it.name == "empty" }.itemCount)
        assertEquals(999L, root.single { it.name == "empty" }.modifiedTimeMillis)

        val docs = browser.itemsAt("docs")
        assertEquals(3, docs.size)
        assertEquals(1, docs.single { it.name == "images" }.itemCount)
        assertEquals(5678L, docs.single { it.name == "images" }.modifiedTimeMillis)
        assertEquals(1234L, docs.single { it.name == "readme.txt" }.modifiedTimeMillis)
        assertTrue(docs.single { it.name == "images" }.isDirectory)
    }

    @Test
    fun `explicit folder timestamp takes priority over derived timestamp`() {
        val browser = ArchivePreviewBrowser(
            listOf(
                entry("docs/file.txt", modifiedTimeMillis = 5678L),
                entry("docs/", isDirectory = true, modifiedTimeMillis = 2000L)
            )
        )

        assertEquals(2000L, browser.itemsAt("").single().modifiedTimeMillis)
    }

    private fun entry(
        name: String,
        isDirectory: Boolean = false,
        modifiedTimeMillis: Long = 0L
    ) = ArchiveManager.EntryInfo(
        name = name,
        size = if (isDirectory) 0L else 10L,
        compressedSize = 5L,
        isDirectory = isDirectory,
        modifiedTimeMillis = modifiedTimeMillis
    )
}
