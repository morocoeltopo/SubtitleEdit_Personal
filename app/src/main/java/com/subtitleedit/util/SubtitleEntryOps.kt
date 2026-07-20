package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

object SubtitleEntryOps {
    private const val DEFAULT_INSERT_DURATION_MS = 3_000L

    fun deepCopy(entry: SubtitleEntry): SubtitleEntry {
        return entry.copy()
    }

    fun deepCopy(entries: List<SubtitleEntry>): List<SubtitleEntry> {
        return entries.map { it.copy() }
    }

    fun createInsertedEntry(
        after: Boolean,
        reference: SubtitleEntry,
        previous: SubtitleEntry?,
        next: SubtitleEntry?
    ): SubtitleEntry = createInsertedEntries(
        after = after,
        reference = reference,
        previous = previous,
        next = next,
        texts = listOf("新字幕")
    ).first()

    fun createInsertedEntries(
        after: Boolean,
        reference: SubtitleEntry,
        previous: SubtitleEntry?,
        next: SubtitleEntry?,
        texts: List<String>
    ): List<SubtitleEntry> {
        if (texts.isEmpty()) return emptyList()

        val count = texts.size
        val defaultTotalDuration = DEFAULT_INSERT_DURATION_MS * count
        val groupStart: Long
        val groupEnd: Long

        if (after) {
            groupStart = reference.endTime
            val defaultEnd = groupStart + defaultTotalDuration
            groupEnd = next?.startTime
                ?.takeIf { it > groupStart && it < defaultEnd }
                ?: defaultEnd
        } else {
            groupEnd = reference.startTime
            val defaultStart = (groupEnd - defaultTotalDuration).coerceAtLeast(0L)
            groupStart = previous?.endTime
                ?.takeIf { it > defaultStart && it < groupEnd }
                ?: defaultStart
        }

        val totalDuration = groupEnd - groupStart
        return texts.mapIndexed { index, text ->
            SubtitleEntry(
                startTime = groupStart + totalDuration * index / count,
                endTime = groupStart + totalDuration * (index + 1) / count,
                text = text
            )
        }
    }

    fun applyOffset(entry: SubtitleEntry, offsetMs: Long) {
        entry.startTime = (entry.startTime + offsetMs).coerceAtLeast(0)
        entry.endTime = (entry.endTime + offsetMs).coerceAtLeast(entry.startTime + 1)
    }

    fun applyOffsetAll(entries: Iterable<SubtitleEntry>, offsetMs: Long) {
        entries.forEach { applyOffset(it, offsetMs) }
    }
}
