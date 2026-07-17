package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTextFormatterTest {
    @Test
    fun removesConfiguredEndPunctuationFromEveryTextLine() {
        val options = SubtitleFormattingOptions(endPunctuation = "。！？!?".toSet())
        assertEquals(
            "第一行\n第二行",
            SubtitleTextFormatter.format("第一行。\n第二行！？", options)
        )
    }

    @Test
    fun removesEndPunctuationBeforeClosingQuote() {
        val options = SubtitleFormattingOptions(endPunctuation = setOf('。'))
        assertEquals("“你好”", SubtitleTextFormatter.format("“你好。”", options))
    }

    @Test
    fun removesOnlyInnerConfiguredPunctuation() {
        val options = SubtitleFormattingOptions(innerPunctuation = setOf('，', '！'))
        assertEquals("你好世界！！", SubtitleTextFormatter.format("你好，世界！！", options))
    }

    @Test
    fun removesHalfAndFullWidthSpacesAndReplacesPunctuation() {
        val options = SubtitleFormattingOptions(
            removeSpaces = true,
            replaceFrom = ",",
            replaceTo = "，"
        )
        assertEquals("你好吗，朋友", SubtitleTextFormatter.format("你 好吗,　朋友", options))
    }

    @Test
    fun replacesOnlyInnerPunctuation() {
        val options = SubtitleFormattingOptions(
            replaceFrom = "，",
            replaceTo = ",",
            replacementScope = PunctuationReplacementScope.INNER
        )
        assertEquals("你好,朋友，", SubtitleTextFormatter.format("你好，朋友，", options))
    }

    @Test
    fun replacesOnlyEndPunctuationBeforeQuote() {
        val options = SubtitleFormattingOptions(
            replaceFrom = "。",
            replaceTo = "！",
            replacementScope = PunctuationReplacementScope.END
        )
        assertEquals("“你好！”", SubtitleTextFormatter.format("“你好。”", options))
    }

    @Test
    fun addsEndPunctuationBeforeQuoteWithoutDuplicatingIt() {
        val options = SubtitleFormattingOptions(addEndPunctuation = "。")
        assertEquals("“你好。”", SubtitleTextFormatter.format("“你好”", options))
        assertEquals("你好。", SubtitleTextFormatter.format("你好。", options))
    }
}
