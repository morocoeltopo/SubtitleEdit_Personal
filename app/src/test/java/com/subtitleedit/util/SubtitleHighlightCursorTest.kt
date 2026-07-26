package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleHighlightCursorTest {

    private fun entries(vararg ranges: Pair<Long, Long>): MutableList<SubtitleEntry> =
        ranges.mapIndexed { index, (start, end) ->
            SubtitleEntry(index = index + 1, startTime = start, endTime = end, text = "t${index + 1}")
        }.toMutableList()

    /** 0-1000 / 1000-2000 / 2000-3000，三条首尾相接。 */
    private fun contiguous() = entries(0L to 1000L, 1000L to 2000L, 2000L to 3000L)

    // ==================== 顺序播放 ====================

    @Test
    fun resolve_sequentialPlaybackHitsEachEntry() {
        val cursor = SubtitleHighlightCursor()
        val list = contiguous()
        assertEquals(0, cursor.resolve(list, 0L))
        assertEquals(0, cursor.resolve(list, 500L))
        assertEquals(1, cursor.resolve(list, 1000L))
        assertEquals(1, cursor.resolve(list, 1999L))
        assertEquals(2, cursor.resolve(list, 2000L))
        assertEquals(2, cursor.resolve(list, 2999L))
    }

    @Test
    fun resolve_repeatedSameTimeIsStable() {
        val cursor = SubtitleHighlightCursor()
        val list = contiguous()
        repeat(5) { assertEquals(1, cursor.resolve(list, 1500L)) }
    }

    @Test
    fun resolve_jumpBeyondNeighborWindowFallsBackToFullScan() {
        val cursor = SubtitleHighlightCursor()
        // 20 条，邻近前探窗口只有 4 条，跳到第 15 条必须靠全量兜底
        val list = entries(*Array(20) { (it * 1000L) to (it * 1000L + 1000L) })
        assertEquals(0, cursor.resolve(list, 500L))
        assertEquals(15, cursor.resolve(list, 15_500L))
        assertEquals(15, cursor.resolve(list, 15_900L))
    }

    @Test
    fun resolve_backwardSeekRelocates() {
        val cursor = SubtitleHighlightCursor()
        val list = contiguous()
        assertEquals(2, cursor.resolve(list, 2500L))
        assertEquals(0, cursor.resolve(list, 100L))
        assertEquals(1, cursor.resolve(list, 1200L))
    }

    // ==================== 半开区间边界 ====================

    @Test
    fun resolve_startTimeIsInclusiveEndTimeIsExclusive() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(1000L to 2000L)
        assertEquals(-1, cursor.resolve(list, 999L))
        assertEquals(0, cursor.resolve(list, 1000L))
        assertEquals(0, cursor.resolve(list, 1999L))
        assertEquals(-1, cursor.resolve(list, 2000L))
    }

    @Test
    fun resolve_zeroLengthEntryNeverMatches() {
        val cursor = SubtitleHighlightCursor()
        assertEquals(-1, SubtitleHighlightCursor().resolve(entries(1000L to 1000L), 1000L))
        assertEquals(-1, cursor.resolve(entries(0L to 0L), 0L))
    }

    // ==================== 空隙 ====================

    @Test
    fun resolve_gapReturnsMinusOneAndStaysStable() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 1000L, 5000L to 6000L)
        assertEquals(0, cursor.resolve(list, 500L))
        assertEquals(-1, cursor.resolve(list, 1000L))
        assertEquals(-1, cursor.resolve(list, 2000L))
        assertEquals(-1, cursor.resolve(list, 4999L))
    }

    @Test
    fun resolve_gapExitEntersNextEntry() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 1000L, 5000L to 6000L)
        assertEquals(-1, cursor.resolve(list, 2000L))
        assertEquals(1, cursor.resolve(list, 5000L))
        assertEquals(1, cursor.resolve(list, 5500L))
        assertEquals(-1, cursor.resolve(list, 6000L))
    }

    @Test
    fun resolve_gapBeforeFirstEntry() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(3000L to 4000L)
        assertEquals(-1, cursor.resolve(list, 0L))
        assertEquals(-1, cursor.resolve(list, 2999L))
        assertEquals(0, cursor.resolve(list, 3000L))
    }

    @Test
    fun resolve_gapAfterLastEntryHasNoUpperBound() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 1000L)
        assertEquals(-1, cursor.resolve(list, 1000L))
        assertEquals(-1, cursor.resolve(list, Long.MAX_VALUE - 1))
    }

    // ==================== 退化输入 ====================

    @Test
    fun resolve_emptyListAlwaysReturnsMinusOne() {
        val cursor = SubtitleHighlightCursor()
        val list = mutableListOf<SubtitleEntry>()
        assertEquals(-1, cursor.resolve(list, 0L))
        assertEquals(-1, cursor.resolve(list, 12_345L))
    }

    @Test
    fun resolve_singleEntryList() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 1000L)
        assertEquals(0, cursor.resolve(list, 0L))
        assertEquals(0, cursor.resolve(list, 999L))
        assertEquals(-1, cursor.resolve(list, 1000L))
        assertEquals(0, cursor.resolve(list, 500L))
    }

    // ==================== 列表就地修改 ====================

    @Test
    fun resolve_cachedEntryTimeChangedTriggersRescan() {
        val cursor = SubtitleHighlightCursor()
        val list = contiguous()
        assertEquals(1, cursor.resolve(list, 1500L))
        // 把命中条目改短，缓存自校验应发现起止时间已变并重扫
        list[1].endTime = 1200L
        assertEquals(-1, cursor.resolve(list, 1500L))
        assertEquals(2, cursor.resolve(list, 2500L))
    }

    @Test
    fun resolve_entryRemovedFromCachedIndexTriggersRescan() {
        val cursor = SubtitleHighlightCursor()
        val list = contiguous()
        assertEquals(2, cursor.resolve(list, 2500L))
        list.removeAt(0)
        // 原下标 2 已越界，自校验兜底后应命中新的下标 1
        assertEquals(1, cursor.resolve(list, 2500L))
    }

    @Test
    fun invalidate_dropsGapCache() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 1000L, 5000L to 6000L)
        assertEquals(-1, cursor.resolve(list, 2000L))
        // 空隙里插入一条新字幕；空隙缓存无法自校验，必须显式失效
        list.add(1, SubtitleEntry(index = 2, startTime = 1500L, endTime = 2500L, text = "new"))
        cursor.invalidate()
        assertEquals(1, cursor.resolve(list, 2000L))
    }

    @Test
    fun invalidate_beforeAnyResolveIsHarmless() {
        val cursor = SubtitleHighlightCursor()
        cursor.invalidate()
        assertEquals(0, cursor.resolve(contiguous(), 500L))
    }

    // ==================== 重叠与乱序 ====================

    @Test
    fun resolve_overlappingEntriesKeepCurrentHighlight() {
        val cursor = SubtitleHighlightCursor()
        // 第 2 条覆盖第 1 条的后半段
        val list = entries(0L to 2000L, 1000L to 3000L)
        assertEquals(1, cursor.resolve(list, 2500L))
        // 播放头退回重叠区间时保持当前条，而不是回跳到更靠前的第 1 条
        assertEquals(1, cursor.resolve(list, 1500L))
    }

    @Test
    fun resolve_overlappingEntriesFullScanReturnsFirstMatch() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(0L to 2000L, 1000L to 3000L)
        // 冷启动（无缓存）走全量扫描，保持「返回第一个命中」的原语义
        assertEquals(0, cursor.resolve(list, 1500L))
    }

    @Test
    fun resolve_unorderedListStillFindsMatch() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(4000L to 5000L, 0L to 1000L, 2000L to 3000L)
        assertEquals(1, cursor.resolve(list, 500L))
        assertEquals(2, cursor.resolve(list, 2500L))
        assertEquals(0, cursor.resolve(list, 4500L))
        assertEquals(-1, cursor.resolve(list, 3500L))
    }

    @Test
    fun resolve_unorderedGapUsesNearestFollowingStart() {
        val cursor = SubtitleHighlightCursor()
        val list = entries(9000L to 10_000L, 2000L to 3000L)
        assertEquals(-1, cursor.resolve(list, 1000L))
        // 空隙的有效区间应止于最近的后继起点 2000，而不是列表首项的 9000
        assertEquals(1, cursor.resolve(list, 2500L))
    }
}
