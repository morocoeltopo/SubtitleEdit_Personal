package com.subtitleedit.util

import com.subtitleedit.model.SubtitleEntry

object SubtitlePasteOps {
    data class PasteAtPositionResult(
        val structureChanged: Boolean,
        val affectedPositions: Set<Int>
    )

    data class PasteToSelectionResult(
        val affectedPositions: Set<Int>
    )

    fun pasteAtPosition(
        entries: MutableList<SubtitleEntry>,
        position: Int,
        clipboardTexts: List<String>
    ): PasteAtPositionResult {
        if (clipboardTexts.isEmpty() || position !in entries.indices) {
            return PasteAtPositionResult(structureChanged = false, affectedPositions = emptySet())
        }

        entries[position].text = clipboardTexts.first()
        if (clipboardTexts.size == 1) {
            return PasteAtPositionResult(
                structureChanged = false,
                affectedPositions = setOf(position)
            )
        }

        // 多出的文本按“向后插入”规则创建目标行，时间不从剪贴板读取。
        for (i in 1 until clipboardTexts.size) {
            val previous = entries[position + i - 1]
            entries.add(
                position + i,
                SubtitleEntry(
                    startTime = previous.endTime,
                    endTime = previous.endTime + 3_000L,
                    text = clipboardTexts[i]
                )
            )
        }
        val affected = (position until (position + clipboardTexts.size)).toSet()
        return PasteAtPositionResult(structureChanged = true, affectedPositions = affected)
    }

    fun pasteToSelection(
        entries: MutableList<SubtitleEntry>,
        selectedPositions: List<Int>,
        clipboardTexts: List<String>
    ): PasteToSelectionResult {
        val validPositions = selectedPositions.distinct().sorted().filter { it in entries.indices }
        if (validPositions.size != clipboardTexts.size) {
            return PasteToSelectionResult(emptySet())
        }

        validPositions.forEachIndexed { index, position ->
            entries[position].text = clipboardTexts[index]
        }
        return PasteToSelectionResult(validPositions.toSet())
    }
}
