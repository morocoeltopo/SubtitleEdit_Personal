package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

/**
 * 播放高亮的定位游标。
 *
 * 播放时每帧都要问「当前时间落在哪条字幕上」，全量线性扫描在几千条字幕下开销可观。
 * 这里利用「字幕基本有序 + 播放单调前进」的特性缓存上次结果及其有效区间：
 * 只要播放头还在该区间内就直接返回，落在区间外才向后探几条，再不行才全量兜底。
 *
 * 调用方传入的列表可能被就地修改（[SubtitleEntry] 是可变的），因此缓存命中时会重新
 * 校验该条目的起止时间；命中空隙（返回 -1）无法自校验，需由调用方在列表变更后调用
 * [invalidate]。
 *
 * 语义差异：字幕重叠时，只要播放头仍在当前高亮条内就保持不动，而不会回跳到列表里
 * 更靠前的那条。全量兜底扫描仍然返回第一个命中项。
 */
internal class SubtitleHighlightCursor {

    private var cachedIndex = INVALID_INDEX
    private var validFromMs = 0L
    private var validUntilMs = 0L
    private var hasCache = false

    /** 返回 [timeMs] 命中的条目下标，落在空隙则返回 -1。 */
    fun resolve(entries: List<SubtitleEntry>, timeMs: Long): Int {
        if (hasCache && timeMs >= validFromMs && timeMs < validUntilMs) {
            if (cachedIndex == INVALID_INDEX) return INVALID_INDEX
            val cached = entries.getOrNull(cachedIndex)
            // 起止时间未变说明缓存仍然有效（列表可能已被就地修改）
            if (cached != null &&
                cached.startTime == validFromMs &&
                cached.endTime == validUntilMs
            ) {
                return cachedIndex
            }
        }

        if (hasCache && cachedIndex != INVALID_INDEX) {
            val scanEnd = minOf(cachedIndex + 1 + NEIGHBOR_SCAN, entries.size)
            for (index in cachedIndex + 1 until scanEnd) {
                val entry = entries[index]
                if (timeMs >= entry.startTime && timeMs < entry.endTime) {
                    return remember(index, entry.startTime, entry.endTime)
                }
                // 有序列表里起点已越过播放头，后面不可能再命中，交给兜底扫描
                if (entry.startTime > timeMs) break
            }
        }

        return fullScan(entries, timeMs)
    }

    /** 列表结构或时间轴发生变化后调用，丢弃缓存。 */
    fun invalidate() {
        hasCache = false
        cachedIndex = INVALID_INDEX
    }

    private fun fullScan(entries: List<SubtitleEntry>, timeMs: Long): Int {
        var nextStartMs = Long.MAX_VALUE
        for (index in entries.indices) {
            val entry = entries[index]
            if (timeMs >= entry.startTime && timeMs < entry.endTime) {
                return remember(index, entry.startTime, entry.endTime)
            }
            if (entry.startTime > timeMs && entry.startTime < nextStartMs) {
                nextStartMs = entry.startTime
            }
        }
        // 空隙：从当前时间到下一条字幕开始之前，答案都是 -1
        return remember(INVALID_INDEX, timeMs, nextStartMs)
    }

    private fun remember(index: Int, fromMs: Long, untilMs: Long): Int {
        cachedIndex = index
        validFromMs = fromMs
        validUntilMs = untilMs
        hasCache = true
        return index
    }

    private companion object {
        const val INVALID_INDEX = -1

        /** 缓存失效后先向后试探的条目数，覆盖顺序播放跨条的常见情况。 */
        const val NEIGHBOR_SCAN = 4
    }
}
