package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlePasteOpsTest {
    @Test
    fun singlePasteChangesOnlyText() {
        val target = SubtitleEntry(
            index = 7,
            startTime = 1_250L,
            endTime = 2_750L,
            text = "旧文本",
            endTimeModified = true
        )

        val result = SubtitlePasteOps.pasteAtPosition(
            entries = mutableListOf(target),
            position = 0,
            clipboardTexts = listOf("新文本")
        )

        assertEquals("新文本", target.text)
        assertEquals(7, target.index)
        assertEquals(1_250L, target.startTime)
        assertEquals(2_750L, target.endTime)
        assertTrue(target.endTimeModified)
        assertFalse(result.structureChanged)
    }

    @Test
    fun multilinePasteGeneratesNewTimingsFromTargetSide() {
        val entries = mutableListOf(
            SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "目标"),
            SubtitleEntry(startTime = 8_000L, endTime = 9_000L, text = "后一行")
        )

        val result = SubtitlePasteOps.pasteAtPosition(
            entries = entries,
            position = 0,
            clipboardTexts = listOf("一", "二", "三")
        )

        assertEquals(listOf("一", "二", "三", "后一行"), entries.map { it.text })
        assertEquals(1_000L, entries[0].startTime)
        assertEquals(2_000L, entries[0].endTime)
        assertEquals(2_000L, entries[1].startTime)
        assertEquals(5_000L, entries[1].endTime)
        assertEquals(5_000L, entries[2].startTime)
        assertEquals(8_000L, entries[2].endTime)
        assertTrue(result.structureChanged)
    }

    @Test
    fun selectionPastePreservesEveryTargetTimeline() {
        val entries = mutableListOf(
            SubtitleEntry(startTime = 100L, endTime = 200L, text = "甲"),
            SubtitleEntry(startTime = 300L, endTime = 400L, text = "乙")
        )

        val result = SubtitlePasteOps.pasteToSelection(
            entries = entries,
            selectedPositions = listOf(0, 1),
            clipboardTexts = listOf("A", "B")
        )

        assertEquals(listOf("A", "B"), entries.map { it.text })
        assertEquals(listOf(100L, 300L), entries.map { it.startTime })
        assertEquals(listOf(200L, 400L), entries.map { it.endTime })
        assertEquals(setOf(0, 1), result.affectedPositions)
    }

    @Test
    fun selectionPasteInsertsExtraTextsAfterLastTarget() {
        val entries = mutableListOf(
            SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "目标一"),
            SubtitleEntry(startTime = 3_000L, endTime = 4_000L, text = "目标二"),
            SubtitleEntry(startTime = 7_000L, endTime = 8_000L, text = "后一行")
        )

        val result = SubtitlePasteOps.pasteToSelection(
            entries = entries,
            selectedPositions = listOf(0, 1),
            clipboardTexts = listOf("A", "B", "C")
        )

        assertEquals(listOf("A", "B", "C", "后一行"), entries.map { it.text })
        assertEquals(3_000L, entries[1].startTime)
        assertEquals(4_000L, entries[1].endTime)
        assertEquals(4_000L, entries[2].startTime)
        assertEquals(7_000L, entries[2].endTime)
        assertEquals(setOf(0, 1, 2), result.affectedPositions)
    }

    @Test
    fun selectionPasteCanInsertTwoExtraTextsAfterSingleTarget() {
        val entries = mutableListOf(
            SubtitleEntry(startTime = 1_000L, endTime = 2_000L, text = "目标"),
            SubtitleEntry(startTime = 6_000L, endTime = 7_000L, text = "后一行")
        )

        SubtitlePasteOps.pasteToSelection(
            entries = entries,
            selectedPositions = listOf(0),
            clipboardTexts = listOf("A", "B", "C")
        )

        assertEquals(listOf("A", "B", "C", "后一行"), entries.map { it.text })
        assertEquals(listOf(2_000L, 4_000L), entries.slice(1..2).map { it.startTime })
        assertEquals(listOf(4_000L, 6_000L), entries.slice(1..2).map { it.endTime })
    }
}
