package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleEntryOpsTest {
    @Test
    fun insertBeforeUsesPreviousSubtitleEndWhenGapIsShorterThanDefault() {
        val result = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 10_000L, endTime = 12_000L),
            previous = SubtitleEntry(startTime = 7_000L, endTime = 8_500L),
            next = null
        )

        assertEquals(8_500L, result.startTime)
        assertEquals(10_000L, result.endTime)
    }

    @Test
    fun insertAfterUsesNextSubtitleStartWhenGapIsShorterThanDefault() {
        val result = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(startTime = 10_000L, endTime = 12_000L),
            previous = null,
            next = SubtitleEntry(startTime = 13_250L, endTime = 15_000L)
        )

        assertEquals(12_000L, result.startTime)
        assertEquals(13_250L, result.endTime)
    }

    @Test
    fun insertKeepsDefaultDurationWhenNeighborIsFarEnoughAway() {
        val before = SubtitleEntryOps.createInsertedEntry(
            after = false,
            reference = SubtitleEntry(startTime = 10_000L),
            previous = SubtitleEntry(endTime = 5_000L),
            next = null
        )
        val after = SubtitleEntryOps.createInsertedEntry(
            after = true,
            reference = SubtitleEntry(endTime = 12_000L),
            previous = null,
            next = SubtitleEntry(startTime = 20_000L)
        )

        assertEquals(7_000L, before.startTime)
        assertEquals(10_000L, before.endTime)
        assertEquals(12_000L, after.startTime)
        assertEquals(15_000L, after.endTime)
    }

    @Test
    fun insertMultipleBeforeDistributesShortGapEvenly() {
        val results = SubtitleEntryOps.createInsertedEntries(
            after = false,
            reference = SubtitleEntry(startTime = 10_000L),
            previous = SubtitleEntry(endTime = 7_000L),
            next = null,
            texts = listOf("一", "二", "三")
        )

        assertEquals(listOf("一", "二", "三"), results.map { it.text })
        assertEquals(listOf(7_000L, 8_000L, 9_000L), results.map { it.startTime })
        assertEquals(listOf(8_000L, 9_000L, 10_000L), results.map { it.endTime })
    }

    @Test
    fun insertMultipleAfterDistributesShortGapEvenly() {
        val results = SubtitleEntryOps.createInsertedEntries(
            after = true,
            reference = SubtitleEntry(endTime = 20_000L),
            previous = null,
            next = SubtitleEntry(startTime = 24_500L),
            texts = listOf("一", "二", "三")
        )

        assertEquals(listOf(20_000L, 21_500L, 23_000L), results.map { it.startTime })
        assertEquals(listOf(21_500L, 23_000L, 24_500L), results.map { it.endTime })
    }
}
