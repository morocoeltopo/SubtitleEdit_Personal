package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePasteOpsTest {

    private fun sampleEntries() = mutableListOf(
        SubtitleEntry(index = 1, startTime = 0, endTime = 1000, text = "A"),
        SubtitleEntry(index = 2, startTime = 3000, endTime = 4000, text = "B")
    )

    // ==================== pasteAtPosition ====================

    @Test
    fun pasteAtPosition_singleText_replacesInPlace() {
        val entries = sampleEntries()
        val result = SubtitlePasteOps.pasteAtPosition(entries, 0, listOf("X"))
        assertFalse(result.structureChanged)
        assertEquals(setOf(0), result.affectedPositions)
        assertEquals("X", entries[0].text)
        assertEquals(2, entries.size)
    }

    @Test
    fun pasteAtPosition_emptyClipboardIsNoOp() {
        val entries = sampleEntries()
        val result = SubtitlePasteOps.pasteAtPosition(entries, 0, emptyList())
        assertFalse(result.structureChanged)
        assertTrue(result.affectedPositions.isEmpty())
        assertEquals("A", entries[0].text)
    }

    @Test
    fun pasteAtPosition_invalidPositionIsNoOp() {
        val entries = sampleEntries()
        val result = SubtitlePasteOps.pasteAtPosition(entries, 5, listOf("X"))
        assertFalse(result.structureChanged)
        assertTrue(result.affectedPositions.isEmpty())
    }

    @Test
    fun pasteAtPosition_multipleTexts_insertsIntoGap() {
        val entries = sampleEntries()
        val result = SubtitlePasteOps.pasteAtPosition(entries, 0, listOf("X", "Y", "Z"))

        assertTrue(result.structureChanged)
        assertEquals(setOf(0, 1, 2), result.affectedPositions)
        assertEquals(4, entries.size)
        assertEquals(listOf("X", "Y", "Z", "B"), entries.map { it.text })
        // 多出的两条平分 A 结束（1000）到 B 开始（3000）之间的空隙
        assertEquals(1000L, entries[1].startTime)
        assertEquals(2000L, entries[1].endTime)
        assertEquals(2000L, entries[2].startTime)
        assertEquals(3000L, entries[2].endTime)
    }

    // ==================== pasteToSelection ====================

    @Test
    fun pasteToSelection_equalCounts_replacesInSortedOrder() {
        val entries = mutableListOf(
            SubtitleEntry(text = "A"),
            SubtitleEntry(text = "B"),
            SubtitleEntry(text = "C")
        )
        // 选择顺序乱序传入，粘贴按位置升序对应
        val result = SubtitlePasteOps.pasteToSelection(entries, listOf(2, 0), listOf("x", "y"))
        assertEquals(setOf(0, 2), result.affectedPositions)
        assertEquals(listOf("x", "B", "y"), entries.map { it.text })
    }

    @Test
    fun pasteToSelection_insufficientClipboardIsNoOp() {
        val entries = mutableListOf(SubtitleEntry(text = "A"), SubtitleEntry(text = "B"))
        val result = SubtitlePasteOps.pasteToSelection(entries, listOf(0, 1), listOf("x"))
        assertTrue(result.affectedPositions.isEmpty())
        assertEquals(listOf("A", "B"), entries.map { it.text })
    }

    @Test
    fun pasteToSelection_extraTextsInsertedAfterLastSelected() {
        val entries = mutableListOf(
            SubtitleEntry(startTime = 0, endTime = 1000, text = "A"),
            SubtitleEntry(startTime = 5000, endTime = 6000, text = "B")
        )
        val result = SubtitlePasteOps.pasteToSelection(entries, listOf(0), listOf("x", "y"))
        assertEquals(setOf(0, 1), result.affectedPositions)
        assertEquals(3, entries.size)
        assertEquals(listOf("x", "y", "B"), entries.map { it.text })
        // 空隙足够大（5000 > 默认 1000+3000），插入条目使用默认 3 秒时长
        assertEquals(1000L, entries[1].startTime)
        assertEquals(4000L, entries[1].endTime)
    }

    @Test
    fun pasteToSelection_duplicatePositionsDeduplicated() {
        val entries = mutableListOf(SubtitleEntry(text = "A"), SubtitleEntry(text = "B"))
        val result = SubtitlePasteOps.pasteToSelection(entries, listOf(1, 1, 0), listOf("x", "y"))
        assertEquals(setOf(0, 1), result.affectedPositions)
        assertEquals(listOf("x", "y"), entries.map { it.text })
    }

    @Test
    fun pasteToSelection_outOfRangePositionsFiltered() {
        val entries = mutableListOf(SubtitleEntry(text = "A"), SubtitleEntry(text = "B"))
        val result = SubtitlePasteOps.pasteToSelection(entries, listOf(0, 5), listOf("x"))
        assertEquals(setOf(0), result.affectedPositions)
        assertEquals("x", entries[0].text)
    }
}
